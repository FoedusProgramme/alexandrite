package org.unitedsoftware.alexandrite.provider.common

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_API_KEY
import org.unitedsoftware.alexandrite.provider.common.chat.chatEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelPrice
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDiscoveryTest {
    private val server = FakeModelServer()

    @AfterTest
    fun close() {
        server.close()
    }

    private fun models(
        flavor: ChatFlavor = ChatFlavor.STANDARD,
        models: Map<String, ModelConfig> = emptyMap(),
    ): Map<String, ModelInfo> =
        runBlocking { chatEndpoint(server, flavor, models).use { it.models() } }.associateBy { it.id }

    private fun listing(vararg models: JsonObject): String = buildJsonObject {
        put("object", "list")
        putJsonArray("data") { models.forEach { add(it) } }
    }.toString()

    private fun model(id: String, field: String? = null, window: Int = 0): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "model")
        field?.let { put(it, window) }
    }

    @Test
    fun `the standard listing reads the context window from whichever field the backend fills`() {
        server.enqueue(
            fakeResponse {
                body(
                    listing(
                        model("vllm", "max_model_len", 32768),
                        model("router", "context_length", 200000),
                        model("other", "context_window", 8192),
                        model("bare"),
                    ),
                )
            },
            "/v1/models",
        )

        val models = models()

        assertEquals(
            mapOf("vllm" to 32768, "router" to 200000, "other" to 8192, "bare" to null),
            models.mapValues { it.value.contextWindow },
        )
        val vllm = models.getValue("vllm")
        assertTrue(vllm.nativeTools && vllm.parallelToolCalls && vllm.streaming)
        assertEquals(ChatFlavor.OPENAI_CHAT to emptySet(), vllm.dialect to vllm.reasoningEfforts)
        assertEquals("Bearer $FIXTURE_API_KEY", server.requests.single().header("authorization"))
    }

    @Test
    fun `the operator's facts win, its context window too`() {
        server.enqueue(fakeResponse { body(listing(model("chat-model", "max_model_len", 32768))) }, "/v1/models")
        val configured = mapOf(
            "chat-model" to ModelConfig(contextWindow = 8192, parallelToolCalls = false, inputMedia = setOf("image")),
            "extra-model" to ModelConfig(contextWindow = 4096, nativeTools = false, displayName = "Extra"),
        )

        val models = models(models = configured)

        val chat = models.getValue("chat-model")
        assertEquals(8192, chat.contextWindow)
        assertFalse(chat.parallelToolCalls)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        val extra = models.getValue("extra-model")
        assertEquals(4096 to "Extra", extra.contextWindow to extra.displayName)
        assertFalse(extra.nativeTools || extra.parallelToolCalls)
    }

    @Test
    fun `a configured kind the wire API never carries is bound away`() {
        server.enqueue(fakeResponse { body(listing(model("chat-model"))) }, "/v1/models")
        val configured = mapOf("chat-model" to ModelConfig(inputMedia = setOf("image", "audio")))

        val models = models(models = configured)

        assertEquals(setOf(MediaKind.IMAGE), models.getValue("chat-model").inputMedia)
    }

    @Test
    fun `a configured price wins over the one the backend lists, and a model priced by neither has none`() {
        val listing = object : ModelListing() {
            override fun facts(model: JsonObject): ModelFacts = ModelFacts(
                id = model.string("id"),
                price = ModelPrice(BigDecimal.ONE, BigDecimal.TEN),
            )
        }
        server.enqueue(fakeResponse { body(listing(model("listed"), model("mine"))) }, "/v1/models")
        val configured = mapOf(
            "mine" to
                Json.decodeFromString<ModelConfig>("""{"price": {"input": 0.27, "output": 1.1, "cached": 0.07}}"""),
            "extra" to ModelConfig(),
        )

        val models = models(ChatFlavor(Dialect("custom"), listing), configured)

        assertEquals(ModelPrice(BigDecimal.ONE, BigDecimal.TEN), models.getValue("listed").price)
        assertEquals(
            ModelPrice(BigDecimal("0.27"), BigDecimal("1.1"), BigDecimal("0.07")),
            models.getValue("mine").price,
        )
        assertNull(models.getValue("extra").price)
        assertFailsWith<IllegalArgumentException> { PriceConfig(input = -1.0, output = 1.0) }
    }

    @Test
    fun `a listing's URLs, pages, facts and defaults decide what the endpoint reports`() {
        val listing = object : ModelListing() {
            override val defaults: ModelFacts = ModelFacts(id = null, nativeTools = true, parallelToolCalls = false)

            override fun urls(baseUrl: String): List<String> = listOf("$baseUrl/catalog", "$baseUrl/models")

            override fun next(page: JsonObject): String? = page.obj("links")?.string("next")

            override fun facts(model: JsonObject): ModelFacts =
                ModelFacts(id = model.string("id"), displayName = model.string("name"))
        }
        val first = buildJsonObject {
            putJsonArray("data") {
                add(
                    buildJsonObject {
                        put("id", "a")
                        put("name", "Model A")
                    },
                )
            }
            putJsonObject("links") { put("next", "/v1/models?page=2") }
        }
        server.enqueue(FakeResponse.builder(404).body("{}").build(), "/v1/catalog")
        server.enqueue(fakeResponse { body(first.toString()) }, "/v1/models")
        server.enqueue(fakeResponse { body(listing(model("b"))) }, "/v1/models")

        val models = models(ChatFlavor(Dialect("custom"), listing))

        assertEquals(listOf("a", "b"), models.keys.toList())
        assertEquals("Model A", models.getValue("a").displayName)
        assertTrue(models.values.all { it.nativeTools && !it.parallelToolCalls && it.dialect == Dialect("custom") })
        assertEquals("/v1/models?page=2", server.requests.last().path)
    }

    @Test
    fun `a listing that fails falls back to the configured models, or fails when there are none`() {
        server.enqueue(FakeResponse.builder(401).body("""{"error":{"message":"bad key"}}""").build(), "/v1/models")
        server.enqueue(FakeResponse.builder(401).body("""{"error":{"message":"bad key"}}""").build(), "/v1/models")

        val configured = models(models = mapOf("mine" to ModelConfig(contextWindow = 1000)))
        val error = assertFailsWith<ModelException> { models() }.error

        assertEquals(setOf("mine"), configured.keys)
        assertEquals(ModelErrorKind.AUTHENTICATION, error.kind)
        assertFalse(FIXTURE_API_KEY in error.message)
    }

    @Test
    fun `a listing that fails after one that succeeded fails, also when the config names models`() {
        server.enqueue(fakeResponse { body(listing(model("a"))) }, "/v1/models")
        server.enqueue(FakeResponse.builder(503).body("{}").build(), "/v1/models")
        chatEndpoint(server, ChatFlavor.STANDARD, mapOf("mine" to ModelConfig())).use { endpoint ->
            val first = runBlocking { endpoint.models() }
            val error = assertFailsWith<ModelException> { runBlocking { endpoint.models() } }.error

            assertEquals(listOf("a", "mine"), first.map { it.id })
            assertEquals(ModelErrorKind.OVERLOADED, error.kind)
        }
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `a listing that names no URL lists nothing`() {
        val listing = object : ModelListing() {
            override fun urls(baseUrl: String): List<String> = emptyList()
        }

        val models = models(ChatFlavor(Dialect("custom"), listing), mapOf("mine" to ModelConfig()))

        assertEquals(setOf("mine"), models.keys)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a next page on another origin or in no URL ends the listing`() {
        val listing = object : ModelListing() {
            override fun next(page: JsonObject): String? = page.string("next")
        }
        val nexts = listOf(
            "http://elsewhere.invalid/v1/models?page=2",
            "https://127.0.0.1:1/v1/models?page=2",
            "http://[bad/models",
        )
        for (next in nexts) {
            val page = buildJsonObject {
                putJsonArray("data") { add(model("a")) }
                put("next", next)
            }
            server.enqueue(fakeResponse { body(page.toString()) }, "/v1/models")
        }

        val listed = nexts.map { models(ChatFlavor(Dialect("custom"), listing)).keys.toList() }

        assertEquals(List(3) { listOf("a") }, listed)
        assertEquals(3, server.requests.size)
    }

    @Test
    fun `a listing whose own code fails falls back like a listing that fails`() {
        fun failing(part: String) = object : ModelListing() {
            override fun urls(baseUrl: String): List<String> =
                if (part == "urls") error("no URLs") else listOf("$baseUrl/models")

            override fun models(page: JsonObject): List<JsonObject> =
                if (part == "models") throw ClassCastException("no models") else super.models(page)
        }
        val parts = listOf("urls", "models")
        repeat(2) { server.enqueue(fakeResponse { body(listing(model("a"))) }, "/v1/models") }
        val configured = mapOf("mine" to ModelConfig())

        val fallbacks = parts.map { models(ChatFlavor(Dialect("custom"), failing(it)), configured).keys }
        val errors = parts.map { part ->
            assertFailsWith<ModelException> { models(ChatFlavor(Dialect("custom"), failing(part))) }.error
        }

        assertEquals(List(2) { setOf("mine") }, fallbacks)
        assertEquals(List(2) { ModelErrorKind.PROTOCOL }, errors.map { it.kind })
        assertTrue(errors.all { "cannot read its listing" in it.message }, "$errors")
    }

    @Test
    fun `a listing stops after its page limit, keeps each model once and drops ids that are empty or broken`() {
        val listing = object : ModelListing() {
            override fun next(page: JsonObject): String? = "/v1/models"
        }
        repeat(21) { index ->
            val page = listing(model("same"), model("page-$index"), model(""), model("bad\u0007id"))
            server.enqueue(fakeResponse { body(page) }, "/v1/models")
        }

        val models = models(ChatFlavor(Dialect("custom"), listing))

        assertEquals(listOf("same") + List(20) { "page-$it" }, models.keys.toList())
        assertEquals(20, server.requests.size)
    }

    @Test
    fun `a listing in no JSON object breaks the protocol`() {
        server.enqueue(fakeResponse { body("[]") }, "/v1/models")

        assertEquals(ModelErrorKind.PROTOCOL, assertFailsWith<ModelException> { models() }.error.kind)
    }

    @Test
    fun `an endpoint that does not discover asks nothing`() {
        val endpoint = chatEndpoint(server, ChatFlavor.STANDARD, mapOf("only" to ModelConfig()), discover = false)

        val models = runBlocking { endpoint.use { it.models() } }

        assertEquals(listOf("only"), models.map { it.id })
        assertTrue(server.requests.isEmpty())
    }
}
