package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The words of a turn go to the reply of that turn, and to nothing else.
 *
 * What this keeps out is a response of a turn this instance does not speak for: a delegated turn reaches the same hook
 * but never opened a reply here, so nothing is said to the chat about it.
 */
class OneBotReplyRegistryTest {
    private val registry = OneBotReplyRegistry()
    private val first = TurnId("t-1")
    private val other = TurnId("t-2")

    @Test
    fun `the reply of a turn is the one that opened it`() {
        val sink = Sink()

        registry.open(first, sink)

        assertSame(sink, registry.of(first))
        assertNull(registry.of(other), "a turn that opened no reply here has none, such as a delegated one")
    }

    @Test
    fun `a reply that closed is not found again`() {
        val sink = Sink()
        registry.open(first, sink)

        registry.close(first)

        assertNull(registry.of(first))
    }

    @Test
    fun `a reply opened later is the one a turn is told through`() {
        val earlier = Sink()
        val later = Sink()
        registry.open(first, earlier)

        registry.open(first, later)

        assertSame(later, registry.of(first))
    }

    private class Sink : OneBotWords {
        val shown = ArrayList<AssistantEntry>()

        override suspend fun showResponse(message: AssistantEntry) {
            shown += message
        }
    }
}
