package org.unitedsoftware.alexandrite.testkit.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpFailureKind
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.internal.http.SseEvent
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.model.WarningKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantPart
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.ProviderFixture
import org.unitedsoftware.alexandrite.testkit.RecordedRequest
import org.unitedsoftware.alexandrite.testkit.ScriptedToolCall
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import java.net.URI
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/** Whether each model of a reference endpoint takes tools and images. */
private val MODELS = mapOf("chat-model" to true, "plain-model" to false)

/** A way to break the provider contract, and the name of the check that catches it. */
internal enum class Flaw(val check: String) {
    LISTS_TWICE("models are described honestly"),
    NO_TEXT_DELTAS("a text reply streams its text and completes with it"),
    REASONING_AS_TEXT("a reasoning reply keeps its reasoning apart from its text"),
    WIRE_NAMES("parallel tool calls stream by index with their ids, names and arguments"),
    DROPS_RESULTS("a tool round trip sends each call's result back"),
    WRONG_RETRY_AFTER("a rate limit says when to retry"),
    FINAL_SERVER_ERROR("a server error before output may be retried"),
    CUT_OFF_AS_PROTOCOL("a stream cut off after output started says so"),
    HOLDS_ON_CANCEL("cancelling the collector closes the exchange"),
    LOSES_RAW_FINISH("an unknown finish reason is kept raw"),
    DROPS_USAGE("usage is reported"),
    BLANK_LIMIT_AS_ERROR("a response cut off before any output completes at its output limit"),
    TOOLS_TO_PLAIN("a model without tools is sent none"),
    IMAGES_TO_PLAIN("images reach only a model that takes them"),
    SILENT_EFFORT("an effort the model does not take is warned about, and the response still comes"),
}

/** The fixture of a [ReferenceEndpoint] with [flaw], which keeps every endpoint it builds. */
internal class ReferenceFixture(private val flaw: Flaw? = null) : ProviderFixture {
    val built = mutableListOf<ReferenceEndpoint>()

    override val model: String = "chat-model"

    override val plainModel: String = "plain-model"

    override fun endpoint(server: FakeModelServer): ModelEndpoint =
        ReferenceEndpoint(server.baseUrl, flaw).also { built += it }

    override fun models(server: FakeModelServer) {
        val listing = buildJsonObject {
            put("models", JsonArray(MODELS.map { (id, rich) -> JsonPrimitive("$id:$rich") }))
        }
        server.enqueue(fakeResponse { body(listing.toString()) }, "/models")
    }

    override fun text(chunks: List<String>): FakeResponse = events {
        chunks.forEach { send("text", "text", it) }
        send("finish", "reason", "stop")
    }

    override fun textStart(chunk: String): FakeResponse = events { send("text", "text", chunk) }

    override fun reasoning(reasoning: List<String>, text: List<String>): FakeResponse = events {
        reasoning.forEach { send("reasoning", "text", it) }
        text.forEach { send("text", "text", it) }
        send("finish", "reason", "stop")
    }

    override fun toolCalls(calls: List<ScriptedToolCall>): FakeResponse = events {
        for (call in calls) {
            val start = buildJsonObject {
                put("id", call.id)
                put("name", call.wireName)
            }
            event(start.toString(), "call")
            call.arguments.forEach { send("args", "text", it) }
        }
        send("finish", "reason", "tool_calls")
    }

    override fun finish(text: String, raw: String): FakeResponse = events {
        send("text", "text", text)
        send("finish", "reason", raw)
    }

    override fun blankAtOutputLimit(): FakeResponse = events { send("finish", "reason", "length") }

    override fun usage(text: String, usage: Usage): FakeResponse = events {
        send("text", "text", text)
        send("finish", "reason", "stop")
        val counts = buildJsonObject {
            put("input", usage.inputTokens)
            put("cacheRead", usage.cacheReadTokens)
            put("output", usage.outputTokens)
            put("reasoning", usage.reasoningTokens)
        }
        event(counts.toString(), "usage")
    }

    override fun rateLimited(retryAfterSeconds: Int, requestId: String): FakeResponse = FakeResponse.builder(429)
        .header("retry-after", "$retryAfterSeconds")
        .header("x-request-id", requestId)
        .body("{}")
        .build()

    override fun serverError(): FakeResponse = FakeResponse.builder(500).body("{}").build()

    override fun toolResults(request: RecordedRequest): List<Pair<String, String>> =
        request.json()["messages"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "tool" }
            .map { it["id"]!!.jsonPrimitive.content to it["text"]!!.jsonPrimitive.content }

    private fun events(block: FakeResponse.Builder.() -> Unit): FakeResponse = fakeResponse(block = block)

    private fun FakeResponse.Builder.send(type: String, field: String, value: String) {
        event(buildJsonObject { put(field, value) }.toString(), type)
    }
}

/** An endpoint of a wire API of its own, which keeps the provider contract unless it has a [flaw]. */
internal class ReferenceEndpoint(private val base: String, private val flaw: Flaw?) :
    ModelEndpoint,
    AutoCloseable {
    @Volatile
    var closed: Boolean = false
        private set

    private val transport = HttpTransport(HttpTimeouts(2.seconds, 20.seconds, 20.seconds), testHttpEngine())

    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val id: EndpointId = EndpointId("reference")

    override suspend fun models(): List<ModelInfo> {
        val listing = Json.parseToJsonElement(transport.send(HttpCall("GET", URI("$base/models")))).jsonObject
        val models = listing["models"]!!.jsonArray.map { item ->
            val (model, rich) = item.jsonPrimitive.content.split(':')
            info(model, rich.toBoolean())
        }
        return if (flaw == Flaw.LISTS_TWICE) models + models.first() else models
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> {
        if (flaw != Flaw.HOLDS_ON_CANCEL) return exchange(request)
        return flow {
            val events = Channel<ModelEvent>(Channel.UNLIMITED)
            detached.launch {
                try {
                    exchange(request).collect { events.send(it) }
                    events.close()
                } catch (e: Throwable) {
                    events.close(e)
                }
            }
            for (event in events) emit(event)
        }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        TurnContextMode.TRANSIENT

    override fun close() {
        closed = true
        detached.cancel()
        transport.close()
    }

    private fun exchange(request: ModelRequest): Flow<ModelEvent> = flow {
        val rich = MODELS.getValue(request.model.model)
        if (request.tools.isNotEmpty() && !rich && flaw != Flaw.TOOLS_TO_PLAIN) {
            throw failure(ModelErrorKind.UNSUPPORTED, "Model ${request.model} takes no tools.", false)
        }
        val effort = request.options.reasoning
        val warnings = listOfNotNull(
            effort?.takeIf { flaw != Flaw.SILENT_EFFORT }?.let {
                Warning(
                    WarningKind.UNSUPPORTED_OPTION,
                    "Model ${request.model} takes no reasoning effort; $it is left out.",
                )
            },
        )
        val reply = Reply(request, flaw, warnings)
        val call = HttpCall("POST", URI("$base/respond"), mapOf("content-type" to "application/json"), body(request))
        try {
            transport.open(call) { response ->
                response.events().collect { event -> reply.on(event).forEach { emit(it) } }
            }
        } catch (e: HttpException) {
            throw failure(e, reply.started)
        }
        reply.finish().forEach { emit(it) }
    }

    private fun body(request: ModelRequest): String = buildJsonObject {
        val images = MODELS.getValue(request.model.model) || flaw == Flaw.IMAGES_TO_PLAIN
        put("model", request.model.model)
        put("tools", JsonArray(request.tools.map { JsonPrimitive(ToolNames.wire(it.name)) }))
        val messages = buildJsonArray {
            for (entry in request.history) {
                when (entry) {
                    is UserEntry -> add(
                        buildJsonObject {
                            put("role", "user")
                            put("text", entry.parts.filterIsInstance<TextPart>().joinToString { it.text })
                            val sent = entry.parts.filterIsInstance<MediaPart>().mapNotNull { part ->
                                (part.source as? InlineMedia)?.takeIf { images && part.kind == MediaKind.IMAGE }
                            }
                            val encoded = sent.map { Base64.getEncoder().encodeToString(it.bytes()) }
                            put("images", JsonArray(encoded.map(::JsonPrimitive)))
                        },
                    )

                    is AssistantEntry -> add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("text", entry.parts.filterIsInstance<TextPart>().joinToString { it.text })
                        },
                    )

                    is ToolResultEntry -> if (flaw != Flaw.DROPS_RESULTS) {
                        add(
                            buildJsonObject {
                                put("role", "tool")
                                put("id", entry.callId.value)
                                put("text", entry.content.filterIsInstance<TextPart>().joinToString { it.text })
                            },
                        )
                    }

                    else -> Unit
                }
            }
        }
        put("messages", messages)
    }.toString()

    private fun failure(error: HttpException, started: Boolean): ModelException {
        val kind = when (error.kind) {
            HttpFailureKind.RATE_LIMITED -> ModelErrorKind.RATE_LIMITED

            HttpFailureKind.SERVER_ERROR ->
                if (flaw == Flaw.FINAL_SERVER_ERROR) ModelErrorKind.INVALID_REQUEST else ModelErrorKind.SERVER_ERROR

            HttpFailureKind.CONNECTION ->
                if (flaw == Flaw.CUT_OFF_AS_PROTOCOL) ModelErrorKind.PROTOCOL else ModelErrorKind.CONNECTION

            HttpFailureKind.TIMEOUT -> ModelErrorKind.TIMEOUT

            else -> ModelErrorKind.PROTOCOL
        }
        val retryAfter = if (flaw == Flaw.WRONG_RETRY_AFTER) error.retryAfter?.times(2) else error.retryAfter
        val built = ModelError.builder(kind, error.message ?: "The exchange failed.")
            .retryAfter(retryAfter)
            .status(error.status)
            .requestId(error.requestId)
            .outputStarted(started)
            .build()
        return ModelException(built, error)
    }

    private companion object {
        val DIALECT = Dialect("reference")

        fun info(model: String, rich: Boolean): ModelInfo = ModelInfo.builder(model, DIALECT)
            .nativeTools(rich)
            .parallelToolCalls(rich)
            .inputMedia(if (rich) setOf(MediaKind.IMAGE) else emptySet())
            .streaming(true)
            .build()

        fun failure(kind: ModelErrorKind, message: String, started: Boolean): ModelException =
            ModelException(ModelError.builder(kind, message).outputStarted(started).build())
    }
}

/** The events of one response to [request], which an endpoint with [flaw] gets wrong. */
private class Reply(private val request: ModelRequest, private val flaw: Flaw?, private val warnings: List<Warning>) {
    var started = false
        private set

    private val parts = mutableListOf<AssistantPart>()
    private var kind: String? = null
    private val text = StringBuilder()
    private var call: Pair<ToolCallId, String>? = null
    private var finish: String? = null
    private var usage: Usage? = null

    fun on(event: SseEvent): List<ModelEvent> {
        val data = Json.parseToJsonElement(event.data).jsonObject
        val events = mutableListOf<ModelEvent>()
        val index = parts.size
        when (event.type) {
            "text", "reasoning" -> {
                val type = if (flaw == Flaw.REASONING_AS_TEXT) "text" else event.type
                val piece = data.string("text")
                if (kind != type) {
                    close(events)
                    kind = type
                }
                text.append(piece)
                when {
                    type == "reasoning" -> events += ModelEvent.ReasoningDelta(parts.size, piece, null)
                    flaw != Flaw.NO_TEXT_DELTAS -> events += ModelEvent.TextDelta(parts.size, piece)
                }
            }

            "call" -> {
                close(events)
                val wire = data.string("name")
                val name = if (flaw == Flaw.WIRE_NAMES) wire else ToolNames.fromWire(wire) ?: wire
                val id = ToolCallId(data.string("id"))
                kind = "call"
                call = id to name
                events += ModelEvent.ToolCallStarted(parts.size, id, name)
            }

            "args" -> {
                text.append(data.string("text"))
                events += ModelEvent.ToolArgumentsDelta(index, data.string("text"))
            }

            "usage" -> if (flaw != Flaw.DROPS_USAGE) {
                val counted = Usage.builder()
                    .inputTokens(data.count("input"))
                    .cacheReadTokens(data.count("cacheRead"))
                    .outputTokens(data.count("output"))
                    .reasoningTokens(data.count("reasoning"))
                    .contextTokens(data.count("input"))
                    .raw(data)
                    .build()
                usage = counted
                events += ModelEvent.UsageUpdated(counted)
            }

            "finish" -> finish = data.string("reason")
        }
        return started(events)
    }

    fun finish(): List<ModelEvent> {
        val events = mutableListOf<ModelEvent>()
        close(events)
        val raw = finish ?: throw ModelException(
            ModelError.builder(ModelErrorKind.PROTOCOL, "The response ended without a finish reason.")
                .outputStarted(started)
                .build(),
        )
        if (raw == "length" && parts.isEmpty() && flaw == Flaw.BLANK_LIMIT_AS_ERROR) {
            throw ModelException(
                ModelError.builder(ModelErrorKind.PROTOCOL, "The response holds no output.").outputStarted(started)
                    .build(),
            )
        }
        val finishKind = when (raw) {
            "stop" -> FinishKind.END_TURN
            "tool_calls" -> FinishKind.TOOL_USE
            "length" -> FinishKind.MAX_OUTPUT_TOKENS
            else -> if (flaw == Flaw.LOSES_RAW_FINISH) FinishKind.END_TURN else FinishKind.OTHER
        }
        val reason = FinishReason(finishKind, raw.takeUnless { flaw == Flaw.LOSES_RAW_FINISH }, null)
        events += ModelEvent.Completed(
            AssistantEntry(null, parts.toList(), request.model),
            reason,
            usage ?: Usage.builder().build(),
        )
        return started(events)
    }

    private fun close(events: MutableList<ModelEvent>) {
        val part = when (kind) {
            "text" -> TextPart(text.toString())
            "reasoning" -> ReasoningPart(text.toString(), null, null)
            "call" -> call!!.let { (id, name) -> ToolCallPart(id, name, text.toString()) }
            else -> return
        }
        events += ModelEvent.PartCompleted(parts.size, part)
        parts += part
        kind = null
        text.setLength(0)
    }

    private fun started(events: List<ModelEvent>): List<ModelEvent> {
        if (started || events.isEmpty()) return events
        started = true
        return listOf(ModelEvent.ResponseStarted(null, request.model.model, warnings)) + events
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.count(name: String): Long = getValue(name).jsonPrimitive.long
}
