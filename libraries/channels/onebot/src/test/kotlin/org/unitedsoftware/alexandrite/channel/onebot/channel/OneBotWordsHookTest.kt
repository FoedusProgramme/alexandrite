package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
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

/**
 * The words of a response reach the reply of the turn that asked for it, and nothing else.
 *
 * This is the half [OneBotReplyRegistryTest] cannot show: that a response is handed over as a whole, once, at the
 * moment the model finished saying it, which is the point the runtime fires as the request ends.
 */
@OptIn(InternalAlexandriteApi::class)
class OneBotWordsHookTest {
    private val replies = OneBotReplyRegistry()
    private val hook = OneBotWordsHook(replies)
    private val turn = testTurn()

    @Test
    fun `a response goes whole to the reply of its own turn`() = runBlocking {
        val sink = Sink()
        replies.open(turn.id, sink)

        hook.intercept(ModelReply(turn, 0, response("Let me check.")))

        assertEquals(listOf("Let me check."), sink.shown)
    }

    @Test
    fun `the response of a turn this instance opened no reply for goes nowhere`() = runBlocking {
        val sink = Sink()
        replies.open(TurnId("t-another"), sink)

        hook.intercept(ModelReply(turn, 0, response("Nobody asked.")))

        assertEquals(emptyList(), sink.shown, "a delegated turn reaches this point too, and speaks to no chat")
    }

    @Test
    fun `a response after the reply closed goes nowhere`() = runBlocking {
        val sink = Sink()
        replies.open(turn.id, sink)
        replies.close(turn.id)

        hook.intercept(ModelReply(turn, 0, response("Too late.")))

        assertEquals(emptyList(), sink.shown)
    }

    @Test
    fun `the chain goes on, since this hook only watches`() = runBlocking {
        val payload = ModelReply(turn, 0, response("Hi."))

        assertEquals(HookDecision.Continue, hook.intercept(payload))
    }

    @Test
    fun `a response that calls no tool is not told, since the answer carries it`() = runBlocking {
        val sink = Sink()
        replies.open(turn.id, sink)

        hook.intercept(ModelReply(turn, 0, response("Done.", callsTool = false)))

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

    private class Sink : OneBotWords {
        val shown = ArrayList<String>()

        override suspend fun showResponse(message: AssistantEntry) {
            shown += message.parts.filterIsInstance<TextPart>().joinToString("\n\n") { it.text }
        }
    }
}
