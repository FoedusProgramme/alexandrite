package org.unitedsoftware.alexandrite.channel.common.pairing

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.testkit.testRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PairingDesksTest {
    private val clock = TestClock()
    private val chat = ChannelInstanceId(ChannelType("chat"), "work")
    private val mail = ChannelInstanceId(ChannelType("mail"), "work")
    private val first = Recorded(Pairings(clock, testRecords("chat-channel")))
    private val second = Recorded(Pairings(clock, testRecords("mail-channel")))
    private val chatPairing = runBlocking { first.pairings.open(chat, listOf(ChatAddress(chat, "7"))) }
    private val mailPairing = runBlocking { second.pairings.open(mail, emptyList()) }
    private val desks = PairingDesks(listOf(first, second))

    @Test
    fun `each code goes to the desk whose request holds it`() = runTest {
        val bob = ChatAddress(chat, "42")
        val carol = ChatAddress(mail, "43")
        val forBob = assertNotNull(chatPairing.request(bob, ChatKind.DIRECT, null, null))
        clock.advance(60)
        val forCarol = assertNotNull(mailPairing.request(carol, ChatKind.DIRECT, null, null))

        assertEquals(listOf(bob, carol), desks.pending().map { it.chat })
        assertEquals(carol, assertIs<PairingDecision.Denied>(desks.deny(forCarol.text, null)).request.chat)
        assertEquals(bob, assertIs<PairingDecision.Approved>(desks.approve(forBob.text, null)).request.chat)

        assertEquals(listOf("holds", "holds", "approve"), first.calls)
        assertEquals(listOf("holds", "deny"), second.calls)
        assertTrue(chatPairing.isPaired(bob))
        assertEquals(listOf(ChatAddress(chat, "7"), bob), desks.paired().map { it.chat })
    }

    @Test
    fun `a code no desk holds is unknown or malformed at once, and no desk decides on it`() = runTest {
        assertEquals(PairingDecision.Unknown, desks.approve("AAAA-AAAA", null))
        assertEquals(PairingDecision.Malformed, desks.deny("hello", null))

        assertEquals(listOf("holds", "holds"), first.calls)
        assertEquals(listOf("holds", "holds"), second.calls)
    }

    @Test
    fun `a chat is unpaired by the desk that pairs it`() = runTest {
        val carol = ChatAddress(mail, "43")
        desks.approve(assertNotNull(mailPairing.request(carol, ChatKind.DIRECT, null, null)).text, null)

        assertEquals(Unpairing.Unpaired, desks.unpair(ChatAddress(mail, "43", "3")))

        assertEquals(Unpairing.NotPaired, desks.unpair(carol))
        assertEquals(Unpairing.Declared(approvalRemoved = false), desks.unpair(ChatAddress(chat, "7")))
        assertEquals(listOf("holds", "unpair", "unpair", "unpair"), first.calls)
        assertEquals(listOf("holds", "approve", "unpair", "unpair"), second.calls)
    }

    /** [pairings] recording which of its decisions are asked for. */
    private class Recorded(val pairings: Pairings) : PairingDesk by pairings {
        val calls = mutableListOf<String>()

        override fun holds(code: String): Boolean {
            calls += "holds"
            return pairings.holds(code)
        }

        override suspend fun approve(code: String, by: ChatUser?): PairingDecision {
            calls += "approve"
            return pairings.approve(code, by)
        }

        override suspend fun deny(code: String, by: ChatUser?): PairingDecision {
            calls += "deny"
            return pairings.deny(code, by)
        }

        override suspend fun unpair(chat: ChatAddress): Unpairing {
            calls += "unpair"
            return pairings.unpair(chat)
        }
    }
}
