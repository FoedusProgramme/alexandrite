package org.unitedsoftware.alexandrite.tools.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.internal.media.NormalizedImage
import org.unitedsoftware.alexandrite.internal.media.imageDimensions
import org.unitedsoftware.alexandrite.internal.media.normalizedImage
import org.unitedsoftware.alexandrite.sdk.browse.BrowseBackend
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
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** What a tool built on [BrowseTool] says and decides for its surface. */
internal class BrowseProfile(
    val name: String,
    /** What the tool drives, as in "browser" for "The browser session is closed." */
    val subject: String,
    /** The description's opening, what the tool drives and how its sessions last. */
    val intro: String,
    /** The description's closing. */
    val outro: String,
    /** What the model sees, as in "page" for "read the page". */
    val screen: String,
    val activity: ToolActivity,
    /** Why a step taints the conversation. */
    val untrusted: String,
    /** The risk of a call by its action, `close` aside. */
    val risk: (String) -> ToolRisk,
    /** The host a call reaches. */
    val host: (JsonObject) -> String? = { null },
    /** Actions that need nothing of an earlier step, which a session found ended simply runs again in a new one. */
    val standalone: Set<String> = emptySet(),
    /** What any other action is told when it finds the session ended. */
    val ended: String,
)

/**
 * A tool driving the conversation's session in [backend] one step per call, offering only the steps, views and
 * targets the backend has. A [ToolSource][org.unitedsoftware.alexandrite.sdk.tool.ToolSource] offers it, so it is a
 * [Tool] only as [asTool] makes it one.
 */
internal class BrowseTool(
    private val backend: BrowseBackend,
    private val sessions: BrowseSessions,
    private val profile: BrowseProfile,
) {
    fun asTool(): Tool = object : ReviewedTool {
        override val definition: ToolDefinition get() = this@BrowseTool.definition

        override suspend fun review(arguments: JsonObject, context: ToolContext) =
            this@BrowseTool.review(arguments, context)

        override suspend fun execute(arguments: JsonObject, context: ToolContext) =
            this@BrowseTool.execute(arguments, context)
    }

    private val abilities = backend.abilities
    private val targets = abilities.targets
    private val subject = profile.subject.replaceFirstChar(Char::uppercase)

    private val actions: List<String> = buildList {
        for (kind in BrowseStep.Kind.entries.filter { it in abilities.steps }) {
            if (kind == BrowseStep.Kind.LOOK) {
                if (BrowseView.LAYOUT in abilities.views) add("snapshot")
                if (BrowseView.IMAGE in abilities.views) add("screenshot")
            } else {
                add(kind.name.lowercase())
            }
        }
        add("close")
    }

    val definition: ToolDefinition = ToolDefinition(
        name = profile.name,
        description = description(),
        parameters = parameters(),
        risk = ToolRisk.EXTERNAL_ACTION,
        timeout = 120.seconds,
        activity = profile.activity,
        titleArguments = listOf("action", "url", "ref", "selector"),
    )

    suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
        val action = (arguments["action"] as? JsonPrimitive)?.content
        val risk = if (action == "close") ToolRisk.READ_ONLY else profile.risk(action.orEmpty())
        return CallReview.builder().risk(risk).untrusted(action != "close").host(profile.host(arguments)).build()
    }

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = browseResult {
        val action = (arguments["action"] as? JsonPrimitive)?.content
        require(action in actions) { "action must be one of ${actions.joinToString()}." }
        require(arguments.keys.all { it in FIELDS }) { "Unknown ${profile.name} argument." }
        sessions.inSession(context.turn.conversation) {
            if (action == "close") {
                return@inSession ToolResult(
                    if (close()) {
                        "The ${profile.subject} session is closed."
                    } else {
                        "No ${profile.subject} session was open."
                    },
                )
            }
            context.untrusted(profile.untrusted)
            val step = step(action!!, StepArguments(arguments), scale).also(backend::check)
            withTimeout(definition.timeout!!) {
                val (session, opened) = session(backend)
                val report = try {
                    backend.act(session, step)
                } catch (e: BrowseSessionGone) {
                    forget()
                    if (action !in profile.standalone) {
                        return@withTimeout ToolResult(
                            "The ${profile.subject} session had ended (${why(e)}); ${profile.ended}",
                            isError = true,
                        )
                    }
                    backend.act(session(backend).first, step)
                }
                val image = report.image?.let { normalizedImage(it.toByteArray()) }
                if (image != null && !(step is BrowseStep.Look && step.fullPage) && image.width > 0) {
                    imageDimensions(report.image!!.toByteArray())?.let { (width, _) ->
                        scaled(width.toDouble() / image.width)
                    }
                }
                ToolResult(rendered(report, image, opened && action !in profile.standalone))
            }
        }
    }

    private fun step(action: String, arguments: StepArguments, scale: Double): BrowseStep = with(arguments) {
        val target = "ref, selector or x and y"
        when (action) {
            "snapshot" -> BrowseStep.Look(setOf(BrowseView.LAYOUT))

            "screenshot" -> BrowseStep.Look(setOf(BrowseView.IMAGE), boolean("fullPage") ?: false)

            "click" -> BrowseStep.Click(
                required(target, target("", scale)),
                enum("button", abilities.buttons.associateBy { it.name.lowercase() }) ?: MouseButton.LEFT,
                (int("count") ?: 1).also {
                    require(it in 1..abilities.clicks) { "count is from 1 to ${abilities.clicks}." }
                },
            )

            "hover" -> BrowseStep.Hover(required(target, target("", scale)))

            "drag" -> BrowseStep.Drag(
                required(target, target("", scale)),
                required("toRef, toSelector or toX and toY", target("to", scale)),
            )

            "type" -> BrowseStep.Type(target("", scale), required("text", string("text")), boolean("submit") ?: false)

            "press" -> BrowseStep.Press(required("key", string("key")))

            "scroll" -> BrowseStep.Scroll(
                required("direction", enum("direction", abilities.scrolls.associateBy { it.name.lowercase() })),
                int("amount"),
                target("", scale),
            )

            "wait" -> BrowseStep.Wait(number("seconds"), string("text"))

            "navigate" -> WebStep.Navigate(
                required("url", string("url")),
                enum("waitUntil", PAGE_LOADS) ?: PageLoad.LOAD,
            )

            "back" -> WebStep.Back

            "forward" -> WebStep.Forward

            "reload" -> WebStep.Reload

            "select" -> WebStep.Select(required(target, target("", scale)), required("values", strings("values")))

            "evaluate" -> WebStep.Evaluate(required("expression", string("expression")))

            else -> throw IllegalArgumentException("action must be one of ${actions.joinToString()}.")
        }
    }

    /** The target [prefix]`ref`, [prefix]`selector` or [prefix]`x` and `y` name, a point scaled by [scale]. */
    private fun StepArguments.target(prefix: String, scale: Double): BrowseTarget? {
        val ref = string(prefixed(prefix, "ref"))
        val selector = string(prefixed(prefix, "selector"))
        val x = int(prefixed(prefix, "x"))
        val y = int(prefixed(prefix, "y"))
        val names = "${prefixed(prefix, "ref")}, ${prefixed(prefix, "selector")} or ${prefixed(prefix, "x")} and " +
            prefixed(prefix, "y")
        require((x == null) == (y == null)) { "${prefixed(prefix, "x")} and ${prefixed(prefix, "y")} come together." }
        val target = listOfNotNull(
            ref?.let { BrowseTarget.Ref(it) },
            selector?.let { BrowseTarget.Selector(it) },
            x?.let { BrowseTarget.Point((it * scale).roundToInt(), (y!! * scale).roundToInt()) },
        )
        require(target.size <= 1) { "Name one of $names." }
        return target.firstOrNull()?.also {
            require(it.kind in targets) { "This ${profile.subject} takes no ${it.kind.name.lowercase()} targets." }
        }
    }

    private fun rendered(report: BrowseReport, image: NormalizedImage?, fresh: Boolean): List<ToolOutputPart> {
        val text = buildString {
            if (fresh) append("(A new ${profile.subject} session was opened for this conversation.)\n")
            report.url?.let { append("URL: ").append(it.take(4000)).append('\n') }
            report.title?.let { append("Title: ").append(it.take(1000)) }
            report.value?.takeIf { it !is JsonNull }?.let { append("\nValue: ").append(it.toString().take(MAX_TEXT)) }
            report.layout?.let { append("\n\n").append(it.take(MAX_TEXT)) }
        }.trim()
        val parts = mutableListOf<ToolOutputPart>(TextPart(text.ifEmpty { "Done." }))
        if (report.image != null) {
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

    private fun description(): String = buildString {
        val screen = profile.screen
        append(profile.intro)
        if (BrowseView.LAYOUT in abilities.views) {
            append(
                " Take a `snapshot` to read the $screen as an accessibility tree whose elements carry refs such as " +
                    "e12 (refs expire when the $screen changes; take a new snapshot).",
            )
        }
        if (BrowseView.IMAGE in abilities.views) append(" A `screenshot` shows the $screen as an image.")
        append(" Steps that act on an element name it by ").append(targetNames()).append('.')
        backend.sessionNote?.let { append(' ').append(it) }
        append(' ').append(profile.outro)
    }

    private fun targetNames(): String = buildList {
        if (BrowseTarget.Kind.REF in targets) add("ref")
        if (BrowseTarget.Kind.SELECTOR in targets) add("CSS selector")
        if (BrowseTarget.Kind.POINT in targets) {
            add("x and y, in pixels of the latest screenshot of the visible area as you saw it")
        }
    }.joinToString(" or ")

    private fun parameters(): JsonObject = buildJsonObject {
        val offered = actions.toSet()
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") { actions.forEach { add(JsonPrimitive(it)) } }
                put(
                    "description",
                    actions.joinToString(", ") { action -> ACTION_HINTS[action]?.let { "$action ($it)" } ?: action } +
                        ".",
                )
            }
            if ("navigate" in offered) {
                string("url", "navigate: the http or https URL to open.")
                enum("waitUntil", PAGE_LOADS.keys, "navigate: when the page counts as loaded; load when left out.")
            }
            val aimed = listOf("click", "hover", "drag", "type", "scroll", "select").filter { it in offered }
            if (aimed.isNotEmpty()) targetFields("", aimed.joinToString(", "))
            if ("drag" in offered) targetFields("to", "drag: where to drop")
            if ("click" in offered && abilities.buttons.size > 1) {
                enum("button", abilities.buttons.map { it.name.lowercase() }, "click: left when left out.")
            }
            if ("click" in offered && abilities.clicks > 1) {
                integer("count", "click: 2 for a double click, 3 for a triple; 1 when left out.")
            }
            if ("type" in offered || "wait" in offered) {
                string("text", "type: the text, which replaces what the field held. wait: text to wait for.")
            }
            if ("type" in offered) boolean("submit", "type: press Enter afterwards.")
            if ("press" in offered) {
                string(
                    "key",
                    "press: a key such as Enter, Tab, Escape, ArrowDown or a, after Control+, Shift+, Alt+ or Meta+.",
                )
            }
            if ("scroll" in offered) {
                enum("direction", abilities.scrolls.map { it.name.lowercase() }, "scroll: which way.")
                integer("amount", "scroll: how far in pixels; most of a screen when left out.")
            }
            if ("select" in offered) {
                putJsonObject("values") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "select: the options to choose, by value or label.")
                }
            }
            if ("wait" in offered) {
                putJsonObject("seconds") {
                    put("type", "number")
                    put("description", "wait: how long, at most 30; with text, the longest to wait for it.")
                }
            }
            if ("screenshot" in offered && backend.surface == BrowseSurface.WEB) {
                boolean("fullPage", "screenshot: the whole page rather than the visible part.")
            }
            if ("evaluate" in offered) {
                string("expression", "evaluate: JavaScript run in the page; its value comes back as JSON.")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")) }
        put("additionalProperties", false)
    }

    private fun JsonObjectBuilder.targetFields(prefix: String, steps: String) {
        if (BrowseTarget.Kind.REF in targets) {
            string(prefixed(prefix, "ref"), "$steps: the element's ref from the latest snapshot, such as e12.")
        }
        if (BrowseTarget.Kind.SELECTOR in targets) {
            string(prefixed(prefix, "selector"), "$steps: a CSS selector, where there is no ref.")
        }
        if (BrowseTarget.Kind.POINT in targets) {
            integer(
                prefixed(prefix, "x"),
                "$steps: pixels from the left of the latest screenshot of the visible area as you saw it.",
            )
            integer(
                prefixed(prefix, "y"),
                "$steps: pixels from the top of the latest screenshot of the visible area as you saw it.",
            )
        }
    }

    private suspend fun browseResult(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (e: TimeoutCancellationException) {
        ToolResult("The ${profile.subject} step timed out.", isError = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        ToolResult("$subject step refused: ${why(e)}", isError = true)
    } catch (e: SerializationException) {
        ToolResult("$subject step failed: the service answered in an unexpected form.", isError = true)
    } catch (e: Exception) {
        ToolResult("$subject step failed: ${why(e)}", isError = true)
    }

    private companion object {
        val FIELDS = setOf(
            "action", "url", "waitUntil", "ref", "selector", "x", "y", "button", "count", "toRef", "toSelector", "toX",
            "toY", "text", "submit", "key", "direction", "amount", "values", "seconds", "fullPage", "expression",
        )
        val ACTION_HINTS = mapOf(
            "navigate" to "url",
            "screenshot" to "fullPage",
            "click" to "a target",
            "hover" to "a target",
            "drag" to "a target, then where to drop it",
            "type" to "a target, text, submit",
            "press" to "key",
            "scroll" to "direction, amount",
            "select" to "a target, values",
            "wait" to "seconds and/or text",
            "evaluate" to "expression",
        )
        val PAGE_LOADS = mapOf(
            "load" to PageLoad.LOAD,
            "domcontentloaded" to PageLoad.DOM_CONTENT_LOADED,
            "networkidle" to PageLoad.NETWORK_IDLE,
        )

        /** The most text of a layout or a value a call returns. */
        const val MAX_TEXT = 100_000

        fun prefixed(prefix: String, field: String): String =
            if (prefix.isEmpty()) field else prefix + field.replaceFirstChar(Char::uppercase)

        fun why(e: Exception): String =
            e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.take(500) ?: e.javaClass.simpleName

        fun JsonObjectBuilder.string(name: String, description: String) = typed(name, "string", description)

        fun JsonObjectBuilder.boolean(name: String, description: String) = typed(name, "boolean", description)

        fun JsonObjectBuilder.integer(name: String, description: String) = typed(name, "integer", description)

        fun JsonObjectBuilder.enum(name: String, values: Collection<String>, description: String) {
            putJsonObject(name) {
                put("type", "string")
                putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
                put("description", description)
            }
        }

        fun JsonObjectBuilder.typed(name: String, type: String, description: String) {
            putJsonObject(name) {
                put("type", type)
                put("description", description)
            }
        }
    }
}
