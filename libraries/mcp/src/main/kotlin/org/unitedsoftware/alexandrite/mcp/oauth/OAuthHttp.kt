package org.unitedsoftware.alexandrite.mcp.oauth

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.http.common.HttpCall
import org.unitedsoftware.alexandrite.http.common.HttpException
import org.unitedsoftware.alexandrite.http.common.HttpTransport
import org.unitedsoftware.alexandrite.http.common.printable
import org.unitedsoftware.alexandrite.mcp.client.string
import org.unitedsoftware.alexandrite.mcp.isLoopback
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/** A login or a token request that failed, with a message that holds no credential. */
internal open class OAuthException(message: String) : Exception(message)

/** An error that an authorization server answered with, such as `invalid_grant`. */
internal class OAuthErrorException(val error: String, message: String) : OAuthException(message)

/** The JSON requests of the OAuth flow, whose failures name the endpoint without its query. */
internal class OAuthHttp(private val transport: HttpTransport) {
    /** The JSON object at [uri], null when the server answers with a 4xx status. */
    suspend fun find(uri: URI, what: String): JsonObject? {
        val text = try {
            transport.send(HttpCall("GET", uri, mapOf(ACCEPT to JSON)), LIMIT)
        } catch (e: HttpException) {
            if (e.status in 400..499) return null
            throw OAuthException("Cannot fetch the $what at ${printable(uri)}: ${reason(e)}")
        }
        return parse(text) ?: throw OAuthException("The $what at ${printable(uri)} is no JSON object.")
    }

    /** POSTs [form] to [uri] with [headers], masking [secrets], and returns the JSON object it answers. */
    suspend fun postForm(
        uri: URI,
        form: Map<String, String>,
        headers: Map<String, String>,
        secrets: List<String>,
        what: String,
    ): JsonObject {
        val body = form.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        val call = HttpCall(
            "POST",
            uri,
            headers + mapOf(ACCEPT to JSON, CONTENT_TYPE to FORM),
            body,
            secrets + form.values.filter { it.length >= MIN_SECRET },
        )
        return answer(call, what)
    }

    suspend fun postJson(uri: URI, body: JsonObject, what: String): JsonObject {
        val call = HttpCall(
            "POST",
            uri,
            mapOf(ACCEPT to JSON, CONTENT_TYPE to JSON),
            JSON_FORMAT.encodeToString(JsonObject.serializer(), body),
        )
        return answer(call, what)
    }

    private suspend fun answer(call: HttpCall, what: String): JsonObject {
        val text = try {
            transport.send(call, LIMIT)
        } catch (e: HttpException) {
            val error = e.body?.let(::parse)
            val code = error?.string("error")
            if (e.status in 400..499 && code != null) {
                throw OAuthErrorException(code, "The authorization server refused the $what: $code.")
            }
            throw OAuthException("The $what at ${printable(call.uri)} failed: ${reason(e)}")
        }
        return parse(text) ?: throw OAuthException("The authorization server answered the $what with no JSON object.")
    }

    private fun reason(e: HttpException): String = e.status?.let { "HTTP $it" } ?: e.kind.name.lowercase()

    companion object {
        const val JSON: String = "application/json"
        private const val FORM = "application/x-www-form-urlencoded"
        private const val ACCEPT = "Accept"
        private const val CONTENT_TYPE = "Content-Type"
        private const val LIMIT = 1024 * 1024
        private const val MIN_SECRET = 8
        val JSON_FORMAT: Json = Json { ignoreUnknownKeys = true }

        /** [text] as a JSON object, null when it is none. */
        fun parse(text: String): JsonObject? = try {
            JSON_FORMAT.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** [text] as an https URI, or an http one on the loopback interface, else the failure that names it [what]. */
internal fun secureUri(text: String, what: String): URI {
    val uri = try {
        URI(text)
    } catch (e: URISyntaxException) {
        null
    }
    val scheme = uri?.scheme?.lowercase()
    if (uri == null || uri.host.isNullOrEmpty() || uri.rawFragment != null) {
        throw OAuthException("The $what '$text' is no absolute URL.")
    }
    if (scheme != "https" && !(scheme == "http" && isLoopback(uri.host))) {
        throw OAuthException("The $what '$text' does not use https.")
    }
    return uri
}
