package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_API_KEY
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesDiscoveryTest {
    private val server = FakeModelServer()

    @AfterTest
    fun close() {
        server.close()
    }

    private fun models(models: Map<String, ModelConfig> = emptyMap()): Map<String, ModelInfo> =
        runBlocking { messagesEndpoint(server, models = models).use { it.models() } }.associateBy { it.id }

    private fun page(more: Boolean, vararg models: JsonObject): String = buildJsonObject {
        putJsonArray("data") { models.forEach { add(it) } }
        put("has_more", more)
        put("first_id", models.first()["id"]!!)
        put("last_id", models.last()["id"]!!)
    }.toString()

    private fun JsonObjectBuilder.supported(name: String, value: Boolean) {
        putJsonObject(name) { put("supported", value) }
    }

    private fun model(id: String, capabilities: (JsonObjectBuilder.() -> Unit)? = null): JsonObject = buildJsonObject {
        put("type", "model")
        put("id", id)
        put("display_name", "Model $id")
        put("created_at", "2026-07-24T00:00:00Z")
        put("max_input_tokens", 1000000)
        put("max_tokens", 128000)
        capabilities?.let { putJsonObject("capabilities", it) }
    }

    private val thinker: JsonObjectBuilder.() -> Unit = {
        supported("image_input", true)
        putJsonObject("thinking") {
            put("supported", true)
            putJsonObject("types") {
                supported("adaptive", true)
                supported("disabled", false)
                supported("enabled", false)
            }
        }
        putJsonObject("effort") {
            put("supported", true)
            for (level in listOf("low", "medium", "high", "max")) supported(level, true)
            put("xhigh", null as String?)
        }
    }

    private val plain: JsonObjectBuilder.() -> Unit = {
        supported("image_input", false)
        putJsonObject("thinking") {
            put("supported", false)
            putJsonObject("types") {
                supported("adaptive", false)
                supported("disabled", true)
                supported("enabled", false)
            }
        }
        putJsonObject("effort") { put("supported", false) }
    }

    @Test
    fun `the listing pages by its last id and reads limits and capabilities`() {
        server.enqueue(fakeResponse { body(page(true, model("claude-big", thinker))) }, "/v1/models")
        server.enqueue(fakeResponse { body(page(false, model("claude-small", plain))) }, "/v1/models")

        val models = models()

        assertEquals(listOf("claude-big", "claude-small"), models.keys.toList())
        val big = models.getValue("claude-big")
        assertEquals("Model claude-big", big.displayName)
        assertEquals(1000000 to 128000, big.contextWindow to big.maxOutputTokens)
        assertEquals(setOf(MediaKind.IMAGE), big.inputMedia)
        assertEquals(
            setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            big.reasoningEfforts,
        )
        assertTrue(big.nativeTools && big.parallelToolCalls && big.streaming)
        assertEquals(MessagesFlavor.ANTHROPIC, big.dialect)
        val small = models.getValue("claude-small")
        assertEquals(setOf(ReasoningEffort.NONE) to emptySet(), small.reasoningEfforts to small.inputMedia)
        val (first, second) = server.requests
        assertEquals("/v1/models" to "/v1/models?after_id=claude-big", first.path to second.path)
        assertEquals(FIXTURE_API_KEY to "2023-06-01", first.header("x-api-key") to first.header("anthropic-version"))
    }

    @Test
    fun `configured token limits win over the listed ones`() {
        server.enqueue(fakeResponse { body(page(false, model("claude-big", thinker))) }, "/v1/models")

        val big = models(mapOf("claude-big" to ModelConfig(contextWindow = 200000, maxOutputTokens = 64000)))
            .getValue("claude-big")

        assertEquals(200000 to 64000, big.contextWindow to big.maxOutputTokens)
    }

    @Test
    fun `a listing without capabilities claims nothing and the operator's facts win`() {
        server.enqueue(fakeResponse { body(page(false, model("vendor-model"))) }, "/v1/models")
        val configured = mapOf(
            "vendor-model" to
                ModelConfig(
                    inputMedia = setOf("image"),
                    reasoningEfforts = setOf(ReasoningEffort.NONE, ReasoningEffort.HIGH),
                ),
            "extra-model" to ModelConfig(contextWindow = 4096, displayName = "Extra"),
        )

        val models = models(configured)

        val vendor = models.getValue("vendor-model")
        assertEquals(setOf(MediaKind.IMAGE), vendor.inputMedia)
        assertEquals(setOf(ReasoningEffort.NONE, ReasoningEffort.HIGH), vendor.reasoningEfforts)
        val extra = models.getValue("extra-model")
        assertEquals(4096 to "Extra", extra.contextWindow to extra.displayName)
        assertTrue(extra.inputMedia.isEmpty() && extra.reasoningEfforts.isEmpty() && extra.nativeTools)
    }
}
