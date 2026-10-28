package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.web.BrowserOpen
import org.unitedsoftware.alexandrite.sdk.web.BrowserSessionGone
import org.unitedsoftware.alexandrite.sdk.web.BrowserStep
import org.unitedsoftware.alexandrite.sdk.web.BrowserView
import org.unitedsoftware.alexandrite.sdk.web.WebBrowserBackend
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * The headless browser of a Pleochroic account or a self-hosted `standalone-service`, at the same `apiBase` and with
 * the same key as its search and fetch ([PleochroicSearch], [PleochroicFetch]): `POST /browser/sessions`, then
 * `POST /browser/sessions/{id}/actions` per step, and `DELETE /browser/sessions/{id}`.
 */
@Singleton
@Contribute(WebBrowserBackend::class)
internal class PleochroicBrowser(private val config: WebConfig) :
    WebBrowserBackend,
    Lifecycle {
    override val id: String = PLEOCHROIC_WEB_ID

    // A step may take the service 45 s, and a screenshot's answer runs to megabytes
    private val transport = HttpTransport(HttpTimeouts(10.seconds, STEP_TIMEOUT, 30.seconds))

    override suspend fun open(options: BrowserOpen): String {
        val body = buildJsonObject {
            put("idleSeconds", options.idleSeconds)
            options.locale?.let { put("locale", it) }
        }
        val answer = call("POST", "/browser/sessions", body.toString(), session = false)
        return requireNotNull((answer["id"] as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty)) {
            "Pleochroic opened a browser session without an id."
        }
    }

    override suspend fun act(session: String, step: BrowserStep): BrowserView {
        val body = buildJsonObject {
            put("action", step.action)
            step.arguments.forEach { (name, value) -> if (name != "action") put(name, value) }
        }
        val answer = call("POST", "/browser/sessions/${segment(session)}/actions", body.toString(), session = true)
        val screenshot = (answer["screenshot"] as? JsonObject)?.get("data")?.let { (it as? JsonPrimitive)?.content }
        return BrowserView(
            url = (answer["url"] as? JsonPrimitive)?.content.orEmpty(),
            title = (answer["title"] as? JsonPrimitive)?.content.orEmpty(),
            snapshot = (answer["snapshot"] as? JsonPrimitive)?.content,
            screenshot = screenshot?.let { Base64.getDecoder().decode(it) },
            value = answer["value"],
        )
    }

    override suspend fun close(session: String) {
        try {
            call("DELETE", "/browser/sessions/${segment(session)}", null, session = true)
        } catch (e: BrowserSessionGone) {
            // already closed
        }
    }

    /** [session]: the path names a session, so its 404 means the session has ended rather than a missing endpoint. */
    private suspend fun call(method: String, path: String, body: String?, session: Boolean): JsonObject {
        val (base, key) = pleochroicCredentials(config)
        val headers = buildMap {
            put("Authorization", "Bearer $key")
            if (body != null) put("Content-Type", "application/json")
        }
        val text = try {
            withTimeout(STEP_TIMEOUT + 15.seconds) {
                transport.send(
                    HttpCall(method, URI("$base$path"), headers, body, secrets = listOf(key)),
                    MAX_ANSWER_BYTES,
                )
            }
        } catch (e: HttpException) {
            if (session &&
                e.status == 404
            ) {
                throw BrowserSessionGone(serviceMessage(e) ?: "The browser session has ended.")
            }
            throw pleochroicFailure(e)
        }
        return Json.parseToJsonElement(text) as? JsonObject
            ?: error("Pleochroic's browser answered with something other than a JSON object.")
    }

    override fun onDestroy() {
        transport.close()
    }

    private companion object {
        val STEP_TIMEOUT = 75.seconds

        /** A full-page screenshot, base64-encoded, is the largest answer the service gives. */
        const val MAX_ANSWER_BYTES = 40_000_000

        fun segment(session: String): String = URLEncoder.encode(session, Charsets.UTF_8).replace("+", "%20")

        /** The `message` of the service's `{error: {…}}` body, which never holds the key. */
        fun serviceMessage(e: HttpException): String? = e.body?.let {
            try {
                val error = (Json.parseToJsonElement(it) as? JsonObject)?.get("error") as? JsonObject
                (error?.get("message") as? JsonPrimitive)?.content
            } catch (parse: kotlinx.serialization.SerializationException) {
                null
            }
        }
    }
}
