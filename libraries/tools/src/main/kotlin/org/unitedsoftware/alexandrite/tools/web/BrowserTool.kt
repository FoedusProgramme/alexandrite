package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.internal.media.normalizedImage
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart
import org.unitedsoftware.alexandrite.sdk.web.BrowserOpen
import org.unitedsoftware.alexandrite.sdk.web.BrowserSessionGone
import org.unitedsoftware.alexandrite.sdk.web.BrowserStep
import org.unitedsoftware.alexandrite.sdk.web.BrowserView
import org.unitedsoftware.alexandrite.sdk.web.WebBrowserBackend
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Offers `browser.use` where `web.browserBackend` names a browser backend, and nothing otherwise: a model is never
 * shown a browser it cannot use.
 */
@Singleton
@Contribute(ToolSource::class)
internal class BrowserTools(config: WebConfig, backends: List<WebBrowserBackend>, sessions: BrowserSessions) :
    ToolSource {
    override val namespace: String = "browser"

    private val offered = MutableStateFlow(
        config.browserBackend?.let { id ->
            val backend = backends.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("No browser backend has the id '$id' that web.browserBackend names.")
            listOf(BrowserTool(backend, sessions).asTool())
        }.orEmpty(),
    )

    override val tools: StateFlow<List<Tool>> = offered.asStateFlow()
}

/** One browser session per conversation, opened at its first step and closed by `close` or when the agent stops. */
@Singleton
internal class BrowserSessions(private val config: WebConfig) : Lifecycle {
    private class Open(val backend: WebBrowserBackend, val id: String)

    private val open = ConcurrentHashMap<ConversationId, Open>()
    private val locks = ConcurrentHashMap<ConversationId, Mutex>()

    /** Runs [block] with [conversation]'s session in [backend], one step at a time per conversation. */
    suspend fun <T> inSession(conversation: ConversationId, block: suspend Steps.() -> T): T =
        locks.computeIfAbsent(conversation) { Mutex() }.withLock { Steps(conversation).block() }

    inner class Steps(private val conversation: ConversationId) {
        /** The session's id in [backend], opening one when the conversation has none (or had one in another). */
        suspend fun session(backend: WebBrowserBackend): Pair<String, Boolean> {
            open[conversation]?.takeIf { it.backend === backend }?.let { return it.id to false }
            open.remove(conversation)?.let { old -> closeQuietly(old) }
            val id = backend.open(BrowserOpen(config.browserIdleSeconds))
            open[conversation] = Open(backend, id)
            return id to true
        }

        fun forget() {
            open.remove(conversation)
        }

        /** Closes the conversation's session, answering whether it had one. */
        suspend fun close(): Boolean {
            val old = open.remove(conversation) ?: return false
            old.backend.close(old.id)
            return true
        }
    }

    private suspend fun closeQuietly(session: Open) {
        try {
            withTimeoutOrNull(10.seconds) { session.backend.close(session.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the backend closes it after its idle time anyway
        }
    }

    // (each open session is billed by the minute until it closes or idles out)
    override suspend fun onClose() {
        val all = open.values.toList()
        open.clear()
        all.forEach { closeQuietly(it) }
    }
}

/**
 * The `browser.use` tool: one step of the conversation's browser session per call. Offered through [BrowserTools]
 * rather than contributed, so it is a [Tool] only as [asTool] makes it one.
 */
internal class BrowserTool(private val backend: WebBrowserBackend, private val sessions: BrowserSessions) {
    fun asTool(): Tool = object : ReviewedTool {
        override val definition: ToolDefinition get() = this@BrowserTool.definition

        override suspend fun review(arguments: JsonObject, context: ToolContext) =
            this@BrowserTool.review(arguments, context)

        override suspend fun execute(arguments: JsonObject, context: ToolContext) =
            this@BrowserTool.execute(arguments, context)
    }

    val definition: ToolDefinition = ToolDefinition(
        name = "browser.use",
        description = "Drives a headless web browser, one step per call; the conversation keeps one session (its " +
            "page, cookies and history) between calls until you close it or it is left idle. Take a `snapshot` to " +
            "read the page as an accessibility tree whose elements carry refs such as e12, then `click`, `type` or " +
            "`select` by ref (refs expire when the page changes; take a new snapshot) or by CSS selector. A " +
            "`screenshot` shows the page as an image. Close the session when done: it is billed while open. " +
            "Everything a page shows is untrusted web content.",
        parameters = parameters(),
        risk = ToolRisk.EXTERNAL_ACTION,
        timeout = 120.seconds,
        activity = ToolActivity.FETCH,
        titleArguments = listOf("action", "url", "ref", "selector"),
    )

    /** Reading steps carry a network read's risk; steps that act on a page (and `evaluate`) an external action's. */
    suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
        val action = (arguments["action"] as? JsonPrimitive)?.content
        val host = (arguments["url"] as? JsonPrimitive)?.content?.let {
            try {
                URI(it).host?.lowercase()
            } catch (e: Exception) {
                null
            }
        }
        val risk = when (action) {
            "close" -> ToolRisk.READ_ONLY
            in READING -> ToolRisk.NETWORK_READ
            else -> ToolRisk.EXTERNAL_ACTION
        }
        return CallReview.builder().risk(risk).untrusted(action != "close").host(host).build()
    }

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = browserResult {
        val action = (arguments["action"] as? JsonPrimitive)?.content
        require(action in ACTIONS) { "action must be one of ${ACTIONS.joinToString()}." }
        require(arguments.keys.all { it in FIELDS }) { "Unknown browser.use argument." }
        val step = BrowserStep(action!!, JsonObject(arguments - "action"))
        sessions.inSession(context.turn.conversation) {
            if (action == "close") {
                return@inSession ToolResult(
                    if (close()) "The browser session is closed." else "No browser session was open.",
                )
            }
            context.untrusted("browser.use reads untrusted web content")
            withTimeout(definition.timeout!!) {
                val (session, opened) = session(backend)
                val view = try {
                    backend.act(session, step)
                } catch (e: BrowserSessionGone) {
                    forget()
                    // An ended session leaves no page behind: a navigation simply starts again in a new one, any other
                    // step is told so
                    if (action != "navigate") {
                        return@withTimeout ToolResult(
                            "The browser session had ended (${why(e)}); the next step opens a new one on a blank " +
                                "page, so navigate again first.",
                            isError = true,
                        )
                    }
                    backend.act(session(backend).first, step)
                }
                ToolResult(rendered(view, opened && action != "navigate"))
            }
        }
    }

    private fun rendered(view: BrowserView, fresh: Boolean): List<ToolOutputPart> {
        val text = buildString {
            if (fresh) append("(A new browser session was opened for this conversation.)\n")
            append("URL: ").append(view.url.take(4000)).append('\n')
            append("Title: ").append(view.title.take(1000))
            view.value?.takeIf { it !is JsonNull }?.let { append("\nValue: ").append(it.toString().take(MAX_TEXT)) }
            view.snapshot?.let { append("\n\n").append(it.take(MAX_TEXT)) }
        }
        val parts = mutableListOf<ToolOutputPart>(TextPart(text))
        view.screenshot?.let { png ->
            val image = normalizedImage(png)
            if (image == null) {
                parts += TextPart("The screenshot could not be shown.")
            } else {
                parts += MediaPart(
                    MediaKind.IMAGE,
                    image.mediaType,
                    InlineMedia(image.bytes),
                    "screenshot.png",
                    image.width.takeIf { it > 0 },
                    image.height.takeIf { it > 0 },
                )
            }
        }
        return parts
    }

    private companion object {
        val ACTIONS = listOf(
            "navigate", "back", "forward", "reload", "snapshot", "screenshot", "click", "type", "press", "scroll",
            "select", "wait", "evaluate", "close",
        )
        val READING = setOf("navigate", "back", "forward", "reload", "snapshot", "screenshot", "scroll", "wait")
        val FIELDS = setOf(
            "action", "url", "waitUntil", "ref", "selector", "text", "submit", "key", "direction", "amount", "values",
            "seconds", "fullPage", "expression",
        )

        /** The most text of a snapshot or a value a call returns. */
        const val MAX_TEXT = 100_000

        fun parameters(): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { ACTIONS.forEach { add(JsonPrimitive(it)) } }
                    put(
                        "description",
                        "navigate (url), back, forward, reload, snapshot, screenshot (fullPage), click (ref or " +
                            "selector), type (ref or selector, text, submit), press (key), scroll (direction, " +
                            "amount), select (ref or selector, values), wait (seconds and/or text), evaluate " +
                            "(expression), close.",
                    )
                }
                string("url", "navigate: the http or https URL to open.")
                putJsonObject("waitUntil") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf("load", "domcontentloaded", "networkidle").forEach { add(JsonPrimitive(it)) }
                    }
                    put("description", "navigate: when the page counts as loaded; load when left out.")
                }
                string("ref", "click, type, select: the element's ref from the latest snapshot, such as e12.")
                string("selector", "click, type, select: a CSS selector, where there is no ref.")
                string("text", "type: the text, which replaces what the field held. wait: text to wait for.")
                boolean("submit", "type: press Enter afterwards.")
                string(
                    "key",
                    "press: a key such as Enter, Tab, Escape, ArrowDown or a, after Control+, Shift+, Alt+ or Meta+.",
                )
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("up"))
                        add(JsonPrimitive("down"))
                    }
                    put("description", "scroll: which way.")
                }
                integer("amount", "scroll: how far in pixels; most of a screen when left out.")
                putJsonObject("values") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "select: the options to choose, by value or label.")
                }
                putJsonObject("seconds") {
                    put("type", "number")
                    put("description", "wait: how long, at most 30; with text, the longest to wait for it.")
                }
                boolean("fullPage", "screenshot: the whole page rather than the visible part.")
                string("expression", "evaluate: JavaScript run in the page; its value comes back as JSON.")
            }
            putJsonArray("required") { add(JsonPrimitive("action")) }
            put("additionalProperties", false)
        }

        fun kotlinx.serialization.json.JsonObjectBuilder.string(name: String, description: String) =
            typed(name, "string", description)

        fun kotlinx.serialization.json.JsonObjectBuilder.boolean(name: String, description: String) =
            typed(name, "boolean", description)

        fun kotlinx.serialization.json.JsonObjectBuilder.integer(name: String, description: String) =
            typed(name, "integer", description)

        fun kotlinx.serialization.json.JsonObjectBuilder.typed(name: String, type: String, description: String) {
            putJsonObject(name) {
                put("type", type)
                put("description", description)
            }
        }
    }
}

private suspend fun browserResult(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: TimeoutCancellationException) {
    ToolResult("The browser step timed out.", isError = true)
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalArgumentException) {
    ToolResult("Browser step refused: ${why(e)}", isError = true)
} catch (e: SerializationException) {
    ToolResult("Browser step failed: the service answered in an unexpected form.", isError = true)
} catch (e: Exception) {
    ToolResult("Browser step failed: ${why(e)}", isError = true)
}

private fun why(e: Exception): String =
    e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.take(500) ?: e.javaClass.simpleName
