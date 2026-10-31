package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.sdk.browse.BrowseAbilities
import org.unitedsoftware.alexandrite.sdk.browse.BrowseBackend
import org.unitedsoftware.alexandrite.sdk.browse.BrowseImage
import org.unitedsoftware.alexandrite.sdk.browse.BrowseOpen
import org.unitedsoftware.alexandrite.sdk.browse.BrowseReport
import org.unitedsoftware.alexandrite.sdk.browse.BrowseSessionGone
import org.unitedsoftware.alexandrite.sdk.browse.BrowseStep
import org.unitedsoftware.alexandrite.sdk.browse.BrowseSurface
import org.unitedsoftware.alexandrite.sdk.browse.BrowseTarget
import org.unitedsoftware.alexandrite.sdk.browse.BrowseView
import org.unitedsoftware.alexandrite.sdk.browse.MouseButton
import org.unitedsoftware.alexandrite.sdk.browse.PageLoad
import org.unitedsoftware.alexandrite.sdk.browse.ScrollDirection
import org.unitedsoftware.alexandrite.sdk.browse.WebStep
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
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
@Contribute(BrowseBackend::class)
internal class PleochroicBrowser(private val config: WebConfig) :
    BrowseBackend,
    Lifecycle {
    override val id: String = PLEOCHROIC_WEB_ID

    override val surface: BrowseSurface = BrowseSurface.WEB

    override val abilities: BrowseAbilities = BrowseAbilities(
        steps = BrowseStep.Kind.entries.toSet() - BrowseStep.Kind.HOVER - BrowseStep.Kind.DRAG,
        views = setOf(BrowseView.LAYOUT, BrowseView.IMAGE),
        targets = setOf(BrowseTarget.Kind.REF, BrowseTarget.Kind.SELECTOR),
        buttons = setOf(MouseButton.LEFT),
        clicks = 1,
        scrolls = setOf(ScrollDirection.UP, ScrollDirection.DOWN),
    )

    override val sessionNote: String = "Close the session when done: it is billed while open."

    // A step may take the service 45 s, and a screenshot's answer runs to megabytes
    private val transport = HttpTransport(HttpTimeouts(10.seconds, STEP_TIMEOUT, 30.seconds))

    override suspend fun open(options: BrowseOpen): String {
        val body = buildJsonObject {
            put("idleSeconds", options.idleSeconds)
            options.locale?.let { put("locale", it) }
        }
        val answer = call("POST", "/browser/sessions", body.toString(), session = false)
        return requireNotNull((answer["id"] as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty)) {
            "Pleochroic opened a browser session without an id."
        }
    }

    override fun check(step: BrowseStep) {
        if (step !is BrowseStep.Look) buildJsonObject { arguments(step) }
    }

    override suspend fun act(session: String, step: BrowseStep): BrowseReport {
        if (step !is BrowseStep.Look) return report(action(session, step.kind.name.lowercase()) { arguments(step) })
        // The service reads the layout and the image in separate actions
        val layout = if (BrowseView.LAYOUT in step.views) report(action(session, "snapshot") {}) else null
        val image = if (BrowseView.IMAGE in step.views) {
            report(action(session, "screenshot") { if (step.fullPage) put("fullPage", true) })
        } else {
            null
        }
        val last = image ?: layout!!
        return BrowseReport(last.url, last.title, layout?.layout, image?.image)
    }

    private suspend fun action(session: String, action: String, fields: JsonObjectBuilder.() -> Unit): JsonObject {
        val body = buildJsonObject {
            put("action", action)
            fields()
        }
        return call("POST", "/browser/sessions/${segment(session)}/actions", body.toString(), session = true)
    }

    private fun report(answer: JsonObject): BrowseReport {
        val screenshot = answer["screenshot"] as? JsonObject
        val data = (screenshot?.get("data") as? JsonPrimitive)?.content
        val mediaType = (screenshot?.get("mediaType") as? JsonPrimitive)?.content ?: "image/png"
        return BrowseReport(
            url = (answer["url"] as? JsonPrimitive)?.content.orEmpty(),
            title = (answer["title"] as? JsonPrimitive)?.content.orEmpty(),
            layout = (answer["snapshot"] as? JsonPrimitive)?.content,
            image = data?.let { BrowseImage(Base64.getDecoder().decode(it), mediaType) },
            value = answer["value"],
        )
    }

    override suspend fun close(session: String) {
        try {
            call("DELETE", "/browser/sessions/${segment(session)}", null, session = true)
        } catch (e: BrowseSessionGone) {
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
                throw BrowseSessionGone(serviceMessage(e) ?: "The browser session has ended.")
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

        /** [step]'s fields as the service's action names them. */
        fun JsonObjectBuilder.arguments(step: BrowseStep) {
            when (step) {
                is WebStep.Navigate -> {
                    require(step.url.startsWith("http://") || step.url.startsWith("https://")) {
                        "url is an http or https URL."
                    }
                    put("url", step.url)
                    if (step.until != PageLoad.LOAD) put("waitUntil", step.until.name.lowercase().replace("_", ""))
                }

                is BrowseStep.Click -> {
                    require(step.button == MouseButton.LEFT && step.count == 1) {
                        "Pleochroic's browser clicks once with the left button only."
                    }
                    target(step.target)
                }

                is BrowseStep.Type -> {
                    target(requireNotNull(step.target) { "Pleochroic's browser types into a ref or a selector." })
                    require(step.text.length <= MAX_TYPED) { "text runs to at most $MAX_TYPED characters." }
                    put("text", step.text)
                    if (step.submit) put("submit", true)
                }

                is BrowseStep.Press -> put("key", step.key)

                is BrowseStep.Scroll -> {
                    require(step.direction == ScrollDirection.UP || step.direction == ScrollDirection.DOWN) {
                        "Pleochroic's browser scrolls up or down only."
                    }
                    require(step.at == null) { "Pleochroic's browser scrolls the whole page only." }
                    require(step.amount.let { it == null || it in 1..MAX_SCROLL }) {
                        "amount is from 1 to $MAX_SCROLL pixels."
                    }
                    put("direction", step.direction.name.lowercase())
                    step.amount?.let { put("amount", it) }
                }

                is WebStep.Select -> {
                    require(step.values.size in 1..MAX_VALUES) { "values holds 1 to $MAX_VALUES options." }
                    target(step.target)
                    putJsonArray("values") { step.values.forEach { add(JsonPrimitive(it)) } }
                }

                is BrowseStep.Wait -> {
                    require(step.seconds != null || step.text != null) { "wait takes seconds, text or both." }
                    require(step.seconds.let { it == null || (it > 0 && it <= 30) }) {
                        "seconds is more than 0 and at most 30."
                    }
                    step.seconds?.let { put("seconds", it) }
                    step.text?.let { put("text", it) }
                }

                is WebStep.Evaluate -> {
                    require(step.expression.isNotBlank()) { "expression is missing." }
                    put("expression", step.expression)
                }

                WebStep.Back, WebStep.Forward, WebStep.Reload -> {}

                else -> throw IllegalArgumentException(
                    "Pleochroic's browser has no ${step.kind.name.lowercase()} step.",
                )
            }
        }

        fun JsonObjectBuilder.target(target: BrowseTarget) {
            when (target) {
                is BrowseTarget.Ref -> {
                    require(REF.matches(target.ref)) { "ref is one from the latest snapshot, such as e12." }
                    put("ref", target.ref)
                }

                is BrowseTarget.Selector -> put("selector", target.selector)

                is BrowseTarget.Point -> throw IllegalArgumentException("Pleochroic's browser has no point clicks.")
            }
        }

        /** The service's limits, checked here so that a step it would refuse opens no session. */
        const val MAX_TYPED = 10_000
        const val MAX_SCROLL = 10_000
        const val MAX_VALUES = 100
        val REF = Regex("e\\d+")

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
