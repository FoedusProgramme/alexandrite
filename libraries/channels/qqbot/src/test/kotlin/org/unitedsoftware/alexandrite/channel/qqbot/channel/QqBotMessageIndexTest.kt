package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QqBotMessageIndexTest {
    private val chat = ChatAddress(ChannelInstanceId(ChannelType("qqbot"), "main"), "private:u1")

    @Test
    fun `the index keeps a bounded number of references`() {
        val index = QqBotMessageIndex()
        repeat(1_100) { index.record(chat, "ref$it", "id$it") }

        assertNull(index.resolve(chat, "ref0"))
        assertEquals("id1099", index.resolve(chat, "ref1099"))
    }
}
