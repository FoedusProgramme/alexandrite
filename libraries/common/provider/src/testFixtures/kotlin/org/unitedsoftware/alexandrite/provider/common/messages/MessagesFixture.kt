package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_API_KEY
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_TIMEOUTS
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.ProviderFixture
import org.unitedsoftware.alexandrite.testkit.RecordedRequest
import org.unitedsoftware.alexandrite.testkit.ScriptedToolCall
import org.unitedsoftware.alexandrite.testkit.fakeResponse

/** An endpoint `test` of [flavor] at [server], with the models [models] configured. */
public fun messagesEndpoint(
    server: FakeModelServer,
    flavor: MessagesFlavor = MessagesFlavor.STANDARD,
    models: Map<String, ModelConfig> = emptyMap(),
    discover: Boolean = true,
    turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    messages: MessagesSettings = MessagesSettings(),
): MessagesEndpoint {
    val settings = EndpointSettings(
        baseUrl = server.baseUrl,
        apiKey = Secret(FIXTURE_API_KEY),
        models = models,
        discover = discover,
        turnContext = turnContext,
        timeouts = FIXTURE_TIMEOUTS,
        wire = MessagesEndpoint.WIRE,
    )
    return MessagesEndpoint(EndpointId("test"), settings, JdkHttpEngine(), flavor, messages)
}

/** The events of [request]'s response. */
public suspend fun MessagesEndpoint.events(request: ModelRequest): List<ModelEvent> = stream(request).toList()

/** An event stream of [events], each sent under its own `type`. */
public fun sse(vararg events: JsonObject): FakeResponse = fakeResponse {
    events.forEach { event(it.toString(), it["type"]!!.jsonPrimitive.content) }
}

/** A usage object as the standard API counts it, whose `input_tokens` leave out what the cache read and wrote. */
public fun messagesUsage(input: Long, cacheRead: Long, cacheWrite: Long, output: Long): JsonObject = buildJsonObject {
    put("input_tokens", input)
    put("cache_creation_input_tokens", cacheWrite)
    put("cache_read_input_tokens", cacheRead)
    put("output_tokens", output)
}

public fun messageStart(usage: JsonObject = messagesUsage(10, 0, 0, 1)): JsonObject = buildJsonObject {
    put("type", "message_start")
    putJsonObject("message") {
        put("id", "msg_1")
        put("type", "message")
        put("role", "assistant")
        putJsonArray("content") {}
        put("model", "claude-model")
        put("stop_reason", null as String?)
        put("usage", usage)
    }
}

public fun blockStart(index: Int, block: JsonObject): JsonObject = buildJsonObject {
    put("type", "content_block_start")
    put("index", index)
    put("content_block", block)
}

public fun textBlock(text: String = ""): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

public fun thinkingBlock(): JsonObject = buildJsonObject {
    put("type", "thinking")
    put("thinking", "")
    put("signature", "")
}

public fun toolUseBlock(id: String, name: String): JsonObject = buildJsonObject {
    put("type", "tool_use")
    put("id", id)
    put("name", name)
    putJsonObject("input") {}
}

/** A `content_block_delta` at [index] whose delta is of [type] and sets [field] to [value]. */
public fun blockDelta(index: Int, type: String, field: String, value: String): JsonObject = buildJsonObject {
    put("type", "content_block_delta")
    put("index", index)
    putJsonObject("delta") {
        put("type", type)
        put(field, value)
    }
}

public fun textDelta(index: Int, text: String): JsonObject = blockDelta(index, "text_delta", "text", text)

public fun thinkingDelta(index: Int, text: String): JsonObject = blockDelta(index, "thinking_delta", "thinking", text)

public fun signatureDelta(index: Int, signature: String): JsonObject =
    blockDelta(index, "signature_delta", "signature", signature)

public fun inputDelta(index: Int, json: String): JsonObject =
    blockDelta(index, "input_json_delta", "partial_json", json)

public fun blockStop(index: Int): JsonObject = buildJsonObject {
    put("type", "content_block_stop")
    put("index", index)
}

/** A `message_delta` that ends the message for [stopReason] and reports [usage]. */
public fun messageDelta(stopReason: String, usage: JsonObject? = outputUsage(5)): JsonObject = buildJsonObject {
    put("type", "message_delta")
    putJsonObject("delta") {
        put("stop_reason", stopReason)
        put("stop_sequence", null as String?)
    }
    usage?.let { put("usage", it) }
}

/** The usage of a `message_delta`: [output] tokens, of which [thinking] were thinking when not null. */
public fun outputUsage(output: Long, thinking: Long? = null): JsonObject = buildJsonObject {
    put("output_tokens", output)
    thinking?.let { putJsonObject("output_tokens_details") { put("thinking_tokens", it) } }
}

public fun messageStop(): JsonObject = buildJsonObject { put("type", "message_stop") }

/** The events of a text reply streamed in [chunks] that ends for [stopReason]. */
public fun textEvents(chunks: List<String>, stopReason: String = "end_turn"): Array<JsonObject> = arrayOf(
    messageStart(),
    blockStart(0, textBlock()),
    *chunks.map { textDelta(0, it) }.toTypedArray(),
    blockStop(0),
    messageDelta(stopReason),
    messageStop(),
)

/** An error body of [type] as the standard API sends it. */
public fun messagesError(type: String, message: String): String = buildJsonObject {
    put("type", "error")
    putJsonObject("error") {
        put("type", type)
        put("message", message)
    }
}.toString()

/** The messages a recorded Messages request sends. */
public fun RecordedRequest.messageList(): List<JsonObject> = json()["messages"]!!.jsonArray.map { it.jsonObject }

/** This object without its `cache_control` fields, at any depth. */
public fun JsonElement.uncached(): JsonElement = when (this) {
    is JsonObject -> JsonObject(filterKeys { it != "cache_control" }.mapValues { it.value.uncached() })
    is JsonArray -> JsonArray(map { it.uncached() })
    else -> this
}

/**
 * The provider checks' fixture of an endpoint of [flavor], with a model that takes tools and images and one that
 * takes neither.
 */
public open class MessagesFixture(private val flavor: MessagesFlavor = MessagesFlavor.STANDARD) : ProviderFixture {
    override val model: String = "claude-model"

    override val plainModel: String = "plain-model"

    override fun endpoint(server: FakeModelServer): MessagesEndpoint = messagesEndpoint(
        server,
        flavor,
        mapOf(
            model to ModelConfig(nativeTools = true, parallelToolCalls = true, inputMedia = setOf("image")),
            plainModel to ModelConfig(nativeTools = false, inputMedia = emptySet()),
        ),
    )

    override fun models(server: FakeModelServer) {
        server.enqueue(fakeResponse { body(listing().toString()) }, "/v1/models")
    }

    /** A listing of [model] and [plainModel] as the Models API gives it. */
    public open fun listing(): JsonObject = buildJsonObject {
        putJsonArray("data") {
            for ((id, image) in listOf(model to true, plainModel to false)) {
                add(
                    buildJsonObject {
                        put("type", "model")
                        put("id", id)
                        put("display_name", "Model $id")
                        put("max_input_tokens", 200000)
                        put("max_tokens", 64000)
                        putJsonObject("capabilities") {
                            putJsonObject("image_input") { put("supported", image) }
                        }
                    },
                )
            }
        }
        put("has_more", false)
        put("first_id", model)
        put("last_id", plainModel)
    }

    override fun text(chunks: List<String>): FakeResponse = sse(*textEvents(chunks))

    override fun textStart(chunk: String): FakeResponse =
        sse(messageStart(), blockStart(0, textBlock()), textDelta(0, chunk))

    override fun reasoning(reasoning: List<String>, text: List<String>): FakeResponse = sse(
        messageStart(),
        blockStart(0, thinkingBlock()),
        *reasoning.map { thinkingDelta(0, it) }.toTypedArray(),
        signatureDelta(0, "sig-0"),
        blockStop(0),
        blockStart(1, textBlock()),
        *text.map { textDelta(1, it) }.toTypedArray(),
        blockStop(1),
        messageDelta("end_turn"),
        messageStop(),
    )

    override fun toolCalls(calls: List<ScriptedToolCall>): FakeResponse {
        val events = calls.withIndex().flatMap { (index, call) ->
            listOf(blockStart(index, toolUseBlock(call.id, call.wireName))) +
                call.arguments.map { inputDelta(index, it) } + blockStop(index)
        }
        return sse(messageStart(), *events.toTypedArray(), messageDelta("tool_use"), messageStop())
    }

    override fun finish(text: String, raw: String): FakeResponse = sse(*textEvents(listOf(text), raw))

    override fun blankAtOutputLimit(): FakeResponse? = sse(messageStart(), messageDelta("max_tokens"), messageStop())

    override fun usage(text: String, usage: Usage): FakeResponse {
        val read = usage.cacheReadTokens ?: 0
        val write = usage.cacheWriteTokens ?: 0
        val start = messagesUsage(usage.inputTokens!! - read - write, read, write, 1)
        return sse(
            messageStart(start),
            blockStart(0, textBlock()),
            textDelta(0, text),
            blockStop(0),
            messageDelta("end_turn", outputUsage(usage.outputTokens!!, usage.reasoningTokens)),
            messageStop(),
        )
    }

    override fun rateLimited(retryAfterSeconds: Int, requestId: String): FakeResponse = FakeResponse.builder(429)
        .header("retry-after", "$retryAfterSeconds")
        .header("request-id", requestId)
        .body(messagesError("rate_limit_error", "Number of request tokens has exceeded your rate limit."))
        .build()

    override fun serverError(): FakeResponse =
        FakeResponse.builder(500).body(messagesError("api_error", "Internal server error")).build()

    override fun toolResults(request: RecordedRequest): List<Pair<String, String>> =
        request.messageList().filter { it["role"]?.jsonPrimitive?.content == "user" }
            .flatMap { it["content"]!!.jsonArray.map(JsonElement::jsonObject) }
            .filter { it["type"]?.jsonPrimitive?.content == "tool_result" }
            .map { result ->
                val text = result["content"]?.jsonArray.orEmpty().joinToString("") {
                    (it.jsonObject["text"] as? JsonPrimitive)?.content.orEmpty()
                }
                result["tool_use_id"]!!.jsonPrimitive.content to text
            }
}
