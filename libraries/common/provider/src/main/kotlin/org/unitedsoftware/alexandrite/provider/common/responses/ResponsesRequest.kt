package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.unitedsoftware.alexandrite.provider.common.IMAGE_TYPES
import org.unitedsoftware.alexandrite.provider.common.ToolCallIndex
import org.unitedsoftware.alexandrite.provider.common.callText
import org.unitedsoftware.alexandrite.provider.common.resultHeading
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
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

/**
 * The Responses body of [request] to [info]'s model, which keeps nothing on the backend: every request carries the
 * whole transcript as input items, its reasoning items encrypted, and its turn context in a user message after them.
 */
internal class ResponsesRequest(
    private val request: ModelRequest,
    private val info: ModelInfo,
    private val flavor: ResponsesFlavor,
    private val settings: ResponsesSettings,
) {
    /** What the request asks for that the body leaves out. */
    val warnings: MutableList<Warning> = mutableListOf()

    private val items = mutableListOf<Item>()

    private val toolCalls = ToolCallIndex(request)

    private val effort = request.options.reasoning?.takeIf { it in info.reasoningEfforts }

    val body: JsonObject = build()

    private fun build(): JsonObject {
        for (entry in request.history) {
            when (entry) {
                is UserEntry -> user(entry.parts.flatMap(::content))

                is SummaryEntry -> user(listOf(text(entry.text)))

                is AssistantEntry -> assistant(entry)

                is ToolResultEntry -> if (toolCalls.sendsResult(entry.callId)) {
                    items += Item.Plain(output(entry))
                } else {
                    user(resultText(entry))
                }
            }
        }
        if (request.turnContext.isNotEmpty()) {
            user(listOf(text(request.turnContext.joinToString(SEPARATOR) { it.text })))
        }
        return buildJsonObject {
            put("model", request.model.model)
            val instructions = request.instructions.filter { it.text.isNotEmpty() }.joinToString(SEPARATOR) { it.text }
            if (instructions.isNotEmpty()) put("instructions", instructions)
            put("input", JsonArray(items.map(Item::json)))
            tools()
            reasoning()
            options()
            request.cacheKey?.takeIf { settings.promptCacheKey }?.let { flavor.caching.key(it, this) }
            put("store", false)
            put("stream", true)
        }
    }

    private fun JsonObjectBuilder.tools() {
        if (request.tools.isEmpty()) return
        put("tools", JsonArray(request.tools.map(flavor.tools::definition)))
        flavor.tools.toolChoice(request, this)?.let(warnings::add)
        val parallel = request.options.parallelToolCalls ?: return
        if (parallel && !info.parallelToolCalls) {
            warnings += Warning(WarningKind.UNSUPPORTED_OPTION, "Model '${info.id}' calls no tools in parallel.")
        } else {
            put("parallel_tool_calls", parallel)
        }
    }

    private fun JsonObjectBuilder.reasoning() {
        val reasoning = flavor.reasoning
        val fields = buildJsonObject {
            request.options.reasoning?.let { reasoning.effort(it, info, this)?.let(warnings::add) }
            settings.reasoningSummary?.takeIf { reasoning.thinks(effort, info) }?.let { put("summary", it.id) }
        }
        if (!reasoning.reasons(info)) return
        if (fields.isNotEmpty()) put("reasoning", fields)
        putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
    }

    private fun JsonObjectBuilder.options() {
        val options = request.options
        maxOutputTokens()?.let { put("max_output_tokens", it) }
        val thinks = flavor.reasoning.thinks(effort, info)
        options.temperature?.let { temperature ->
            if (thinks) {
                warnings +=
                    Warning(WarningKind.UNSUPPORTED_OPTION, "Model '${info.id}' takes no temperature while it reasons.")
            } else {
                put("temperature", temperature)
            }
        }
        options.topP?.let { topP ->
            if (thinks) {
                warnings +=
                    Warning(WarningKind.UNSUPPORTED_OPTION, "Model '${info.id}' takes no top-p while it reasons.")
            } else {
                put("top_p", topP)
            }
        }
        if (options.stopSequences.isNotEmpty()) {
            warnings += Warning(WarningKind.UNSUPPORTED_OPTION, "The Responses API takes no stop sequences.")
        }
    }

    private fun maxOutputTokens(): Int? {
        val asked = request.options.maxOutputTokens ?: return null
        val limit = info.maxOutputTokens
        return when {
            limit != null && asked > limit -> {
                warnings += Warning(
                    WarningKind.UNSUPPORTED_OPTION,
                    "Model '${info.id}' writes at most $limit tokens, so the request asks for that many.",
                )
                limit
            }

            asked < MIN_OUTPUT_TOKENS -> {
                warnings += Warning(
                    WarningKind.UNSUPPORTED_OPTION,
                    "Model '${info.id}' writes at least $MIN_OUTPUT_TOKENS tokens, so the request asks for that many.",
                )
                MIN_OUTPUT_TOKENS
            }

            else -> asked
        }
    }

    private fun user(contents: List<JsonObject>) {
        if (contents.isEmpty()) return
        val last = items.lastOrNull()
        if (last is Item.User) last.contents += contents else items += Item.User(contents.toMutableList())
    }

    /** Adds the items of [entry], in the order of its parts. */
    private fun assistant(entry: AssistantEntry) {
        for (part in entry.parts) {
            when (part) {
                is TextPart -> assistantText(part.text)

                is ReasoningPart -> flavor.reasoning.replay(part, request, flavor.dialect)?.let {
                    items +=
                        Item.Plain(it)
                }

                is ToolCallPart -> if (toolCalls.sendsCall(part.id)) {
                    items += Item.Plain(call(part))
                } else {
                    assistantText(callText(part, flavor.tools.wireName(part.name)))
                }

                is OpaquePart -> if (part.dialect == flavor.dialect) items += Item.Plain(part.json)
            }
        }
    }

    private fun assistantText(text: String) {
        if (text.isEmpty()) return
        val last = items.lastOrNull()
        if (last is Item.Assistant) last.texts += text else items += Item.Assistant(mutableListOf(text))
    }

    private fun content(part: Part): List<JsonObject> = when (part) {
        is TextPart -> listOfNotNull(part.text.takeIf { it.isNotEmpty() }?.let(::text))
        is ContextPart -> listOfNotNull(part.text.takeIf { it.isNotEmpty() }?.let(::text))
        is MediaPart -> listOf(media(part))
        else -> emptyList()
    }

    private fun media(part: MediaPart): JsonObject {
        val source = part.source
        if (source is InlineMedia && part.kind in info.inputMedia) {
            val url = "data:${part.mediaType};base64,${Base64.getEncoder().encodeToString(source.bytes())}"
            when {
                part.kind == MediaKind.IMAGE && part.mediaType in IMAGE_TYPES -> return buildJsonObject {
                    put("type", "input_image")
                    put("image_url", url)
                    put("detail", "auto")
                }

                part.kind == MediaKind.FILE && part.mediaType == PDF -> return buildJsonObject {
                    put("type", "input_file")
                    put("filename", part.name ?: "document.pdf")
                    put("file_data", url)
                }
            }
        }
        warnings +=
            Warning(WarningKind.UNSUPPORTED_MEDIA, "Model '${info.id}' cannot take ${part.kind} ${part.mediaType}.")
        return text("[${part.kind} ${part.name ?: part.mediaType} not shown: the model cannot take it]")
    }

    private fun call(call: ToolCallPart): JsonObject = buildJsonObject {
        put("type", "function_call")
        put("call_id", flavor.tools.wireCallId(call.id))
        put("name", flavor.tools.wireName(call.name))
        put("arguments", call.arguments.ifEmpty { "{}" })
    }

    /** The `function_call_output` of [entry]: its text, or its content items where it holds media. */
    private fun output(entry: ToolResultEntry): JsonObject = buildJsonObject {
        put("type", "function_call_output")
        put("call_id", flavor.tools.wireCallId(entry.callId))
        val content = resultContent(entry)
        if (content.all { it["type"] == INPUT_TEXT }) {
            put("output", content.joinToString(SEPARATOR) { (it["text"] as JsonPrimitive).content })
        } else {
            put("output", JsonArray(content))
        }
    }

    /** The content that stands for the result [entry] in a request that offers no tools. */
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

    private fun text(text: String): JsonObject = buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }

    private sealed interface Item {
        fun json(): JsonElement

        class Plain(val json: JsonObject) : Item {
            override fun json(): JsonElement = json
        }

        /** A user message, which takes the contents of every user entry that follows it directly. */
        class User(val contents: MutableList<JsonObject>) : Item {
            override fun json(): JsonElement = buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", JsonArray(contents))
            }
        }

        class Assistant(val texts: MutableList<String>) : Item {
            override fun json(): JsonElement = buildJsonObject {
                put("type", "message")
                put("role", "assistant")
                put("content", texts.joinToString(SEPARATOR))
            }
        }
    }

    private companion object {
        const val SEPARATOR = "\n\n"
        const val MIN_OUTPUT_TOKENS = 16
        const val PDF = "application/pdf"
        val INPUT_TEXT = JsonPrimitive("input_text")
    }
}
