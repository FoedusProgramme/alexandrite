package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.IMAGE_TYPES
import org.unitedsoftware.alexandrite.provider.common.ToolCallIndex
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.callText
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.resultHeading
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.model.WarningKind
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.OpaquePart
import org.unitedsoftware.alexandrite.sdk.transcript.Part
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import java.util.Base64

/** The key of an assistant entry's provider data that holds the turn-scoped system message sent before it. */
internal const val KEPT_TURN_CONTEXT = "turnContext"

/**
 * The Messages body of [request] to [info]'s model, whose static prefix and persisted transcript end in cache
 * breakpoints. Turn context that [mode] renders TRANSIENT goes after everything persisted, or, while an endpoint that
 * keeps no turn context asks a model to think, to the end of the turn's opening user message in every round, which
 * leaves earlier turns' thinking out. KEPT_UNRENDERED goes into a turn-scoped system message after the last user
 * message, which the response keeps for later requests to repeat.
 */
internal class MessagesRequest(
    private val request: ModelRequest,
    private val info: ModelInfo,
    private val flavor: MessagesFlavor,
    private val settings: MessagesSettings,
    private val setting: TurnContextSetting,
    private val mode: (Trust) -> TurnContextMode,
) {
    /** What the request asks for that the body leaves out. */
    val warnings: MutableList<Warning> = mutableListOf()

    /** The beta features the request asks for. */
    val betas: MutableSet<String> = settings.betas.toMutableSet()

    /** The turn-scoped system message the request ends with, null when it ends with none. */
    var kept: JsonObject? = null
        private set

    private val messages = mutableListOf<Message>()

    private val breakpoint = flavor.caching.breakpoint.takeIf { settings.promptCaching }

    private val toolCalls = ToolCallIndex(request)

    private val effort = if (request.tools.isEmpty()) request.options.reasoning else flavor.tools.effort(request, info)

    private val sent = effort?.takeIf { it in info.reasoningEfforts }

    /** Whether requests keep no turn context, so a thinking turn keeps its own in place and no earlier thinking. */
    private val unkept = flavor.turnContext.prefixBound && !settings.turnScopedSystem &&
        setting == TurnContextSetting.TRANSIENT

    /** The user message before the current turn's first response, null before the turn has one. */
    private var opening: Message.Turn? = null

    /** The user message before the first response of the last earlier turn, null when there is none. */
    private var previousOpening: Message.Turn? = null

    val body: JsonObject = build()

    private fun build(): JsonObject {
        val tools = request.tools.map { tool ->
            buildJsonObject {
                put("name", flavor.tools.wireName(tool.name))
                put("description", tool.description)
                put("input_schema", tool.parameters)
            }
        }.toMutableList()
        val sections = request.instructions.filter { it.text.isNotEmpty() }
        val stable = sections.takeWhile { it.stable }.size
        val system = sections.mapIndexed { index, section ->
            val block = text(section.text)
            if (index == stable - 1) marked(block) else block
        }
        if (stable == 0 && tools.isNotEmpty()) tools[tools.lastIndex] = marked(tools.last())
        history()
        markPersisted()
        turnContext()
        return buildJsonObject {
            put("model", request.model.model)
            put("max_tokens", maxTokens())
            if (system.isNotEmpty()) put("system", JsonArray(system))
            put("messages", JsonArray(messages.map(Message::json)))
            if (tools.isNotEmpty()) put("tools", JsonArray(tools))
            options()
            put("stream", true)
        }
    }

    private fun history() {
        var previousTurn: TurnId? = null
        for ((index, entry) in request.history.withIndex()) {
            when (entry) {
                is UserEntry -> user(entry.parts.flatMap(::content))

                is SummaryEntry -> user(listOf(text(entry.text)))

                is ToolResultEntry ->
                    user(if (toolCalls.sendsResult(entry.callId)) listOf(toolResult(entry)) else resultText(entry))

                is AssistantEntry -> {
                    val current = entry.record?.let { it.turn == request.ids.turn } ?: (index >= request.turnStart)
                    val before = (messages.lastOrNull() as? Message.Turn)?.takeIf { it.role == USER }
                    if (!assistant(entry, current) || before == null) continue
                    when {
                        current -> if (opening == null) opening = before
                        previousOpening == null || entry.record?.turn != previousTurn -> previousOpening = before
                    }
                    if (!current) previousTurn = entry.record?.turn
                }
            }
        }
    }

    private fun turnContext() {
        val byMode = request.turnContext.groupBy { mode(it.trust) }
        for ((mode, items) in byMode) {
            if (mode == TurnContextMode.TRANSIENT || mode == TurnContextMode.KEPT_UNRENDERED) continue
            for (item in items) {
                warnings += Warning(
                    WarningKind.UNSUPPORTED_OPTION,
                    "The endpoint renders no turn context of trust ${item.trust} for model '${info.id}', so the " +
                        "request leaves '${item.source}' out.",
                )
            }
        }
        byMode[TurnContextMode.TRANSIENT]?.let { items ->
            val block = text(items.joinToString(SEPARATOR) { it.text })
            val pinned = opening?.takeIf { unkept && flavor.reasoning.thinks(sent, info) }
            if (pinned != null) pinned.blocks += block else user(listOf(block))
        }
        val scoped = byMode[TurnContextMode.KEPT_UNRENDERED] ?: return
        val last = messages.lastOrNull()
        if (last !is Message.Turn || last.role != USER) {
            warnings += Warning(
                WarningKind.UNSUPPORTED_OPTION,
                "The request ends with no user message, so it leaves out its turn context " +
                    scoped.joinToString { "'${it.source}'" } + ".",
            )
            return
        }
        val message = flavor.turnContext.message(scoped.joinToString(SEPARATOR) { it.text })
        messages += Message.System(message)
        kept = message
        flavor.turnContext.beta?.let(betas::add)
    }

    private fun JsonObjectBuilder.options() {
        effort?.let { flavor.reasoning.request(it, info, this)?.let(warnings::add) }
        if (request.tools.isNotEmpty()) {
            warnings += flavor.tools.toolChoice(request, info, flavor.reasoning.thinks(sent, info), this)
        }
        val thinking = sent != null && sent != ReasoningEffort.NONE
        val options = request.options
        options.temperature?.let { temperature ->
            if (thinking) {
                warnings +=
                    Warning(WarningKind.UNSUPPORTED_OPTION, "Model '${info.id}' takes no temperature while it thinks.")
            } else {
                put("temperature", temperature)
            }
        }
        options.topP?.let { topP ->
            if (thinking && topP < MIN_THINKING_TOP_P) {
                warnings +=
                    Warning(
                        WarningKind.UNSUPPORTED_OPTION,
                        "Model '${info.id}' takes no top-p below 0.95 while it thinks.",
                    )
            } else {
                put("top_p", topP)
            }
        }
        if (options.stopSequences.isNotEmpty()) {
            putJsonArray("stop_sequences") { options.stopSequences.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private fun maxTokens(): Int {
        val limit = info.maxOutputTokens
        val asked = request.options.maxOutputTokens ?: return limit ?: DEFAULT_MAX_TOKENS
        if (limit == null || asked <= limit) return asked
        warnings += Warning(
            WarningKind.UNSUPPORTED_OPTION,
            "Model '${info.id}' writes at most $limit tokens, so the request asks for that many.",
        )
        return limit
    }

    private fun user(blocks: List<JsonObject>) {
        if (blocks.isEmpty()) return
        val last = messages.lastOrNull()
        if (last is Message.Turn && last.role == USER) {
            last.blocks += blocks
        } else {
            messages += Message.Turn(USER, blocks.toMutableList())
        }
    }

    /** Adds [entry], from the [current] turn or an earlier one, and returns whether it made a message of its own. */
    private fun assistant(entry: AssistantEntry, current: Boolean): Boolean {
        val blocks = entry.parts.mapNotNull { part ->
            when (part) {
                is TextPart -> part.text.takeIf { it.isNotEmpty() }?.let(::text)
                is ReasoningPart -> flavor.reasoning.replay(part, request, flavor.dialect).takeIf { current || !unkept }
                is ToolCallPart -> if (toolCalls.sendsCall(part.id)) toolUse(part) else callBlock(part)
                is OpaquePart -> part.json.takeIf { part.dialect == flavor.dialect }
                else -> null
            }
        }
        if (blocks.isEmpty()) return false
        val last = messages.lastOrNull()
        if (last is Message.Turn && last.role == ASSISTANT) {
            last.blocks += blocks
            return false
        }
        keptBefore(entry)?.takeIf { last is Message.Turn }?.let { kept ->
            messages += Message.System(kept)
            flavor.turnContext.beta?.let(betas::add)
        }
        messages += Message.Turn(ASSISTANT, blocks.toMutableList())
        return true
    }

    /** The turn-scoped system message that went out before [entry], null when none did or none may go out now. */
    private fun keptBefore(entry: AssistantEntry): JsonObject? {
        if (!settings.turnScopedSystem) return null
        return entry.providerData.entries[flavor.dialect]?.obj(KEPT_TURN_CONTEXT)
    }

    private fun content(part: Part): List<JsonObject> = when (part) {
        is TextPart -> listOfNotNull(part.text.takeIf { it.isNotEmpty() }?.let(::text))
        is ContextPart -> listOfNotNull(part.text.takeIf { it.isNotEmpty() }?.let(::text))
        is MediaPart -> listOf(media(part))
        else -> emptyList()
    }

    private fun media(part: MediaPart): JsonObject {
        val source = part.source
        if (part.kind == MediaKind.IMAGE && MediaKind.IMAGE in info.inputMedia && source is InlineMedia &&
            part.mediaType in IMAGE_TYPES
        ) {
            return buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", part.mediaType)
                    put("data", Base64.getEncoder().encodeToString(source.bytes()))
                }
            }
        }
        warnings +=
            Warning(WarningKind.UNSUPPORTED_MEDIA, "Model '${info.id}' cannot take ${part.kind} ${part.mediaType}.")
        return text("[${part.kind} ${part.name ?: part.mediaType} not shown: the model cannot take it]")
    }

    private fun toolUse(call: ToolCallPart): JsonObject = buildJsonObject {
        put("type", "tool_use")
        put("id", flavor.tools.wireCallId(call.id))
        put("name", flavor.tools.wireName(call.name))
        put("input", call.parseArguments() ?: JsonObject(emptyMap()))
    }

    private fun callBlock(call: ToolCallPart): JsonObject = text(callText(call, flavor.tools.wireName(call.name)))

    private fun toolResult(entry: ToolResultEntry): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", flavor.tools.wireCallId(entry.callId))
        val content = resultContent(entry)
        if (content.isNotEmpty()) put("content", JsonArray(content))
        if (entry.outcome.isError) put("is_error", true)
    }

    /** The blocks that stand for the result [entry] in a request that offers no tools. */
    private fun resultText(entry: ToolResultEntry): List<JsonObject> =
        listOf(text(resultHeading(flavor.tools.wireName(entry.toolName), entry.outcome.isError))) +
            resultContent(entry)

    private fun resultContent(entry: ToolResultEntry): List<JsonObject> = entry.content.mapNotNull { part ->
        when (part) {
            is TextPart -> part.text.takeIf { it.isNotEmpty() }?.let(::text)
            is MediaPart -> media(part)
            else -> null
        }
    }

    /**
     * Marks the last block of the persisted transcript that may carry a breakpoint and, where no request keeps turn
     * context, the ends of the opening user messages of this turn and the last earlier one, which stay cached when
     * what follows them changes.
     */
    private fun markPersisted() {
        messages.asReversed().firstOrNull { message -> message is Message.Turn && mark(message) }
        if (unkept) listOfNotNull(previousOpening, opening).forEach(::mark)
    }

    /** Marks the last block of [message] that may carry a breakpoint, and returns whether it has one. */
    private fun mark(message: Message.Turn): Boolean {
        val blocks = message.blocks
        val index = blocks.indexOfLast { it.string("type") in CACHEABLE }
        if (index < 0) return false
        if (blocks[index]["cache_control"] == null) blocks[index] = marked(blocks[index])
        return true
    }

    private fun marked(block: JsonObject): JsonObject =
        breakpoint?.let { JsonObject(block + ("cache_control" to it)) } ?: block

    private fun text(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private sealed interface Message {
        fun json(): JsonElement

        class Turn(val role: String, val blocks: MutableList<JsonObject>) : Message {
            override fun json(): JsonElement = buildJsonObject {
                put("role", role)
                put("content", JsonArray(blocks))
            }
        }

        class System(val json: JsonObject) : Message {
            override fun json(): JsonElement = json
        }
    }

    private companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val SEPARATOR = "\n\n"
        const val DEFAULT_MAX_TOKENS = 4096
        const val MIN_THINKING_TOP_P = 0.95
        val CACHEABLE = setOf("text", "image", "document", "tool_use", "tool_result")
    }
}
