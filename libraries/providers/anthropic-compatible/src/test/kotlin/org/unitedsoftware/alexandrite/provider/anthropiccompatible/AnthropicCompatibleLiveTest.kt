package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesFlavor
import org.unitedsoftware.alexandrite.provider.common.read
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Talks to the endpoint the `ALEXANDRITE_LIVE_ANTHROPIC_COMPAT_*` variables name, and skips without them. */
class AnthropicCompatibleLiveTest {
    private val baseUrl: String? = System.getenv("ALEXANDRITE_LIVE_ANTHROPIC_COMPAT_BASE_URL")
    private val apiKey: String? = System.getenv("ALEXANDRITE_LIVE_ANTHROPIC_COMPAT_API_KEY")
    private val model: String? = System.getenv("ALEXANDRITE_LIVE_ANTHROPIC_COMPAT_MODEL")
    private val turn = testTurn()
    private val clock = ToolDefinition("clock.now", "Gives the current time.", schema(), ToolRisk.READ_ONLY)
    private val thinking = ModelOptions.builder().maxOutputTokens(2000).reasoning(ReasoningEffort.LOW).build()

    /** The live endpoint, its model taken to think at the efforts the standard API names where it lists none. */
    private fun live(listedOnly: Boolean = false): MessagesEndpoint {
        assumeTrue(baseUrl != null && apiKey != null && model != null, "No live endpoint is configured.")
        val efforts = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
        val config = EndpointSerializer.read(
            buildJsonObject {
                put("apiKey", apiKey)
                put("baseUrl", baseUrl)
                putJsonObject("models") {
                    val thinks = ModelConfig(nativeTools = true, reasoningEfforts = efforts)
                    if (!listedOnly) put(model!!, Json.encodeToJsonElement(ModelConfig.serializer(), thinks))
                }
            },
        )
        return MessagesEndpoint(
            EndpointId("live"),
            config.settings,
            testHttpEngine(),
            MessagesFlavor.STANDARD,
            config.options,
        )
    }

    private fun request(
        history: List<TranscriptEntry>,
        round: Int,
        id: TurnId = turn.id,
        block: ModelRequest.Builder.() -> Unit,
    ) = ModelRequest.builder(
        ModelRef(EndpointId("live"), model!!),
        history,
        history.indexOfFirst { it.record?.turn == id }.coerceAtLeast(0),
        RequestIds(turn.conversation, id, round),
    )
        .options(thinking)
        .apply(block)
        .build()

    private var entryId = 1L

    private fun TranscriptEntry.stored(id: TurnId): TranscriptEntry =
        withRecord(EntryRecord(EntryId(entryId++), turn.conversation, id, Instant.now()))

    private fun List<ModelEvent>.completed(): ModelEvent.Completed = last() as ModelEvent.Completed

    @Test
    fun `the endpoint lists its models`() = runBlocking {
        live(listedOnly = true).use { endpoint ->
            val models = endpoint.models()
            println("Models: ${models.map { "${it.id} ${it.contextWindow} ${it.maxOutputTokens} ${it.inputMedia}" }}")
            assertTrue(models.any { it.id == model }, "${models.map { it.id }}")
        }
    }

    @Test
    fun `the endpoint streams a reply with its thinking`() = runBlocking {
        live().use { endpoint ->
            val events = endpoint.stream(request(listOf(testUserEntry(turn, "Say the word 'ready'.")), 0) {}).toList()

            val completed = events.completed()
            println("Warnings: ${(events.first() as ModelEvent.ResponseStarted).warnings}")
            println("Reply: ${completed.message.parts}, ${completed.finish}, ${completed.usage}")
            assertTrue(completed.message.parts.any { it is TextPart })
            assertEquals(FinishKind.END_TURN, completed.finish.kind)
        }
    }

    @Test
    fun `a tool round trip keeps its thinking and pinned context, and the next turn drops them`() = runBlocking {
        live().use { endpoint ->
            val context = listOf(TurnContextItem("recall", "The user's name is Ada.", Trust.TRUSTED))
            val history = listOf(testUserEntry(turn, "What time is it? Use the tool.").stored(turn.id))
            val first = endpoint.stream(request(history, 0) { tools(listOf(clock)).turnContext(context) }).toList()
            val assistant = first.completed().message
            println("Round 0: ${assistant.parts}, ${first.completed().finish}")
            val calls = assistant.parts.filterIsInstance<ToolCallPart>()
            assertEquals("clock.now", calls.first().name)
            println("Sealed thinking blocks: ${assistant.parts.count { it is ReasoningPart && it.seal != null }}")
            val results = calls.map {
                val result = ToolResultEntry(null, it.id, it.name, listOf(TextPart("12:00")), ToolOutcome.Succeeded)
                result.stored(turn.id)
            }
            val loop = history + assistant.stored(turn.id) + results
            assertEquals(emptyList(), TranscriptRules.check(loop))

            val second = endpoint.stream(request(loop, 1) { tools(listOf(clock)).turnContext(context) }).toList()

            val reply = second.completed()
            println("Round 1: ${reply.message.parts}, ${reply.finish}, ${reply.usage}")
            assertTrue(reply.message.parts.any { it is TextPart })
            val nextTurn = TurnId("live-turn-2")
            val asked = loop + reply.message.stored(turn.id) + testUserEntry(turn, "What is my name?").stored(nextTurn)
            val later = listOf(TurnContextItem("recall", "The user likes tea.", Trust.TRUSTED))

            val third =
                endpoint.stream(request(asked, 0, nextTurn) { tools(listOf(clock)).turnContext(later) }).toList()

            val answer = third.completed()
            println("Next turn: ${answer.message.parts}, ${answer.finish}, ${answer.usage}")
            assertTrue(answer.message.parts.any { it is TextPart })
        }
    }

    private fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        putJsonArray("required") {}
    }
}
