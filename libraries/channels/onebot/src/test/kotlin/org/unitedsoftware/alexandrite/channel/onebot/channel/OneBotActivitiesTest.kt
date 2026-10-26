package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * What waits to be told is kept by the chat, so every turn working in one chat tells it once.
 *
 * A run moved to the background opens a reply of its own for the same chat, and the runtime hands each reply only its
 * own turn's activity: a chat would hear one stretch of work once per reply without this.
 */
class OneBotActivitiesTest {
    private val activities = OneBotActivities()

    private fun chat(group: String): ChatAddress =
        ChatAddress(ChannelInstanceId(ChannelType("onebot"), "main"), "group:$group")

    @Test
    fun `turns of one chat share what waits to be told`() {
        assertSame(activities.of(chat("1")), activities.of(chat("1")))
    }

    @Test
    fun `another chat waits on its own`() {
        assertNotSame(activities.of(chat("1")), activities.of(chat("2")))
    }

    @Test
    fun `a chat that holds nothing is forgotten`() {
        val first = activities.of(chat("1"))
        activities.forget(chat("1"))

        assertNotSame(first, activities.of(chat("1")), "a chat that holds nothing was kept")
    }

    @Test
    fun `a chat a call is still running in is kept`() {
        val first = activities.of(chat("1"))
        first.started(ToolCallId("c1"), ToolActivity.READ, null)
        activities.forget(chat("1"))

        assertSame(first, activities.of(chat("1")), "a chat a call runs in was forgotten")
    }
}
