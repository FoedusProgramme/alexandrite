package org.unitedsoftware.alexandrite.channel.onebot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OneBotConfigTest {
    @Test
    fun `an admin is the number of an account`() {
        assertFailsWith<IllegalArgumentException> {
            instance(admins = setOf("someone"))
        }
        assertFailsWith<IllegalArgumentException> {
            instance(admins = setOf(""))
        }
        assertEquals(setOf("12345678"), instance(admins = setOf("12345678")).admins)
    }

    @Test
    fun `an allowed chat is a private or group key with a number`() {
        assertFailsWith<IllegalArgumentException> {
            instance(allowedChats = listOf("qq:12345678"))
        }
        assertFailsWith<IllegalArgumentException> {
            instance(allowedChats = listOf("private:someone"))
        }
        val chats = listOf("private:12345678", "group:987654")
        assertEquals(chats, instance(allowedChats = chats).allowedChats)
    }

    @Test
    fun `an instance without admins starts, with nobody an operator`() {
        assertEquals(emptySet(), instance(admins = emptySet()).admins)
    }

    private fun instance(
        admins: Set<String> = emptySet(),
        allowedChats: List<String> = emptyList(),
    ): OneBotInstanceConfig = OneBotInstanceConfig(
        transport = "ws",
        endpoint = "ws://127.0.0.1:3001",
        admins = admins,
        allowedChats = allowedChats,
    )
}
