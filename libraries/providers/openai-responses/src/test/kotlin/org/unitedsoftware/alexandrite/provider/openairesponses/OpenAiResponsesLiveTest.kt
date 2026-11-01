package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.read
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesEndpoint
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesFlavor
import org.unitedsoftware.alexandrite.provider.common.responses.events
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptRules
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to the OpenAI API with `OPENAI_API_KEY`, and skips without it. `OPENAI_MODEL` names a reasoning model that
 * takes low effort, `gpt-5-mini` by default.
 */
class OpenAiResponsesLiveTest {
    private val apiKey: String? = System.getenv("OPENAI_API_KEY")
    private val model: String = System.getenv("OPENAI_MODEL") ?: "gpt-5-mini"
    private val turn = testTurn()

    private fun live(): ResponsesEndpoint {
        assumeTrue(!apiKey.isNullOrBlank(), "OPENAI_API_KEY is not set.")
        val reasoning = ModelConfig(
            nativeTools = true,
            parallelToolCalls = true,
            inputMedia = setOf("image"),
            reasoningEfforts = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        )
        val config = EndpointSerializer.read(
            buildJsonObject {
                put("apiKey", apiKey)
                put("discover", false)
                putJsonObject("models") { put(model, Json.encodeToJsonElement(ModelConfig.serializer(), reasoning)) }
            },
        )
        return ResponsesEndpoint(
            EndpointId("live"),
            config.settings,
            testHttpEngine(),
            ResponsesFlavor.STANDARD,
            config.options,
        )
    }

    private fun request(history: List<TranscriptEntry>, round: Int, block: ModelRequest.Builder.() -> Unit) =
        ModelRequest.builder(
            ModelRef(EndpointId("live"), model),
            history,
            0,
            RequestIds(turn.conversation, turn.id, round),
        )
            .options(ModelOptions.builder().maxOutputTokens(2000).reasoning(ReasoningEffort.LOW).build())
            .apply(block)
            .build()

    @Test
    fun `the endpoint streams a reply`() = runBlocking {
        live().use { endpoint ->
            val events = endpoint.events(request(listOf(testUserEntry(turn, "Say the word 'ready'.")), 0) {})
            val completed = events.last() as ModelEvent.Completed
            println("Reply: ${completed.message.parts}, ${completed.finish}, ${completed.usage}")
            assertTrue(completed.message.parts.any { it is TextPart })
        }
    }

    @Test
    fun `the endpoint calls a tool and takes its result with its reasoning sent back`() = runBlocking {
        live().use { endpoint ->
            val tool = ToolDefinition("clock.now", "Gives the current time.", schema(), ToolRisk.READ_ONLY)
            val history = listOf(testUserEntry(turn, "What time is it? Use the tool."))
            val first = endpoint.events(request(history, 0) { tools(listOf(tool)).toolChoice(ToolChoice.Required) })
            val assistant = (first.last() as ModelEvent.Completed).message
            println("Reasoning sealed: ${assistant.parts.filterIsInstance<ReasoningPart>().map { it.seal != null }}")
            val calls = assistant.parts.filterIsInstance<ToolCallPart>()
            assertEquals("clock.now", calls.first().name)
            val results = calls.map {
                ToolResultEntry(null, it.id, it.name, listOf(TextPart("12:00")), ToolOutcome.Succeeded)
            }
            val next = history + assistant + results
            assertEquals(emptyList(), TranscriptRules.check(next))

            val second = endpoint.events(request(next, 1) { tools(listOf(tool)) })

            val reply = second.last() as ModelEvent.Completed
            println("Reply: ${reply.message.parts}, ${reply.usage}")
            assertTrue(reply.message.parts.any { it is TextPart })
        }
    }

    private fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        putJsonArray("required") {}
    }
}
