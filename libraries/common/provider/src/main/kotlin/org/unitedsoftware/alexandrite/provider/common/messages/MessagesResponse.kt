package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.provider.common.ErrorDetail
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.modelException
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.streamError
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantPart
import org.unitedsoftware.alexandrite.sdk.transcript.OpaquePart
import org.unitedsoftware.alexandrite.sdk.transcript.ProviderData
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart

/**
 * Turns the events of one response to [request] into the events of the stream contract, its message keeping [kept],
 * the turn-scoped system message that preceded it.
 */
internal class MessagesResponse(
    private val request: ModelRequest,
    private val flavor: MessagesFlavor,
    private val warnings: List<Warning>,
    private val kept: JsonObject?,
) {
    private val parts = mutableListOf<PartBuilder>()
    private val open = mutableMapOf<Int, PartBuilder>()
    private var responseId: String? = null
    private var model: String? = null
    private var stop: String? = null
    private var detail: String? = null
    private var rawUsage: JsonObject? = null
    private var usage: Usage? = null

    /** The events of `message_start`, which go out with the first event of the response's content. */
    private val held = mutableListOf<ModelEvent>()

    /** Whether any event was made. */
    var started: Boolean = false
        private set

    /** Whether the response has said it is complete. */
    var done: Boolean = false
        private set

    /** The events that the server-sent event [data] of [type] makes, its error kinds read by [classify]. */
    fun event(data: JsonObject, type: String, classify: (ErrorDetail) -> ModelErrorKind?): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        when (data.string("type") ?: type) {
            "message_start" -> {
                val message = data.obj("message")
                responseId = responseId ?: message?.string("id")
                model = model ?: message?.string("model")
                message?.obj("usage")?.let { usage(it, held) }
                return emptyList()
            }

            "content_block_start" -> start(index(data), data.obj("content_block") ?: JsonObject(emptyMap()), events)

            "content_block_delta" -> delta(index(data), data.obj("delta") ?: JsonObject(emptyMap()), events)

            "content_block_stop" -> {
                val builder = open.remove(index(data)) ?: throw failure("A content block stopped that never started.")
                events += builder.complete()
            }

            "message_delta" -> {
                data.obj("delta")?.let(::end)
                data.obj("usage")?.let { usage(it, events) }
            }

            "message_stop" -> done = true

            "error" -> throw streamError(data, started, classify)
        }
        return started(events)
    }

    /** The events of a [message] that came whole, its error kinds read by [classify]. */
    fun message(message: JsonObject, classify: (ErrorDetail) -> ModelErrorKind?): List<ModelEvent> {
        if (message.string("type") == "error") throw streamError(message, started, classify)
        val events = mutableListOf<ModelEvent>()
        events += event(synthetic("message_start") { put("message", JsonObject(message - "content")) }, "", classify)
        val blocks = (message["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        for ((index, block) in blocks.withIndex()) {
            events += event(synthetic("content_block_start", index) { put("content_block", block) }, "", classify)
            events += event(synthetic("content_block_stop", index) {}, "", classify)
        }
        val delta = JsonObject(message.filterKeys { it in END_FIELDS })
        events += event(synthetic("message_delta") { put("delta", delta) }, "", classify)
        return events
    }

    /** The events that complete the response. */
    fun finish(): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        open.values.sortedBy { it.index }.forEach { events += it.complete() }
        open.clear()
        val raw = stop ?: throw failure("The response ended without a stop reason.")
        val providerData = kept?.let {
            ProviderData.EMPTY.with(flavor.dialect, JsonObject(mapOf(KEPT_TURN_CONTEXT to it)))
        } ?: ProviderData.EMPTY
        val message = AssistantEntry(null, parts.map { it.part!! }, request.model, providerData)
        val reason = FinishReason(flavor.finishReasons.kind(raw), raw, detail)
        events += ModelEvent.Completed(message, reason, usage ?: Usage.builder().build())
        return started(events)
    }

    private fun failure(message: String, kind: ModelErrorKind = ModelErrorKind.PROTOCOL) =
        modelException(kind, message, started)

    private fun started(events: MutableList<ModelEvent>): List<ModelEvent> {
        if (started || events.isEmpty()) return events
        started = true
        return listOf(ModelEvent.ResponseStarted(responseId, model, warnings)) + held + events
    }

    private fun index(data: JsonObject): Int = (data["index"] as? JsonPrimitive)?.takeUnless { it.isString }
        ?.intOrNull?.takeIf { it >= 0 } ?: throw failure("A content block event names no index.")

    private fun start(index: Int, block: JsonObject, events: MutableList<ModelEvent>) {
        if (index in open) throw failure("Content block $index started twice.")
        val at = parts.size
        val builder = when (block.string("type")) {
            "text" -> TextBuilder(at).also { it.add(block.string("text").orEmpty(), events) }

            "thinking" -> ThinkingBuilder(at, block.string("signature")).also {
                it.add(block.string("thinking").orEmpty(), events)
            }

            "redacted_thinking" -> RedactedBuilder(at, block)

            "tool_use" -> call(at, block, events)

            else -> OpaqueBuilder(at, block)
        }
        parts += builder
        open[index] = builder
    }

    private fun call(at: Int, block: JsonObject, events: MutableList<ModelEvent>): CallBuilder {
        val wire = block.string("name")?.takeIf { it.isNotEmpty() } ?: throw failure("Tool call $at names no tool.")
        val id = block.string("id")?.takeIf { it.isNotEmpty() } ?: request.ids.callId(at).value
        val builder = CallBuilder(at, id, flavor.tools.callName(wire, request.tools))
        events += ModelEvent.ToolCallStarted(at, ToolCallId(id), builder.name)
        block.obj("input")?.takeIf { it.isNotEmpty() }?.let { builder.add(it.toString(), events) }
        return builder
    }

    private fun delta(index: Int, delta: JsonObject, events: MutableList<ModelEvent>) {
        val builder = open[index] ?: throw failure("Content block $index got a delta while it was not open.")
        when (delta.string("type")) {
            "text_delta" -> (builder as? TextBuilder)?.add(delta.string("text").orEmpty(), events)

            "thinking_delta" -> (builder as? ThinkingBuilder)?.add(delta.string("thinking").orEmpty(), events)

            "signature_delta" -> (builder as? ThinkingBuilder)?.signature = delta.string("signature")

            "input_json_delta" -> {
                val fragment = delta.string("partial_json").orEmpty()
                when (builder) {
                    is CallBuilder -> builder.add(fragment, events)
                    is OpaqueBuilder -> builder.input.append(fragment)
                    else -> Unit
                }
            }
        }
    }

    private fun end(delta: JsonObject) {
        val raw = delta.string("stop_reason")?.takeIf { it.isNotEmpty() } ?: return
        flavor.finishReasons.failure(raw)?.let { throw failure("The response ended for the reason '$raw'.", it) }
        stop = raw
        detail = flavor.finishReasons.detail(delta)
    }

    private fun usage(reported: JsonObject, events: MutableList<ModelEvent>) {
        val raw = JsonObject(rawUsage.orEmpty() + reported)
        rawUsage = raw
        val counted = usage(raw)
        usage = counted
        events += ModelEvent.UsageUpdated(counted)
    }

    /** The usage that [raw] reports, its negative counts left out. */
    private fun usage(raw: JsonObject): Usage {
        val caching = flavor.caching
        val prompt = caching.promptTokens(raw)?.takeIf { it >= 0 }
        val cacheRead = caching.cacheReadTokens(raw)?.takeIf { it >= 0 }
        val cacheWrite = caching.cacheWriteTokens(raw)?.takeIf { it >= 0 }
        val output = raw.long("output_tokens")?.takeIf { it >= 0 }
        val reasoning = flavor.reasoning.reasoningTokens(raw)?.takeIf { it >= 0 }
        return Usage.builder()
            .inputTokens(prompt)
            .cacheReadTokens(cacheRead)
            .cacheWriteTokens(cacheWrite)
            .outputTokens(output)
            .reasoningTokens(reasoning)
            .contextTokens(prompt)
            .raw(raw)
            .build()
    }

    private fun synthetic(type: String, index: Int? = null, fields: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", type)
            index?.let { put("index", it) }
            fields()
        }

    private abstract class PartBuilder(val index: Int) {
        var part: AssistantPart? = null

        abstract fun build(): Pair<List<ModelEvent>, AssistantPart>

        fun complete(): List<ModelEvent> {
            val (events, built) = build()
            part = built
            return events + ModelEvent.PartCompleted(index, built)
        }
    }

    private class TextBuilder(index: Int) : PartBuilder(index) {
        private val text = StringBuilder()

        fun add(piece: String, events: MutableList<ModelEvent>) {
            if (piece.isEmpty()) return
            text.append(piece)
            events += ModelEvent.TextDelta(index, piece)
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> = emptyList<ModelEvent>() to TextPart("$text")
    }

    private inner class ThinkingBuilder(index: Int, var signature: String?) : PartBuilder(index) {
        private val text = StringBuilder()

        fun add(piece: String, events: MutableList<ModelEvent>) {
            if (piece.isEmpty()) return
            text.append(piece)
            events += ModelEvent.ReasoningDelta(index, piece, null)
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val block = buildJsonObject {
                put("type", "thinking")
                put("thinking", "$text")
                signature?.let { put("signature", it) }
            }
            val seal = flavor.reasoning.seal(block, request.model, flavor.dialect)
            val part = ReasoningPart("$text".ifEmpty { null }, null, seal)
            return listOfNotNull(seal?.let { ModelEvent.ReasoningSealed(index, it) }) to part
        }
    }

    private inner class RedactedBuilder(index: Int, private val block: JsonObject) : PartBuilder(index) {
        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val seal = flavor.reasoning.seal(block, request.model, flavor.dialect)
            return listOfNotNull(seal?.let { ModelEvent.ReasoningSealed(index, it) }) to ReasoningPart(null, null, seal)
        }
    }

    private class CallBuilder(index: Int, val id: String, val name: String) : PartBuilder(index) {
        private val arguments = StringBuilder()

        fun add(fragment: String, events: MutableList<ModelEvent>) {
            if (fragment.isEmpty()) return
            arguments.append(fragment)
            events += ModelEvent.ToolArgumentsDelta(index, fragment)
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> =
            emptyList<ModelEvent>() to ToolCallPart(ToolCallId(id), name, "$arguments")
    }

    private inner class OpaqueBuilder(index: Int, private val block: JsonObject) : PartBuilder(index) {
        val input = StringBuilder()

        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val json = if (input.isEmpty()) block else JsonObject(block + ("input" to parsed("$input")))
            return emptyList<ModelEvent>() to OpaquePart(flavor.dialect, block.string("type") ?: "unknown", json)
        }

        private fun parsed(text: String) = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            JsonPrimitive(text)
        }
    }

    private companion object {
        /** The fields of a whole message that a `message_delta` carries in a stream. */
        val END_FIELDS = setOf("stop_reason", "stop_sequence", "stop_details")
    }
}
