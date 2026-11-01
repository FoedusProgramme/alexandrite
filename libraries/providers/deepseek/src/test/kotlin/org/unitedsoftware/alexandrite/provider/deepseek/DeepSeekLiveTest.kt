package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.events
import org.unitedsoftware.alexandrite.provider.common.read
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
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

/** Talks to DeepSeek with the `ALEXANDRITE_LIVE_DEEPSEEK_*` variables, and skips without them. */
class DeepSeekLiveTest {
    private val apiKey: String? = System.getenv("ALEXANDRITE_LIVE_DEEPSEEK_API_KEY")
    private val model: String? = System.getenv("ALEXANDRITE_LIVE_DEEPSEEK_MODEL")
    private val baseUrl: String? = System.getenv("ALEXANDRITE_LIVE_DEEPSEEK_BASE_URL")
    private val turn = testTurn()
    private val clock = ToolDefinition("clock.now", "Gives the current time.", schema(), ToolRisk.READ_ONLY)

    private fun live(): ChatEndpoint {
        assumeTrue(apiKey != null && model != null, "No live DeepSeek key and model are configured.")
        val config = EndpointSerializer.read(
            buildJsonObject {
                put("apiKey", apiKey)
                baseUrl?.let { put("baseUrl", it) }
            },
        )
        return ChatEndpoint(EndpointId("live"), config.settings, testHttpEngine(), DEEPSEEK_FLAVOR)
    }

    private fun request(history: List<TranscriptEntry>, round: Int, block: ModelRequest.Builder.() -> Unit) =
        ModelRequest.builder(
            ModelRef(EndpointId("live"), model!!),
            history,
            0,
            RequestIds(turn.conversation, turn.id, round),
        )
            .options(ModelOptions.builder().maxOutputTokens(2000).reasoning(ReasoningEffort.LOW).build())
            .apply(block)
            .build()

    private fun List<ModelEvent>.completed(): ModelEvent.Completed = last() as ModelEvent.Completed

    private fun List<ModelEvent>.warnings(): List<String> =
        filterIsInstance<ModelEvent.ResponseStarted>().single().warnings.map { it.message }

    @Test
    fun `DeepSeek lists its models`() = runBlocking {
        live().use { endpoint ->
            val models = endpoint.models()
            println("Models: ${models.map { "${it.id} ${it.contextWindow} ${it.reasoningEfforts} ${it.inputMedia}" }}")
            val info = models.first { it.id == model }
            assertTrue(info.nativeTools && (info.contextWindow ?: 0) > 0)
            assertTrue(ReasoningEffort.NONE in info.reasoningEfforts && ReasoningEffort.HIGH in info.reasoningEfforts)
        }
    }

    @Test
    fun `DeepSeek streams a reply with its reasoning, its effort mapped onto a level it takes`() = runBlocking {
        live().use { endpoint ->
            val history = listOf(testUserEntry(turn, "Say the word 'ready'."))
            val options = ModelOptions.builder().maxOutputTokens(2000).reasoning(ReasoningEffort.MEDIUM).build()

            val events = endpoint.events(request(history, 0) { options(options) })

            val completed = events.completed()
            println("Reply: ${completed.message.parts}, ${completed.finish}, ${completed.usage}")
            assertEquals(
                listOf(
                    "Model '$model' does not accept the reasoning effort 'medium', so the request asks for 'high' " +
                        "instead.",
                ),
                events.warnings(),
            )
            assertTrue(events.any { it is ModelEvent.ReasoningDelta })
            val reasoning = completed.message.parts.filterIsInstance<ReasoningPart>().single()
            assertEquals(SealKind.PLAIN to DEEPSEEK, reasoning.seal?.kind to reasoning.seal?.dialect)
            assertTrue(completed.message.parts.any { it is TextPart })
        }
    }

    @Test
    fun `DeepSeek calls a tool while it thinks and takes the result with its reasoning back`() = runBlocking {
        live().use { endpoint ->
            val history = listOf(testUserEntry(turn, "What time is it? Use the tool."))
            val first = endpoint.events(request(history, 0) { tools(listOf(clock)) })
            val assistant = first.completed().message
            println("Round 0: ${assistant.parts}, ${first.completed().finish}")
            val calls = assistant.parts.filterIsInstance<ToolCallPart>()
            assertEquals("clock.now", calls.first().name)
            assertTrue(assistant.parts.filterIsInstance<ReasoningPart>().any { it.seal?.kind == SealKind.PLAIN })
            val results = calls.map {
                ToolResultEntry(null, it.id, it.name, listOf(TextPart("12:00")), ToolOutcome.Succeeded)
            }
            val next = history + assistant + results
            assertEquals(emptyList(), TranscriptRules.check(next))

            val second = endpoint.events(request(next, 1) { tools(listOf(clock)) })

            val reply = second.completed()
            println("Round 1: ${reply.message.parts}, ${reply.finish}, ${reply.usage}")
            assertEquals(FinishKind.END_TURN, reply.finish.kind)
            assertTrue(reply.message.parts.any { it is TextPart })
        }
    }

    private fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        putJsonArray("required") {}
    }
}
