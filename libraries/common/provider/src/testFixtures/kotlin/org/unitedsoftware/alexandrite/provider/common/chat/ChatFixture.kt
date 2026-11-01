package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import org.unitedsoftware.alexandrite.provider.common.TimeoutConfig
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.WireHeaders
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

public const val FIXTURE_API_KEY: String = "sk-fixture-0123456789"

/** Timeouts that let a test fail fast. */
public val FIXTURE_TIMEOUTS: TimeoutConfig = TimeoutConfig(connectSeconds = 2, firstByteSeconds = 5, idleSeconds = 5)

/** An endpoint `test` of [flavor] at [server], with the models [models] configured. */
public fun chatEndpoint(
    server: FakeModelServer,
    flavor: ChatFlavor,
    models: Map<String, ModelConfig> = emptyMap(),
    discover: Boolean = true,
    promptCacheKey: Boolean = false,
    turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    authHeader: String = "authorization",
): ChatEndpoint {
    val settings = EndpointSettings(
        baseUrl = "${server.baseUrl}/v1",
        apiKey = Secret(FIXTURE_API_KEY),
        models = models,
        discover = discover,
        turnContext = turnContext,
        timeouts = FIXTURE_TIMEOUTS,
        wire = WireHeaders(authHeader),
    )
    return ChatEndpoint(EndpointId("test"), settings, JdkHttpEngine(), flavor, ChatApiSettings(promptCacheKey))
}

/** The events of [request]'s response. */
public suspend fun ChatEndpoint.events(request: ModelRequest): List<ModelEvent> = stream(request).toList()

/** An event stream of [chunks], each a JSON chunk, that ends with `[DONE]` unless [done] is false. */
public fun chunks(vararg chunks: JsonObject, done: Boolean = true): FakeResponse = fakeResponse {
    chunks.forEach { event(it.toString()) }
    if (done) event("[DONE]")
}

/** A chunk whose first choice has [delta] and [finish]. */
public fun chunk(finish: String? = null, delta: JsonObject.() -> JsonObject = { this }): JsonObject = buildJsonObject {
    put("id", "resp-1")
    put("object", "chat.completion.chunk")
    put("model", "chat-model")
    putJsonArray("choices") {
        add(
            buildJsonObject {
                put("index", 0)
                put("delta", JsonObject(emptyMap()).delta())
                put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
    }
}

/** This object with the field [name] set to [value], a string, a number or JSON. */
public fun JsonObject.with(name: String, value: Any): JsonObject = JsonObject(
    this + (
        name to when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is JsonElement -> value
            else -> error("No JSON for $value")
        }
        ),
)

/** A chunk that carries [usage] and no choice. */
public fun usageChunk(usage: JsonObject): JsonObject = buildJsonObject {
    put("id", "resp-1")
    putJsonArray("choices") {}
    put("usage", usage)
}

/** A usage object as the standard API counts it. */
public fun openAiUsage(input: Long, cached: Long, output: Long, reasoning: Long): JsonObject = buildJsonObject {
    put("prompt_tokens", input)
    put("completion_tokens", output)
    put("total_tokens", input + output)
    putJsonObject("prompt_tokens_details") { put("cached_tokens", cached) }
    putJsonObject("completion_tokens_details") { put("reasoning_tokens", reasoning) }
}

/** The messages a recorded chat request sends. */
public fun RecordedRequest.messages(): List<JsonObject> = json()["messages"]!!.jsonArray.map { it.jsonObject }

/**
 * The provider checks' fixture of an endpoint of [flavor], with a model that takes tools and images and one that
 * takes neither.
 */
public open class ChatFixture(private val flavor: ChatFlavor) : ProviderFixture {
    override val model: String = "chat-model"

    override val plainModel: String = "plain-model"

    /** The path the endpoint lists its models at. */
    public open val listingPath: String = "/v1/models"

    override fun endpoint(server: FakeModelServer): ChatEndpoint = chatEndpoint(
        server,
        flavor,
        mapOf(
            model to ModelConfig(nativeTools = true, parallelToolCalls = true, inputMedia = setOf("image")),
            plainModel to ModelConfig(nativeTools = false, inputMedia = emptySet()),
        ),
    )

    override fun models(server: FakeModelServer) {
        server.enqueue(fakeResponse { body(listing().toString()) }, listingPath)
    }

    /** A listing of [model] and [plainModel]. */
    public open fun listing(): JsonObject = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            for (id in listOf(model, plainModel)) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("object", "model")
                        put("max_model_len", 32768)
                    },
                )
            }
        }
    }

    /** A delta that streams [piece] of reasoning. */
    public open fun reasoningDelta(piece: String): JsonObject = JsonObject(emptyMap()).with("reasoning_content", piece)

    /** The chunks that end the reasoning before the text starts. */
    public open fun reasoningEnd(): List<JsonObject> = emptyList()

    /** The usage object that reports [usage]. */
    public open fun usageObject(usage: Usage): JsonObject =
        openAiUsage(usage.inputTokens!!, usage.cacheReadTokens!!, usage.outputTokens!!, usage.reasoningTokens!!)

    override fun text(chunks: List<String>): FakeResponse = chunks(
        chunk { with("role", "assistant").with("content", "") },
        *chunks.map { text -> chunk { with("content", text) } }.toTypedArray(),
        chunk("stop"),
        usageChunk(openAiUsage(10, 0, 5, 0)),
    )

    override fun textStart(chunk: String): FakeResponse = chunks(
        chunk { with("role", "assistant") },
        chunk { with("content", chunk) },
        done = false,
    )

    override fun reasoning(reasoning: List<String>, text: List<String>): FakeResponse = chunks(
        *reasoning.map { piece -> chunk { reasoningDelta(piece) } }.toTypedArray(),
        *reasoningEnd().toTypedArray(),
        *text.map { piece -> chunk { with("content", piece) } }.toTypedArray(),
        chunk("stop"),
    )

    override fun toolCalls(calls: List<ScriptedToolCall>): FakeResponse {
        val events = calls.withIndex().flatMap { (index, call) ->
            val opening = chunk {
                with(
                    "tool_calls",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", index)
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.wireName)
                                    put("arguments", "")
                                }
                            },
                        )
                    },
                )
            }
            listOf(opening) + call.arguments.map { fragment ->
                chunk {
                    with(
                        "tool_calls",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", index)
                                    putJsonObject("function") { put("arguments", fragment) }
                                },
                            )
                        },
                    )
                }
            }
        }
        return chunks(*events.toTypedArray(), chunk("tool_calls"))
    }

    override fun finish(text: String, raw: String): FakeResponse = chunks(chunk { with("content", text) }, chunk(raw))

    override fun blankAtOutputLimit(): FakeResponse? = chunks(
        chunk { with("role", "assistant").with("content", "") },
        chunk("length"),
        usageChunk(openAiUsage(10, 0, 5, 0)),
    )

    override fun usage(text: String, usage: Usage): FakeResponse =
        chunks(chunk { with("content", text) }, chunk("stop"), usageChunk(usageObject(usage)))

    override fun rateLimited(retryAfterSeconds: Int, requestId: String): FakeResponse = FakeResponse.builder(429)
        .header("retry-after", "$retryAfterSeconds")
        .header("x-request-id", requestId)
        .body("""{"error":{"message":"Rate limit reached.","type":"rate_limit_error","code":429}}""")
        .build()

    override fun serverError(): FakeResponse = FakeResponse.builder(
        500,
    ).body("""{"error":{"message":"The server had an error.","type":"server_error"}}""").build()

    override fun toolResults(request: RecordedRequest): List<Pair<String, String>> =
        request.messages().filter { it["role"]?.jsonPrimitive?.content == "tool" }
            .map { it["tool_call_id"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content }
}
