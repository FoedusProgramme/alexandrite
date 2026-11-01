package org.unitedsoftware.alexandrite.provider.lmstudio

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.events
import org.unitedsoftware.alexandrite.provider.common.read
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptRules
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talks to an LM Studio server with the `ALEXANDRITE_LIVE_LMSTUDIO_*` variables, and skips without them. */
class LmStudioLiveTest {
    private val apiKey: String? = System.getenv("ALEXANDRITE_LIVE_LMSTUDIO_API_KEY")
    private val model: String? = System.getenv("ALEXANDRITE_LIVE_LMSTUDIO_MODEL")
    private val baseUrl: String? = System.getenv("ALEXANDRITE_LIVE_LMSTUDIO_BASE_URL")
    private val turn = testTurn()

    private fun live(): ChatEndpoint {
        assumeTrue(model != null, "No live LM Studio model is configured.")
        val config = EndpointSerializer.read(
            buildJsonObject {
                apiKey?.let { put("apiKey", it) }
                baseUrl?.let { put("baseUrl", it) }
            },
        )
        return ChatEndpoint(EndpointId("live"), config.settings, JdkHttpEngine(), LMSTUDIO_FLAVOR)
    }

    private fun request(history: List<TranscriptEntry>, round: Int, block: ModelRequest.Builder.() -> Unit) =
        ModelRequest.builder(
            ModelRef(EndpointId("live"), model!!),
            history,
            0,
            RequestIds(turn.conversation, turn.id, round),
        )
            .options(ModelOptions.builder().maxOutputTokens(2000).build())
            .apply(block)
            .build()

    @Test
    fun `LM Studio lists its models and streams a reply`() = runBlocking {
        live().use { endpoint ->
            val info = endpoint.models().first { it.id == model }
            println("Model: $info")
            val events = endpoint.events(request(listOf(testUserEntry(turn, "Say the word 'ready'.")), 0) {})
            val completed = events.last() as ModelEvent.Completed
            println("Reply: ${completed.message.parts}, ${completed.finish}, ${completed.usage}")
            assertTrue(completed.message.parts.any { it is TextPart })
        }
    }

    @Test
    fun `LM Studio calls a tool and takes its result`() = runBlocking {
        live().use { endpoint ->
            val tool = ToolDefinition("clock.now", "Gives the current time.", schema(), ToolRisk.READ_ONLY)
            val history = listOf(testUserEntry(turn, "What time is it? Use the tool."))
            val first = endpoint.events(request(history, 0) { tools(listOf(tool)) })
            val assistant = (first.last() as ModelEvent.Completed).message
            val calls = assistant.parts.filterIsInstance<ToolCallPart>()
            assertEquals("clock.now", calls.first().name)
            val results = calls.map {
                ToolResultEntry(null, it.id, it.name, listOf(TextPart("12:00")), ToolOutcome.Succeeded)
            }
            val next = history + assistant + results
            assertEquals(emptyList(), TranscriptRules.check(next))

            val second = endpoint.events(request(next, 1) { tools(listOf(tool)) })

            println("Reply: ${(second.last() as ModelEvent.Completed).message.parts}")
        }
    }

    private fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        putJsonArray("required") {}
    }
}
