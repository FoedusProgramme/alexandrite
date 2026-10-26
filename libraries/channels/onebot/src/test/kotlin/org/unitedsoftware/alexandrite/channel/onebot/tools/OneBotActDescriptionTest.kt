package org.unitedsoftware.alexandrite.channel.onebot.tools

import org.unitedsoftware.alexandrite.channel.onebot.channel.OneBotApis
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the tool that changes something tells a model about a call that ends without an answer.
 *
 * Such a call may have been carried out: the request can be on its way when the call is ended, and a call ended that
 * way is one nobody knows the outcome of. A model that takes it for one that did nothing sends the same thing again,
 * which is what a file that reached a group twice came of, so the tool says what to do instead.
 */
class OneBotActDescriptionTest {
    private val description: String = OneBotActTool(OneBotApis(), null).definition.description

    @Test
    fun `it says that an unanswered call may still have been carried out`() {
        assertTrue(description.contains("may still have been carried out"), description.take(240))
    }

    @Test
    fun `it says to look at what a chat holds before calling again`() {
        assertTrue(description.contains("look at what that chat or group holds"), description.take(240))
        assertTrue(description.contains("before calling again"), description.take(240))
    }

    @Test
    fun `the tool that only reads says no such thing, since reading changes nothing`() {
        val reading = OneBotReadTool(OneBotApis(), null).definition.description
        assertTrue(!reading.contains("may still have been carried out"), reading.take(240))
    }
}
