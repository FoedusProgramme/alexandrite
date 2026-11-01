package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.model.WarningKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry

/** The form a wire API takes of a stored value: the value where it matches [grammar], else [MAPPED] and its hash. */
internal class WireForm(private val grammar: Regex) {
    fun of(value: String): String = if (grammar.matches(value) && !value.startsWith(MAPPED)) value else mapped(value)
}

/** Ids of letters, digits, `_` and `-`, as the Messages API takes them. */
internal val MESSAGES_CALL_IDS: WireForm = WireForm(Regex("[A-Za-z0-9_-]{1,64}"))

/** Ids of 40 printable ASCII characters at most, as Chat Completions takes them. */
internal val CHAT_CALL_IDS: WireForm = WireForm(Regex("[!-~]{1,40}"))

/** Ids of 64 printable ASCII characters at most, as the Responses API takes them. */
internal val RESPONSES_CALL_IDS: WireForm = WireForm(Regex("[!-~]{1,64}"))

private val TOOL_NAMES = WireForm(Regex("[A-Za-z0-9_-]{1,64}"))

/** The wire form of the tool name [name]: [ToolNames.wire] of a name of its grammar, a form of [TOOL_NAMES] else. */
internal fun wireToolName(name: String): String = when {
    ToolNames.PATTERN.matches(name) -> ToolNames.wire(name)
    ToolNames.fromWire(name) == null -> TOOL_NAMES.of(name)
    else -> mapped(name)
}

/** Which tool calls and results of [request]'s history go on the wire as such. */
internal class ToolCallIndex(request: ModelRequest) {
    private val offersTools = request.tools.isNotEmpty()

    private val answered = request.history.filterIsInstance<ToolResultEntry>().mapTo(HashSet()) { it.callId }

    private val called = request.history.filterIsInstance<AssistantEntry>()
        .flatMapTo(HashSet()) { entry -> entry.parts.filterIsInstance<ToolCallPart>().map { it.id } }

    /** Whether the call [id] goes as a call: the request offers tools and the history answers it. */
    fun sendsCall(id: ToolCallId): Boolean = offersTools && id in answered

    /** Whether the result of the call [id] goes as a result: the request offers tools and the history made the call. */
    fun sendsResult(id: ToolCallId): Boolean = offersTools && id in called
}

/**
 * Adds the `tool_choice` field that asks for [choice] to this body, or returns why it adds none.
 *
 * [named] fills the object that names the tool of a [ToolChoice.Named].
 */
internal fun JsonObjectBuilder.putToolChoice(
    choice: ToolChoice,
    named: JsonObjectBuilder.(ToolChoice.Named) -> Unit,
): Warning? {
    when (choice) {
        ToolChoice.Auto -> Unit

        ToolChoice.None -> put("tool_choice", "none")

        ToolChoice.Required -> put("tool_choice", "required")

        is ToolChoice.Named -> putJsonObject("tool_choice") {
            put("type", "function")
            named(choice)
        }

        else -> return Warning(
            WarningKind.UNSUPPORTED_OPTION,
            "The tool choice $choice is unknown, so the model decides.",
        )
    }
    return null
}

/** The text that stands for [call] of the tool [name] in a request that offers no tools. */
internal fun callText(call: ToolCallPart, name: String): String =
    "[Called tool $name with ${call.arguments.ifEmpty { "{}" }}]"

/** The heading of the text that stands for a result of the tool [name] in a request that offers no tools. */
internal fun resultHeading(name: String, failed: Boolean): String =
    "[Result of tool $name${if (failed) ", which failed" else ""}]"

/** The image types that the Messages and Responses APIs take. */
internal val IMAGE_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

/** The warning that [info]'s model does not accept [effort], so the request leaves the effort out. */
internal fun unsupportedEffort(effort: ReasoningEffort, info: ModelInfo): Warning = Warning(
    WarningKind.UNSUPPORTED_OPTION,
    "Model '${info.id}' does not accept the reasoning effort '$effort', so the request leaves the effort out.",
)

/** Starts with an uppercase letter, which no wire form of a tool name of [ToolNames.PATTERN] does. */
private const val MAPPED = "X"

private fun mapped(value: String): String = MAPPED + sha256Hex(value).take(HASH_DIGITS)

private const val HASH_DIGITS = 32
