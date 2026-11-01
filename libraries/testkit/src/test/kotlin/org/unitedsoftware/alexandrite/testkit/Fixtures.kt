package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TestIndex(
    id: String,
    private val bindings: List<Binding<*>> = emptyList(),
    private val sections: List<ConfigSectionSpec<*>> = emptyList(),
) : PluginIndex {
    override val info = PluginInfo(id, id, "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot = PluginIds.thirdPartyRoot(id)

    override fun bindings() = bindings

    override fun configSections() = sections
}

fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10.seconds) { block() } }

/** A request of round [round] of the test turn to [model]'s first model, offering the tool `notes.add`. */
fun request(
    model: ScriptedModel,
    round: Int = 0,
    history: List<TranscriptEntry> = listOf(testUserEntry()),
    turn: TurnInfo = testTurn(),
): ModelRequest = ModelRequest.builder(model.ref, history, 0, RequestIds(turn.conversation, turn.id, round))
    .tools(listOf(testToolDefinition("notes.add")))
    .build()

fun ScriptedModel.collect(request: ModelRequest): List<ModelEvent> = blocking { stream(request).toList() }

/** Asserts that [events] keep the stream contract of a response that completed. */
fun assertStreamContract(events: List<ModelEvent>) {
    assertIs<ModelEvent.ResponseStarted>(events.first(), "$events")
    val completed = assertIs<ModelEvent.Completed>(events.last(), "$events")
    val parts = mutableListOf<AssistantPart>()
    for (event in events.drop(1).dropLast(1)) {
        when (event) {
            is ModelEvent.PartCompleted -> {
                assertEquals(parts.size, event.index, "parts complete in index order: $events")
                parts += event.part
            }

            is ModelEvent.TextDelta -> assertEquals(parts.size, event.index, "a delta previews the open part")

            is ModelEvent.ReasoningDelta -> assertEquals(parts.size, event.index, "a delta previews the open part")

            is ModelEvent.ReasoningSealed -> assertEquals(parts.size, event.index, "a seal belongs to the open part")

            is ModelEvent.ToolCallStarted -> assertEquals(parts.size, event.index, "a call starts the open part")

            is ModelEvent.ToolArgumentsDelta -> assertEquals(parts.size, event.index, "a delta previews the open part")

            is ModelEvent.UsageUpdated -> Unit

            else -> throw AssertionError("Unexpected event $event in $events")
        }
    }
    assertEquals(parts, completed.message.parts, "the completed parts make the message")
    assertTrue(completed.message.record == null)
}
