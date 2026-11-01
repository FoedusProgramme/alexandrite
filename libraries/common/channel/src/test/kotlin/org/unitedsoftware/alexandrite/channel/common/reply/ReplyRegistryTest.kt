package org.unitedsoftware.alexandrite.channel.common.reply

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.turn.ModelReply
import org.unitedsoftware.alexandrite.testkit.TEST_MODEL
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What every channel's own reply registry and words hook share: a reply lives under the turn that opened it, and
 * [tellResponse] shows a response only once it calls a tool, since the message that ends the turn carries the rest.
 */
@OptIn(InternalAlexandriteApi::class)
class ReplyRegistryTest {
    private val registry = ReplyRegistry<Sink>()
    private val turn = testTurn()

    @Test
    fun `the reply of a turn is the one that opened it`() {
        val sink = Sink()
        registry.open(turn.id, sink)

        assertSame(sink, registry.of(turn.id))
        assertNull(registry.of(TurnId("t-other")), "a turn that opened no reply here has none, such as a delegated one")
    }

    @Test
    fun `a closed reply is forgotten`() {
        val sink = Sink()
        registry.open(turn.id, sink)
        registry.close(turn.id)

        assertNull(registry.of(turn.id))
    }

    @Test
    fun `a response that calls a tool goes whole to the reply of its own turn`() = runBlocking {
        val sink = Sink()
        registry.open(turn.id, sink)

        registry.tellResponse(ModelReply(turn, 0, response("Let me check.")))

        assertEquals(listOf("Let me check."), sink.shown)
    }

    @Test
    fun `the response of a turn this registry opened no reply for goes nowhere`() = runBlocking {
        val sink = Sink()
        registry.open(TurnId("t-another"), sink)

        registry.tellResponse(ModelReply(turn, 0, response("Nobody asked.")))

        assertEquals(emptyList(), sink.shown, "a delegated turn reaches this point too, and speaks to no chat")
    }

    @Test
    fun `a response that calls no tool is not told, since the answer carries it`() = runBlocking {
        val sink = Sink()
        registry.open(turn.id, sink)

        registry.tellResponse(ModelReply(turn, 0, response("Done.", callsTool = false)))

        assertEquals(emptyList(), sink.shown, "the message that ends the turn says it, and it is not said twice")
    }

    private fun response(text: String, callsTool: Boolean = true): ModelEvent.Completed = ModelEvent.Completed(
        AssistantEntry(
            null,
            listOf(TextPart(text)) +
                if (callsTool) listOf(ToolCallPart(ToolCallId("c1"), "fs.read", "{}")) else emptyList(),
            TEST_MODEL,
        ),
        FinishReason(FinishKind.END_TURN, null, null),
        Usage.builder().build(),
    )

    private class Sink : ReplyWords {
        val shown = ArrayList<String>()

        override suspend fun showResponse(message: AssistantEntry) {
            shown += message.parts.filterIsInstance<TextPart>().joinToString("\n\n") { it.text }
        }
    }
}
