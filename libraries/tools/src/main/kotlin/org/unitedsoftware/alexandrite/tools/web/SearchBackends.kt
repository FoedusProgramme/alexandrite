package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend
import org.unitedsoftware.alexandrite.sdk.web.WebSearchResult
import java.net.URI
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

/** The HTTP client of the built-in search backends, one for all their calls. */
@Singleton
internal class SearchHttp(private val config: WebConfig) : Lifecycle {
    private val timeout = config.timeoutSeconds.seconds
    private val transport = HttpTransport(HttpTimeouts(minOf(10.seconds, timeout), timeout, minOf(10.seconds, timeout)))

    suspend fun send(call: HttpCall): JsonObject = withTimeout(timeout) {
        Json.parseToJsonElement(transport.send(call, 1_000_000)) as? JsonObject
            ?: error("The search service answered with something other than a JSON object.")
    }

    /** The body as plain text, for a backend whose answer is not JSON (DuckDuckGo's HTML). */
    suspend fun sendText(call: HttpCall): String = withTimeout(timeout) { transport.send(call, 1_000_000) }

    override fun onDestroy() {
        transport.close()
    }
}

@Singleton
@Contribute(WebSearchBackend::class)
internal class BraveSearch(private val config: WebConfig, private val http: SearchHttp) : WebSearchBackend {
    override val id: String = ID

    override suspend fun search(query: WebQuery): List<WebSearchResult> {
        val key = requireNotNull(config.braveApiKey) {
            "Configure web.braveApiKey with an environment reference."
        }.reveal()
        return braveResults(http.send(braveCall(query.text, query.limit, key)), query.limit)
    }

    companion object {
        const val ID: String = "brave"
    }
}

@Singleton
@Contribute(WebSearchBackend::class)
internal class TavilySearch(private val config: WebConfig, private val http: SearchHttp) : WebSearchBackend {
    override val id: String = ID

    override suspend fun search(query: WebQuery): List<WebSearchResult> {
        val key = requireNotNull(config.tavilyApiKey) {
            "Configure web.tavilyApiKey with an environment reference."
        }.reveal()
        return tavilyResults(http.send(tavilyCall(query.text, query.limit, key)), query.limit)
    }

    companion object {
        const val ID: String = "tavily"
    }
}

/** A self-hosted SearXNG instance's JSON API, no key: `config.web.searxngUrl` names the instance. */
@Singleton
@Contribute(WebSearchBackend::class)
internal class SearxngSearch(private val config: WebConfig, private val http: SearchHttp) : WebSearchBackend {
    override val id: String = ID

    override suspend fun search(query: WebQuery): List<WebSearchResult> {
        val base = requireNotNull(config.searxngUrl) { "Configure web.searxngUrl with the instance's address." }
        return searxngResults(http.send(searxngCall(query.text, query.limit, base)), query.limit)
    }

    companion object {
        const val ID: String = "searxng"
    }
}

/**
 * DuckDuckGo's keyless HTML ("lite") endpoint, scraped rather than called through a documented JSON API: best
 * effort, and the first backend a change to that page's markup breaks, so operators who need a dependable search
 * should prefer a backend with a real API (Brave, Tavily, a self-hosted SearXNG).
 */
@Singleton
@Contribute(WebSearchBackend::class)
internal class DuckDuckGoSearch(private val http: SearchHttp) : WebSearchBackend {
    override val id: String = ID

    override suspend fun search(query: WebQuery): List<WebSearchResult> =
        duckDuckGoResults(http.sendText(duckDuckGoCall(query.text)), query.limit)

    companion object {
        const val ID: String = "duckduckgo"
    }
}

internal fun braveCall(
    query: String,
    limit: Int,
    key: String,
    base: String = "https://api.search.brave.com",
): HttpCall = HttpCall(
    "GET",
    URI("$base/res/v1/web/search?q=${URLEncoder.encode(query, Charsets.UTF_8)}&count=$limit"),
    headers = mapOf("Accept" to "application/json", "X-Subscription-Token" to key),
    secrets = listOf(key),
)

internal fun tavilyCall(query: String, limit: Int, key: String, base: String = "https://api.tavily.com"): HttpCall =
    HttpCall(
        "POST",
        URI("$base/search"),
        headers = mapOf("Content-Type" to "application/json"),
        body = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", limit)
            put("include_answer", false)
            put("include_raw_content", false)
        }.toString(),
        secrets = listOf(key),
    )

internal fun searxngCall(query: String, limit: Int, base: String): HttpCall = HttpCall(
    "GET",
    URI(
        "${base.trimEnd('/')}/search?q=${URLEncoder.encode(query, Charsets.UTF_8)}&format=json" +
            "&pageno=1&count=$limit",
    ),
    headers = mapOf("Accept" to "application/json"),
)

internal fun duckDuckGoCall(query: String, base: String = "https://html.duckduckgo.com"): HttpCall = HttpCall(
    "GET",
    URI("$base/html/?q=${URLEncoder.encode(query, Charsets.UTF_8)}"),
    headers = mapOf("Accept" to "text/html", "User-Agent" to "Mozilla/5.0 (compatible; Alexandrite)"),
)

internal fun braveResults(json: JsonObject, limit: Int): List<WebSearchResult> =
    results((json["web"] as? JsonObject)?.get("results"), "description", limit)

internal fun tavilyResults(json: JsonObject, limit: Int): List<WebSearchResult> =
    results(json["results"], "content", limit)

internal fun searxngResults(json: JsonObject, limit: Int): List<WebSearchResult> =
    results(json["results"], "content", limit)

/** The results of DuckDuckGo's HTML page [html]: each `result__a` link (its `uddg` redirect decoded to the real
 * URL) paired, in order, with the `result__snippet` that follows it. Best effort against markup this backend does
 * not control; a page it cannot make sense of simply yields no results rather than failing the call. */
internal fun duckDuckGoResults(html: String, limit: Int): List<WebSearchResult> {
    val links = RESULT_LINK.findAll(html).toList()
    val snippets = RESULT_SNIPPET.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
    return links.take(limit).mapIndexedNotNull { index, match ->
        val url = decodeDuckDuckGoUrl(match.groupValues[1]) ?: return@mapIndexedNotNull null
        val title = stripHtml(match.groupValues[2])
        WebSearchResult(title.take(1000), url.take(4000), snippets.getOrElse(index) { "" }.take(4000))
    }
}

/** The target of a DuckDuckGo redirect link such as `//duckduckgo.com/l/?uddg=<encoded>&rut=…`, decoded once (the
 * HTML entity `&amp;` first, then the URL encoding of the query value itself); the link as is when it already is
 * a plain `http(s)` URL. */
private fun decodeDuckDuckGoUrl(href: String): String? {
    val unescaped = href.replace("&amp;", "&")
    if (unescaped.startsWith("http://") || unescaped.startsWith("https://")) return unescaped
    val encoded = Regex("uddg=([^&]+)").find(unescaped)?.groupValues?.get(1) ?: return null
    val decoded = try {
        java.net.URLDecoder.decode(encoded, Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

private fun stripHtml(fragment: String): String =
    fragment.replace(HTML_TAG, "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

private val RESULT_LINK =
    Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
private val RESULT_SNIPPET =
    Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
private val HTML_TAG = Regex("<[^>]*>")

private fun results(element: JsonElement?, snippet: String, limit: Int): List<WebSearchResult> =
    (element as? JsonArray).orEmpty().take(limit).mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        fun field(key: String): String = (obj[key] as? JsonPrimitive)?.content.orEmpty()
        val url = field("url")
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        WebSearchResult(field("title").take(1000), url.take(4000), field(snippet).take(4000))
    }
