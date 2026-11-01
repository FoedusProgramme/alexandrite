package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
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

/** An endpoint `test` of [flavor] at [server]`/v1`, with the models [models] configured. */
public fun responsesEndpoint(
    server: FakeModelServer,
    flavor: ResponsesFlavor = ResponsesFlavor.STANDARD,
    models: Map<String, ModelConfig> = emptyMap(),
    discover: Boolean = true,
    turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    responses: ResponsesSettings = ResponsesSettings(),
): ResponsesEndpoint {
    val settings = EndpointSettings(
        baseUrl = "${server.baseUrl}/v1",
        apiKey = Secret(FIXTURE_API_KEY),
        models = models,
        discover = discover,
        turnContext = turnContext,
        timeouts = FIXTURE_TIMEOUTS,
    )
    return ResponsesEndpoint(EndpointId("test"), settings, JdkHttpEngine(), flavor, responses)
}

/** The events of [request]'s response. */
public suspend fun ResponsesEndpoint.events(request: ModelRequest): List<ModelEvent> = stream(request).toList()

/** An event stream of [events], each sent under its own `type`. */
public fun sse(vararg events: JsonObject): FakeResponse = fakeResponse {
    events.forEach { event(it.toString(), it["type"]!!.jsonPrimitive.content) }
}

public fun responseEvent(type: String, fields: Map<String, Any?> = emptyMap()): JsonObject = buildJsonObject {
    put("type", type)
    for ((name, value) in fields) {
        when (value) {
            null -> put(name, null as String?)
            is String -> put(name, value)
            is Number -> put(name, value)
            is Boolean -> put(name, value)
            is JsonObject -> put(name, value)
            is JsonArray -> put(name, value)
            else -> error("No JSON for $value")
        }
    }
}

/** A response object of [status], with [output] and [usage]. */
public fun responseObject(
    status: String = "in_progress",
    output: List<JsonObject> = emptyList(),
    usage: JsonObject? = null,
    incomplete: String? = null,
): JsonObject = buildJsonObject {
    put("id", "resp_1")
    put("object", "response")
    put("status", status)
    put("model", "gpt-model")
    put("output", JsonArray(output))
    put("error", null as String?)
    incomplete?.let { putJsonObject("incomplete_details") { put("reason", it) } }
    usage?.let { put("usage", it) }
}

/** A usage object as the standard API counts it, whose `input_tokens` include the [cached] ones. */
public fun responsesUsage(input: Long, cached: Long, output: Long, reasoning: Long): JsonObject = buildJsonObject {
    put("input_tokens", input)
    putJsonObject("input_tokens_details") { put("cached_tokens", cached) }
    put("output_tokens", output)
    putJsonObject("output_tokens_details") { put("reasoning_tokens", reasoning) }
    put("total_tokens", input + output)
}

public fun created(): JsonObject = responseEvent("response.created", mapOf("response" to responseObject()))

public fun completed(usage: JsonObject? = responsesUsage(10, 0, 5, 0)): JsonObject =
    responseEvent("response.completed", mapOf("response" to responseObject("completed", usage = usage)))

public fun incomplete(reason: String, usage: JsonObject? = responsesUsage(10, 0, 5, 0)): JsonObject = responseEvent(
    "response.incomplete",
    mapOf("response" to responseObject("incomplete", usage = usage, incomplete = reason)),
)

public fun itemAdded(index: Int, item: JsonObject): JsonObject =
    responseEvent("response.output_item.added", mapOf("output_index" to index, "item" to item))

public fun itemDone(index: Int, item: JsonObject): JsonObject =
    responseEvent("response.output_item.done", mapOf("output_index" to index, "item" to item))

/** A message item that holds [text], none while it streams. */
public fun messageItem(id: String, text: String? = null): JsonObject = buildJsonObject {
    put("id", id)
    put("type", "message")
    put("role", "assistant")
    put("status", if (text == null) "in_progress" else "completed")
    putJsonArray("content") {
        if (text != null) {
            add(
                buildJsonObject {
                    put("type", "output_text")
                    put("text", text)
                    putJsonArray("annotations") {}
                },
            )
        }
    }
}

public fun functionCallItem(id: String, callId: String, name: String, arguments: String = ""): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "function_call")
        put("call_id", callId)
        put("name", name)
        put("arguments", arguments)
    }

/** A reasoning item with [summary] and [content] texts and [encrypted] reasoning. */
public fun reasoningItem(
    id: String,
    encrypted: String? = null,
    summary: List<String> = emptyList(),
    content: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("id", id)
    put("type", "reasoning")
    putJsonArray("summary") {
        summary.forEach {
            add(
                buildJsonObject {
                    put("type", "summary_text")
                    put("text", it)
                },
            )
        }
    }
    if (content.isNotEmpty()) {
        putJsonArray("content") {
            content.forEach {
                add(
                    buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", it)
                    },
                )
            }
        }
    }
    encrypted?.let { put("encrypted_content", it) }
}

public fun textDelta(index: Int, delta: String): JsonObject = responseEvent(
    "response.output_text.delta",
    mapOf("item_id" to "msg_$index", "output_index" to index, "content_index" to 0, "delta" to delta),
)

public fun argumentsDelta(index: Int, delta: String): JsonObject = responseEvent(
    "response.function_call_arguments.delta",
    mapOf("item_id" to "fc_$index", "output_index" to index, "delta" to delta),
)

public fun summaryDelta(index: Int, summaryIndex: Int, delta: String): JsonObject = responseEvent(
    "response.reasoning_summary_text.delta",
    mapOf("item_id" to "rs_$index", "output_index" to index, "summary_index" to summaryIndex, "delta" to delta),
)

public fun reasoningTextDelta(index: Int, delta: String): JsonObject = responseEvent(
    "response.reasoning_text.delta",
    mapOf("item_id" to "rs_$index", "output_index" to index, "content_index" to 0, "delta" to delta),
)

/** The events of a text reply streamed in [chunks], ended by [end]. */
public fun textEvents(chunks: List<String>, end: JsonObject = completed()): Array<JsonObject> = arrayOf(
    created(),
    itemAdded(0, messageItem("msg_0")),
    *chunks.map { textDelta(0, it) }.toTypedArray(),
    itemDone(0, messageItem("msg_0", chunks.joinToString(""))),
    end,
)

/** An error body of [type] and [code] as the standard API sends it. */
public fun responsesError(type: String, code: String?, message: String): String = buildJsonObject {
    putJsonObject("error") {
        put("message", message)
        put("type", type)
        put("param", null as String?)
        put("code", code)
    }
}.toString()

/** The input items a recorded request sends. */
public fun RecordedRequest.inputItems(): List<JsonObject> = json()["input"]!!.jsonArray.map { it.jsonObject }

/**
 * The provider checks' fixture of an endpoint of [flavor], with a model that takes tools and images and one that
 * takes neither.
 */
public open class ResponsesFixture(private val flavor: ResponsesFlavor = ResponsesFlavor.STANDARD) : ProviderFixture {
    override val model: String = "gpt-model"

    override val plainModel: String = "plain-model"

    override fun endpoint(server: FakeModelServer): ResponsesEndpoint = responsesEndpoint(
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
        put("object", "list")
        putJsonArray("data") {
            for (id in listOf(model, plainModel)) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("object", "model")
                        put("created", 1700000000)
                        put("owned_by", "system")
                    },
                )
            }
        }
    }

    override fun text(chunks: List<String>): FakeResponse = sse(*textEvents(chunks))

    override fun textStart(chunk: String): FakeResponse =
        sse(created(), itemAdded(0, messageItem("msg_0")), textDelta(0, chunk))

    override fun reasoning(reasoning: List<String>, text: List<String>): FakeResponse = sse(
        created(),
        itemAdded(0, reasoningItem("rs_0")),
        *reasoning.map { reasoningTextDelta(0, it) }.toTypedArray(),
        itemDone(0, reasoningItem("rs_0", "enc-0", content = listOf(reasoning.joinToString("")))),
        itemAdded(1, messageItem("msg_1")),
        *text.map { textDelta(1, it) }.toTypedArray(),
        itemDone(1, messageItem("msg_1", text.joinToString(""))),
        completed(),
    )

    override fun toolCalls(calls: List<ScriptedToolCall>): FakeResponse {
        val events = calls.withIndex().flatMap { (index, call) ->
            listOf(itemAdded(index, functionCallItem("fc_$index", call.id, call.wireName))) +
                call.arguments.map { argumentsDelta(index, it) } +
                itemDone(index, functionCallItem("fc_$index", call.id, call.wireName, call.arguments.joinToString("")))
        }
        return sse(created(), *events.toTypedArray(), completed())
    }

    override fun finish(text: String, raw: String): FakeResponse = sse(*textEvents(listOf(text), incomplete(raw)))

    override fun blankAtOutputLimit(): FakeResponse? = sse(created(), incomplete("max_output_tokens"))

    override fun usage(text: String, usage: Usage): FakeResponse {
        val counted =
            responsesUsage(usage.inputTokens!!, usage.cacheReadTokens!!, usage.outputTokens!!, usage.reasoningTokens!!)
        return sse(*textEvents(listOf(text), completed(counted)))
    }

    override fun rateLimited(retryAfterSeconds: Int, requestId: String): FakeResponse = FakeResponse.builder(429)
        .header("retry-after", "$retryAfterSeconds")
        .header("x-request-id", requestId)
        .body(responsesError("requests", "rate_limit_exceeded", "Rate limit reached for requests."))
        .build()

    override fun serverError(): FakeResponse =
        FakeResponse.builder(500).body(responsesError("server_error", null, "The server had an error.")).build()

    override fun toolResults(request: RecordedRequest): List<Pair<String, String>> =
        request.inputItems().filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            .map { it["call_id"]!!.jsonPrimitive.content to (it["output"] as JsonPrimitive).content }
}
