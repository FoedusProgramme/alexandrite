package org.unitedsoftware.alexandrite.tools.web

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.web.FetchedPage
import org.unitedsoftware.alexandrite.sdk.web.WebFetchBackend
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend
import org.unitedsoftware.alexandrite.sdk.web.WebSearchResult
import java.net.URI

/** The id both the search and the fetch backend of a Pleochroic account or a self-hosted `standalone-service` use. */
internal const val PLEOCHROIC_WEB_ID: String = "pleochroic"

@Singleton
@Contribute(WebSearchBackend::class)
internal class PleochroicSearch(private val config: WebConfig, private val http: SearchHttp) : WebSearchBackend {
    override val id: String = PLEOCHROIC_WEB_ID

    override suspend fun search(query: WebQuery): List<WebSearchResult> {
        val (base, key) = pleochroicCredentials(config)
        val body = buildJsonObject {
            put("query", query.text)
            put("count", query.limit.coerceAtMost(20))
        }
        val json = pleochroicCall(http, base, key, "/search", body.toString())
        return (json["results"] as? JsonArray).orEmpty().mapNotNull { result ->
            val obj = result as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            WebSearchResult(
                (obj["title"] as? JsonPrimitive)?.content.orEmpty().take(1000),
                url.take(4000),
                (obj["snippet"] as? JsonPrimitive)?.content.orEmpty().take(4000),
            )
        }
    }
}

@Singleton
@Contribute(WebFetchBackend::class)
internal class PleochroicFetch(private val config: WebConfig, private val http: SearchHttp) : WebFetchBackend {
    override val id: String = PLEOCHROIC_WEB_ID

    override suspend fun fetch(url: String): FetchedPage {
        val (base, key) = pleochroicCredentials(config)
        val body = buildJsonObject {
            put("url", url)
            put("format", "markdown")
        }
        val json = pleochroicCall(http, base, key, "/fetch", body.toString())
        val content = (json["content"] as? JsonPrimitive)?.content.orEmpty()
        val fetchedUrl = (json["url"] as? JsonPrimitive)?.content ?: url
        return FetchedPage(fetchedUrl, content)
    }
}

/** The configured `apiBase` and key of a Pleochroic account or self-hosted `standalone-service`. */
internal fun pleochroicCredentials(config: WebConfig): Pair<String, String> {
    val base = requireNotNull(config.pleochroicApiBase) {
        "Configure web.pleochroicApiBase with the signed-in account's apiBase or a standalone-service URL."
    }
    val key = requireNotNull(config.pleochroicApiKey) {
        "Configure web.pleochroicApiKey with an environment reference."
    }.reveal()
    return base.trimEnd('/') to key
}

/** `POST [base][path]` with [body], bearer-authenticated with [key], mapped to a clear failure on an error. */
private suspend fun pleochroicCall(
    http: SearchHttp,
    base: String,
    key: String,
    path: String,
    body: String,
): JsonObject = try {
    http.send(
        HttpCall(
            "POST",
            URI("$base$path"),
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $key"),
            body = body,
            secrets = listOf(key),
        ),
    )
} catch (e: HttpException) {
    throw pleochroicFailure(e)
}

/** What Pleochroic's `{error: {code, message}}` body at [e] means, told without the account's key. */
internal fun pleochroicFailure(e: HttpException): Exception {
    val error = e.body?.let {
        try {
            (Json.parseToJsonElement(it) as? JsonObject)?.get("error")?.jsonObject
        } catch (parse: SerializationException) {
            null
        }
    }
    val code = (error?.get("code") as? JsonPrimitive)?.content
    val message = (error?.get("message") as? JsonPrimitive)?.content
    return IllegalStateException(
        when (code) {
            "not_entitled" -> "The Pleochroic account's plan does not include this service."

            "missing_scope" -> "The Pleochroic key is missing the scope this service needs."

            "insufficient_credits" -> "The Pleochroic account's plan and credits are both exhausted."

            "quota_exceeded" -> "The Pleochroic account's quota for this service is used up."

            "rate_limited" -> message?.let { "Pleochroic is rate-limiting this account: $it" }
                ?: "Pleochroic is rate-limiting this account; try again shortly."

            "upstream_error" -> message?.let { "Pleochroic's engine failed upstream: $it" }
                ?: "Pleochroic's engine failed upstream."

            else -> message ?: "Pleochroic request failed (${e.kind})."
        },
    )
}
