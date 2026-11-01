package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart

/**
 * Turns the events of one response to [request] into the events of the stream contract: each output item becomes one
 * part, in the order the items start, and completes when its `response.output_item.done` arrives.
 */
internal class ResponsesResponse(
    private val request: ModelRequest,
    private val flavor: ResponsesFlavor,
    private val warnings: List<Warning>,
) {
    private val parts = mutableListOf<PartBuilder>()

    /** The items that have not completed yet, by their output index. */
    private val open = mutableMapOf<Int, PartBuilder>()
    private var responseId: String? = null
    private var model: String? = null
    private var status: String? = null
    private var incomplete: String? = null
    private var usage: Usage? = null

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
            "response.created", "response.queued", "response.in_progress" -> data.obj("response")?.let(::identify)

            "response.output_item.added" -> {
                val index = outputIndex(data)
                if (index in open) throw failure("Output item $index started twice.")
                open(index, data.obj("item") ?: EMPTY, events)
            }

            "response.output_item.done" -> {
                val index = outputIndex(data)
                val item = data.obj("item") ?: EMPTY
                val builder = open.remove(index) ?: open(index, item, events).also { open.remove(index) }
                builder.done(item, events)
                events += builder.complete()
            }

            "response.output_text.delta" ->
                builder<TextBuilder>(data) { TextBuilder(it) }.add(data.string("delta").orEmpty(), events)

            "response.refusal.delta" -> builder<TextBuilder>(data) { TextBuilder(it) }.refusal.append(
                data.string("delta").orEmpty(),
            )

            "response.function_call_arguments.delta" ->
                builder<CallBuilder>(data) { CallBuilder(it) }.add(data.string("delta").orEmpty(), events)

            "response.reasoning_summary_text.delta" -> builder<ReasoningBuilder>(data) { ReasoningBuilder(it) }
                .summary(summaryIndex(data, "summary_index"), data.string("delta").orEmpty(), events)

            "response.reasoning_text.delta" -> builder<ReasoningBuilder>(data) { ReasoningBuilder(it) }
                .text(summaryIndex(data, "content_index"), data.string("delta").orEmpty(), events)

            "response.completed" -> end(data.obj("response") ?: EMPTY, "completed", events)

            "response.incomplete" -> end(data.obj("response") ?: EMPTY, "incomplete", events)

            "response.failed" -> {
                val error = data.obj("response")?.get("error") as? JsonObject ?: EMPTY
                throw streamError(JsonObject(mapOf("error" to error)), started, classify)
            }

            "error" -> {
                val error = data.obj("error") ?: JsonObject(data - "type")
                throw streamError(JsonObject(mapOf("error" to error)), started, classify)
            }
        }
        return started(events)
    }

    /** The events of a [response] that came whole, its error kinds read by [classify]. */
    fun response(response: JsonObject, classify: (ErrorDetail) -> ModelErrorKind?): List<ModelEvent> {
        if (response["error"] is JsonObject) {
            throw streamError(JsonObject(mapOf("error" to response["error"]!!)), started, classify)
        }
        val events = mutableListOf<ModelEvent>()
        val items = (response["output"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        for ((index, item) in items.withIndex()) {
            events += event(synthetic("response.output_item.done", index, item), "", classify)
        }
        val end = if (response.string("status") == "incomplete") "response.incomplete" else "response.completed"
        val ending = buildJsonObject {
            put("type", end)
            put("response", response)
        }
        events += event(ending, "", classify)
        return events
    }

    /** The events that complete the response. */
    fun finish(): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        open.entries.sortedBy { it.value.index }.forEach { (_, builder) ->
            builder.done(EMPTY, events)
            events += builder.complete()
        }
        open.clear()
        val ended = status ?: throw failure("The response ended before it completed.")
        val built = parts.map { it.part!! }
        val refusal = parts.filterIsInstance<TextBuilder>().map { "${it.refusal}" }.filter { it.isNotEmpty() }
        val raw = when {
            ended == "incomplete" -> incomplete ?: ended
            ended == "completed" && refusal.isNotEmpty() -> "refusal"
            else -> ended
        }
        val kind = flavor.finishReasons.kind(raw, built.any { it is ToolCallPart })
        val message = AssistantEntry(null, built, request.model)
        val reason = FinishReason(kind, raw, refusal.joinToString("\n").ifEmpty { null })
        events += ModelEvent.Completed(message, reason, usage ?: Usage.builder().build())
        return started(events)
    }

    private fun identify(response: JsonObject) {
        responseId = responseId ?: response.string("id")
        model = model ?: response.string("model")
    }

    /** Ends the response with its [response] object, whose status is [fallback] where it names none. */
    private fun end(response: JsonObject, fallback: String, events: MutableList<ModelEvent>) {
        identify(response)
        status = response.string("status") ?: fallback
        incomplete = response.obj("incomplete_details")?.string("reason")
        response.obj("usage")?.let { raw ->
            val counted = usage(raw)
            usage = counted
            events += ModelEvent.UsageUpdated(counted)
        }
        done = true
    }

    /** The usage that [raw] reports, its negative counts left out. */
    private fun usage(raw: JsonObject): Usage {
        val input = raw.long("input_tokens")?.takeIf { it >= 0 }
        return Usage.builder()
            .inputTokens(input)
            .cacheReadTokens(flavor.caching.cacheReadTokens(raw)?.takeIf { it >= 0 })
            .cacheWriteTokens(flavor.caching.cacheWriteTokens(raw)?.takeIf { it >= 0 })
            .outputTokens(raw.long("output_tokens")?.takeIf { it >= 0 })
            .reasoningTokens(flavor.reasoning.reasoningTokens(raw)?.takeIf { it >= 0 })
            .contextTokens(input)
            .raw(raw)
            .build()
    }

    private fun failure(message: String) = modelException(ModelErrorKind.PROTOCOL, message, started)

    private fun started(events: MutableList<ModelEvent>): List<ModelEvent> {
        if (started || events.isEmpty()) return events
        started = true
        return listOf(ModelEvent.ResponseStarted(responseId, model, warnings)) + events
    }

    private fun outputIndex(data: JsonObject): Int = index(data, "output_index")
        ?: throw failure("An output item event names no output index.")

    private fun summaryIndex(data: JsonObject, name: String): Int = index(data, name) ?: 0

    private fun index(data: JsonObject, name: String): Int? =
        (data[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }

    /** The open item that the delta [data] belongs to, which [create] starts when the delta comes first. */
    private inline fun <reified T : PartBuilder> builder(data: JsonObject, create: (Int) -> T): T {
        val index = outputIndex(data)
        val builder = open.getOrPut(index) { create(parts.size).also { parts += it } }
        return builder as? T ?: throw failure("Output item $index got a delta of another kind.")
    }

    private fun open(index: Int, item: JsonObject, events: MutableList<ModelEvent>): PartBuilder {
        val at = parts.size
        val builder = when (item.string("type")) {
            "message" -> TextBuilder(at)
            "function_call" -> CallBuilder(at).also { it.start(item, events) }
            "reasoning" -> ReasoningBuilder(at)
            else -> OpaqueBuilder(at, item)
        }
        parts += builder
        open[index] = builder
        return builder
    }

    private fun synthetic(type: String, index: Int, item: JsonObject): JsonObject = buildJsonObject {
        put("type", type)
        put("output_index", index)
        put("item", item)
    }

    private abstract class PartBuilder(val index: Int) {
        var part: AssistantPart? = null

        /** Takes what the complete [item] says that its deltas did not. */
        abstract fun done(item: JsonObject, events: MutableList<ModelEvent>)

        abstract fun build(): Pair<List<ModelEvent>, AssistantPart>

        fun complete(): List<ModelEvent> {
            val (events, built) = build()
            part = built
            return events + ModelEvent.PartCompleted(index, built)
        }
    }

    private class TextBuilder(index: Int) : PartBuilder(index) {
        private val text = StringBuilder()
        private var deltas = false
        val refusal = StringBuilder()

        fun add(piece: String, events: MutableList<ModelEvent>) {
            deltas = true
            if (piece.isEmpty()) return
            text.append(piece)
            events += ModelEvent.TextDelta(index, piece)
        }

        override fun done(item: JsonObject, events: MutableList<ModelEvent>) {
            val content = (item["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            if (!deltas) {
                add(
                    content.filter {
                        it.string("type") == "output_text"
                    }.joinToString("") { it.text() },
                    events,
                )
            }
            if (refusal.isEmpty()) {
                content.filter {
                    it.string("type") == "refusal"
                }.forEach { refusal.append(it.string("refusal").orEmpty()) }
            }
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> = emptyList<ModelEvent>() to TextPart("$text")

        private fun JsonObject.text(): String = string("text").orEmpty()
    }

    private inner class CallBuilder(index: Int) : PartBuilder(index) {
        private var id: String? = null
        private var name: String? = null
        private val arguments = StringBuilder()
        private var deltas = false

        /** Starts the call that [item] names, or leaves it until a later item names its tool. */
        fun start(item: JsonObject, events: MutableList<ModelEvent>) {
            if (name != null) return
            val wire = item.string("name")?.takeIf { it.isNotEmpty() } ?: return
            val callId = item.string("call_id")?.takeIf { it.isNotEmpty() } ?: request.ids.callId(index).value
            id = callId
            name = flavor.tools.callName(wire, request.tools)
            events += ModelEvent.ToolCallStarted(index, ToolCallId(callId), name!!)
            if (arguments.isNotEmpty()) events += ModelEvent.ToolArgumentsDelta(index, "$arguments")
        }

        fun add(fragment: String, events: MutableList<ModelEvent>) {
            deltas = true
            if (fragment.isEmpty()) return
            arguments.append(fragment)
            if (name != null) events += ModelEvent.ToolArgumentsDelta(index, fragment)
        }

        override fun done(item: JsonObject, events: MutableList<ModelEvent>) {
            start(item, events)
            if (!deltas) item.string("arguments")?.let { add(it, events) }
            if (name == null) throw failure("Tool call $index names no tool.")
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> =
            emptyList<ModelEvent>() to ToolCallPart(ToolCallId(id!!), name!!, "$arguments")
    }

    private inner class ReasoningBuilder(index: Int) : PartBuilder(index) {
        private val text = Pieces()
        private val summary = Pieces()
        private var item: JsonObject = EMPTY

        fun summary(at: Int, piece: String, events: MutableList<ModelEvent>) {
            summary.add(at, piece)?.let { events += ModelEvent.ReasoningDelta(index, null, it) }
        }

        fun text(at: Int, piece: String, events: MutableList<ModelEvent>) {
            text.add(at, piece)?.let { events += ModelEvent.ReasoningDelta(index, it, null) }
        }

        override fun done(item: JsonObject, events: MutableList<ModelEvent>) {
            if (item.isNotEmpty()) this.item = item
            if (!summary.streamed) texts(item, "summary").forEachIndexed { at, piece -> summary(at, piece, events) }
            if (!text.streamed) texts(item, "content").forEachIndexed { at, piece -> text(at, piece, events) }
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> {
            val seal = flavor.reasoning.seal(item, request.model, flavor.dialect)
            val part = ReasoningPart(text.joined(), summary.joined(), seal)
            return listOfNotNull(seal?.let { ModelEvent.ReasoningSealed(index, it) }) to part
        }

        private fun texts(item: JsonObject, name: String): List<String> =
            (item[name] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { it.string("text").orEmpty() }
    }

    private inner class OpaqueBuilder(index: Int, private var item: JsonObject) : PartBuilder(index) {
        override fun done(item: JsonObject, events: MutableList<ModelEvent>) {
            if (item.isNotEmpty()) this.item = item
        }

        override fun build(): Pair<List<ModelEvent>, AssistantPart> =
            emptyList<ModelEvent>() to OpaquePart(flavor.dialect, item.string("type") ?: "unknown", item)
    }

    /** Text that streams in numbered pieces, joined by blank lines. */
    private class Pieces {
        private val text = StringBuilder()
        private var last: Int? = null

        val streamed: Boolean get() = last != null

        /** Adds [piece] to the piece [at] and returns what the text grew by, null when it did not grow. */
        fun add(at: Int, piece: String): String? {
            if (piece.isEmpty()) return null
            val separator = if (last != null && last != at) SEPARATOR else ""
            last = at
            text.append(separator).append(piece)
            return separator + piece
        }

        fun joined(): String? = "$text".ifEmpty { null }
    }

    private companion object {
        const val SEPARATOR = "\n\n"
        val EMPTY = JsonObject(emptyMap())
    }
}
