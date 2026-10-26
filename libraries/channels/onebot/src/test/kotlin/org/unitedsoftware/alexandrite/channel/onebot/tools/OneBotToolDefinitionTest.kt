package org.unitedsoftware.alexandrite.channel.onebot.tools

import org.unitedsoftware.alexandrite.channel.onebot.channel.OneBotApis
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * How long the tools of this channel may take, which a file of any size makes long.
 *
 * A call that changes something may have to send a file of the workspace to the implementation in pieces and then
 * send it on. The timeout a turn takes by default ends such a call before it is done, and a call that ends that way
 * is one whose outcome nobody knows, which is what a file sent twice came of.
 */
class OneBotToolDefinitionTest {
    @Test
    fun `a tool that changes something may take as long as a file of the workspace can need`() {
        val timeout = assertNotNull(OneBotActTool(OneBotApis(), null).definition.timeout, "no timeout is declared")
        assertTrue(timeout >= 5.minutes, "a change may take only $timeout")
    }

    @Test
    fun `a tool that only reads needs no time of its own`() {
        assertNull(OneBotReadTool(OneBotApis(), null).definition.timeout)
    }
}
