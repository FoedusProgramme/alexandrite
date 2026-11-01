package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.modelException
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.streamError
import org.unitedsoftware.alexandrite.provider.common.stringContent
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantPart
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.ProviderData
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart

@Serializable
internal class ChunkWire(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChoiceWire> = emptyList(),
    val usage: JsonObject? = null,
    val error: JsonElement? = null,
)

@Serializable
internal class ChoiceWire(
    val index: Int = 0,
    val delta: JsonObject? = null,
    /** The whole message of a response that is not streamed. */
    val message: JsonObject? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal class DeltaWire(
    val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallWire>? = null,
)

@Serializable
internal class ToolCallWire(val index: Int? = null, val id: String? = null, val function: FunctionWire? = null)

@Serializable
internal class FunctionWire(val name: String? = null, val arguments: String? = null)

/** Turns the chunks of one response to [request] into the events of the stream contract. */
internal class ChatResponse(
    private val request: ModelRequest,
    private val flavor: ChatFlavor,
    private val warnings: List<Warning>,
) {
    private val parts = mutableListOf<PartBuilder>()
    private var current: PartBuilder? = null
    private val calls = mutableListOf<CallBuilder>()
    private val indexed = mutableMapOf<Int, CallBuilder>()

    /** The last call at each position of a chunk's calls that name no index. */
    private val unindexed = mutableMapOf<Int, CallBuilder>()
    private var responseId: String? = null
    private var model: String? = null
    private var finish: String? = null
    private var usage: Usage? = null

    /** Whether any event was made. */
    var started: Boolean = false
        private set

    fun chunk(chunk: ChunkWire): List<ModelEvent> {
        responseId = responseId ?: chunk.id
        model = model ?: chunk.model
        chunk.error?.let { throw streamError(it, started) }
        val events = mutableListOf<ModelEvent>()
        chunk.choices.firstOrNull { it.index == 0 }?.let { choice ->
            (choice.delta ?: choice.message)?.let { delta(it, events) }
            choice.finishReason?.takeIf { it.isNotEmpty() }?.let { raw ->
                flavor.finishReasons.failure(raw)?.let {
                    throw failure(it, "The response ended for the reason '$raw'.")
                }
                finish = raw
            }
        }
        chunk.usage?.let { raw ->
            val reported = usage(raw, flavor.caching)
            usage = reported
            events += ModelEvent.UsageUpdated(reported)
        }
        return started(events)
    }

    /** The events that complete the response. */
    fun finish(): List<ModelEvent> {
        val raw = finish ?: throw failure(ModelErrorKind.PROTOCOL, "The response ended without a finish reason.")
        val events = mutableListOf<ModelEvent>()
        close(events)
        for (call in calls) {
            if (call.name == null) throw failure(ModelErrorKind.PROTOCOL, "Tool call ${call.index} names no tool.")
            events += call.complete()
        }
        val message = AssistantEntry(null, parts.map { it.part!! }, request.model)
        val reason = FinishReason(flavor.finishReasons.kind(raw), raw, null)
        events += ModelEvent.Completed(message, reason, usage ?: Usage.builder().build())
        return started(events)
    }

    private fun failure(kind: ModelErrorKind, message: String) = modelException(kind, message, started)

    private fun started(events: MutableList<ModelEvent>): List<ModelEvent> {
        if (events.isEmpty() || started) return events
        started = true
        return listOf(ModelEvent.ResponseStarted(responseId, model, warnings)) + events
    }

    private fun delta(raw: JsonObject, events: MutableList<ModelEvent>) {
        val delta = try {
            DELTAS.decodeFromJsonElement(DeltaWire.serializer(), raw)
        } catch (e: IllegalArgumentException) {
            throw failure(ModelErrorKind.PROTOCOL, "The response sent a delta that does not read.")
        }
        val reasoning = delta.reasoningContent ?: delta.reasoning.stringContent()
        val reader = (current as? ReasoningBuilder)?.reader ?: flavor.reasoning.reader(request.model)
        val own = reader.read(raw)
        if (!reasoning.isNullOrEmpty() || own != null) {
            val builder = current as? ReasoningBuilder ?: open(ReasoningBuilder(parts.size, reader), events)
            builder.add(reasoning, own, events)
        }
        delta.content.stringContent()?.takeIf { it.isNotEmpty() }?.let { text ->
            val builder = current as? TextBuilder ?: open(TextBuilder(parts.size, flavor.text.reader()), events)
            val read = builder.reader.read(text)
            read.malformed?.let { throw failure(ModelErrorKind.MALFORMED_OUTPUT, it) }
            builder.show(read.shown, events)
        }
        val raws = raw["tool_calls"] as? JsonArray
        delta.toolCalls?.forEachIndexed { position, call ->
            call(call, raws?.getOrNull(position) as? JsonObject, position, events)
        }
    }

    private fun call(wire: ToolCallWire, raw: JsonObject?, position: Int, events: MutableList<ModelEvent>) {
        val index = wire.index
        val call = if (index != null) {
            indexed.getOrPut(index) { newCall(events) }
        } else {
            val last = unindexed[position]?.takeUnless { startsAnother(wire, it) }
            last ?: newCall(events).also { unindexed[position] = it }
        }
        if (call.id == null) wire.id?.takeIf { it.isNotEmpty() }?.let { call.id = it }
        if (call.wireId == null) call.wireId = wire.id?.takeIf { it.isNotEmpty() }
        if (call.name == null) {
            wire.function?.name?.takeIf {
                it.isNotEmpty()
            }?.let { call.name = flavor.tools.callName(it, request.tools) }
        }
        raw?.let(flavor.tools::readCall)?.let { call.data = JsonObject(call.data.orEmpty() + it) }
        val fragment = wire.function?.arguments.orEmpty()
        call.arguments.append(fragment)
        val name = call.name ?: return
        if (!call.started) {
            call.started = true
            val id = call.id ?: request.ids.callId(call.index).value
            call.id = id
            events += ModelEvent.ToolCallStarted(call.index, ToolCallId(id), name)
            if (call.arguments.isNotEmpty()) {
                events +=
                    ModelEvent.ToolArgumentsDelta(call.index, call.arguments.toString())
            }
        } else if (fragment.isNotEmpty()) {
            events += ModelEvent.ToolArgumentsDelta(call.index, fragment)
        }
    }

    private fun newCall(events: MutableList<ModelEvent>): CallBuilder {
        close(events)
        return CallBuilder(parts.size, flavor.dialect).also {
            parts += it
            calls += it
        }
    }

    /** Whether [wire], which names no index, starts a call after [call]: by its id, else by a second name. */
    private fun startsAnother(wire: ToolCallWire, call: CallBuilder): Boolean {
        val id = wire.id?.takeIf { it.isNotEmpty() }
        if (id != null && call.wireId != null) return id != call.wireId
        val name = wire.function?.name?.takeIf { it.isNotEmpty() }
        return name != null && call.name != null
    }

    private fun <T : PartBuilder> open(builder: T, events: MutableList<ModelEvent>): T {
        close(events)
        parts += builder
        current = builder
        return builder
    }

    private fun close(events: MutableList<ModelEvent>) {
        current?.let { events += it.complete() }
        current = null
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

    private class TextBuilder(index: Int, val reader: ChatTextReader) : PartBuilder(index) {
        private val text = StringBuilder()

        fun show(shown: String, events: MutableList<ModelEvent>) {
            if (shown.isEmpty()) return
            text.append(shown)
            events += ModelEvent.TextDelta(index, shown)
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val events = mutableListOf<ModelEvent>()
            show(reader.release(), events)
            return events to TextPart(text.toString())
        }
    }

    private class ReasoningBuilder(index: Int, val reader: ChatReasoningReader) : PartBuilder(index) {
        private val text = StringBuilder()
        private val summary = StringBuilder()

        fun add(reasoning: String?, own: ChatReasoningDelta?, events: MutableList<ModelEvent>) {
            val shown = reasoning?.ifEmpty { null } ?: own?.text?.ifEmpty { null }
            val summarized = own?.summary?.ifEmpty { null }
            shown?.let { text.append(it) }
            summarized?.let { summary.append(it) }
            if (shown != null || summarized != null) events += ModelEvent.ReasoningDelta(index, shown, summarized)
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val seal = reader.seal(text.toString())
            val part = ReasoningPart(text.toString().ifEmpty { null }, summary.toString().ifEmpty { null }, seal)
            return listOfNotNull(seal?.let { ModelEvent.ReasoningSealed(index, it) }) to part
        }
    }

    private class CallBuilder(index: Int, private val dialect: Dialect) : PartBuilder(index) {
        var id: String? = null
        var wireId: String? = null
        var name: String? = null
        var started = false
        val arguments = StringBuilder()

        /** What the flavor keeps of the call's raw fragments. */
        var data: JsonObject? = null

        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val kept = data?.let { ProviderData.EMPTY.with(dialect, it) } ?: ProviderData.EMPTY
            return emptyList<ModelEvent>() to ToolCallPart(ToolCallId(id!!), name!!, arguments.toString(), kept)
        }
    }

    private companion object {
        val DELTAS = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

/** The usage that [raw] reports, its cache counts read by [caching] and its negative counts left out. */
internal fun usage(raw: JsonObject, caching: ChatCaching): Usage {
    val input = raw.long("prompt_tokens")?.takeIf { it >= 0 }
    return Usage.builder()
        .inputTokens(input)
        .cacheReadTokens(caching.cacheReadTokens(raw)?.takeIf { it >= 0 })
        .cacheWriteTokens(caching.cacheWriteTokens(raw)?.takeIf { it >= 0 })
        .outputTokens(raw.long("completion_tokens")?.takeIf { it >= 0 })
        .reasoningTokens(raw.obj("completion_tokens_details")?.long("reasoning_tokens")?.takeIf { it >= 0 })
        .contextTokens(input)
        .raw(raw)
        .build()
}
