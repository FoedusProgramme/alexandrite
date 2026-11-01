package org.unitedsoftware.alexandrite.channel.common.pairing

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testUser
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingsTest {
    private val clock = TestClock()
    private val store = FailingRecords(clock)
    private val desk = desk()
    private val work = ChannelInstanceId(ChannelType("chat"), "work")
    private val home = ChannelInstanceId(ChannelType("chat"), "home")
    private val configured = ChatAddress(work, "7")
    private val stranger = ChatAddress(work, "42")
    private val group = ChatAddress(work, "-100")
    private val ada = testUser("ada", instance = work)
    private val pairing = runBlocking { desk.open(work, listOf(configured)) }

    /** A desk of the plugin `chat-channel` over the records kept so far. */
    private fun desk(): Pairings = Pairings(clock, TestRecords(store).of(PLUGIN))

    private fun offer(chat: ChatAddress = stranger, pairing: InstancePairing = this.pairing): PairingCode? =
        pairing.request(chat, ChatKind.DIRECT, null, ada)

    private fun approval(kind: ChatKind = ChatKind.DIRECT, title: String? = null) =
        PairingApproval(kind, title, ada, clock.now, null)

    /** The approvals kept as `<chat> by <who>`. */
    private suspend fun records(): List<String> = store.list(PLUGIN, "pairings").map { "${it.key} by ${it.createdBy}" }

    // Codes.

    @Test
    fun `a paired chat and its threads pass and are offered no code`() = runTest {
        assertTrue(pairing.isPaired(configured))
        assertTrue(pairing.isPaired(ChatAddress(work, "7", "3")))
        assertNull(offer(configured))
        assertEquals(emptyList(), desk.pending())
        assertEquals(listOf(PairedChat(configured, true, null)), desk.paired())
    }

    @Test
    fun `an unpaired chat is offered one code per code lifetime, then nothing until it expires`() {
        val first = assertNotNull(offer())

        assertFalse(pairing.isPaired(stranger))
        assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}").matches(first.text), first.text)
        assertEquals(clock.now.plusSeconds(600), first.expiresAt)
        clock.advance(599)
        assertNull(offer())
        assertNull(offer(ChatAddress(work, "42", "9")))
        clock.advance(1)
        val second = assertNotNull(offer())
        assertNotEquals(first.text, second.text)
        assertEquals(listOf(second.text), desk.pending().map { it.code })
    }

    @Test
    fun `a request names the chat, its kind and title, who asked and when the code expires`() {
        val code = assertNotNull(pairing.request(ChatAddress(work, "-100", "5"), ChatKind.GROUP, "Backend", ada))

        assertEquals(
            listOf(PairingRequest(group, ChatKind.GROUP, "Backend", ada, code.text, clock.now, code.expiresAt)),
            desk.pending(),
        )
    }

    @Test
    fun `the requests come oldest first and leave when their codes expire`() {
        offer(group)
        clock.advance(300)
        offer()

        assertEquals(listOf(group, stranger), desk.pending().map { it.chat })
        clock.advance(300)
        assertEquals(listOf(stranger), desk.pending().map { it.chat })
        clock.advance(300)
        assertEquals(emptyList(), desk.pending())
    }

    // Decisions.

    @Test
    fun `an approved code pairs its chat at once and keeps it as a record of the host's`() = runTest {
        val code = assertNotNull(offer())

        val decision = assertIs<PairingDecision.Approved>(desk.approve(code.text.lowercase().replace("-", " "), null))

        assertEquals(stranger, decision.request.chat)
        assertTrue(pairing.isPaired(stranger))
        assertEquals(listOf("$stranger by null"), records())
        assertEquals(
            listOf(PairedChat(configured, true, null), PairedChat(stranger, false, approval())),
            desk.paired(),
        )
        assertEquals(emptyList(), desk.pending())
        assertIs<PairingDecision.Unknown>(desk.approve(code.text, null))
        assertNull(offer())
    }

    @Test
    fun `an approval keeps who approved it`() = runTest {
        val operator = testUser("grace", isOperator = true, instance = home)

        desk.approve(assertNotNull(offer()).text, operator)

        assertEquals(listOf("$stranger by ${operator.address}"), records())
        assertEquals(operator.address, desk.paired().last().approval?.approvedBy)
    }

    @Test
    fun `an approval outlives the desk, and the next desk opens the instance with it`() = runTest {
        val code = assertNotNull(pairing.request(group, ChatKind.GROUP, "Backend", ada))
        desk.approve(code.text, null)
        val approved = approval(ChatKind.GROUP, "Backend")

        val next = desk()
        val reopened = next.open(work, listOf(configured))

        assertTrue(reopened.isPaired(ChatAddress(work, "-100", "5")))
        assertEquals(listOf(PairedChat(configured, true, null), PairedChat(group, false, approved)), next.paired())
        assertNull(reopened.request(group, ChatKind.GROUP, "Backend", ada))
        assertFalse(next.open(home, emptyList()).isPaired(ChatAddress(home, "-100")))
    }

    @Test
    fun `a denied code pairs nothing and the chat stays silent until the code would have expired`() = runTest {
        val code = assertNotNull(offer())

        assertEquals(stranger, assertIs<PairingDecision.Denied>(desk.deny(code.text, null)).request.chat)

        assertFalse(pairing.isPaired(stranger))
        assertEquals(emptyList(), records())
        assertEquals(emptyList(), desk.pending())
        assertNull(offer())
        clock.advance(600)
        assertNotNull(offer())
    }

    @Test
    fun `unknown and malformed codes are told apart, and no number of wrong codes locks the operator out`() = runTest {
        val code = assertNotNull(offer())

        assertEquals(PairingDecision.Malformed, desk.approve("hello", null))
        repeat(20) { assertEquals(PairingDecision.Unknown, desk.deny("AAAA-AAAA", null)) }
        repeat(20) { assertEquals(PairingDecision.Unknown, desk.approve("AAAA-AAAA", null)) }
        repeat(10) { assertFalse(desk.holds("AAAA-AAAA")) }

        assertIs<PairingDecision.Approved>(desk.approve(code.text, null))
        assertTrue(pairing.isPaired(stranger))
    }

    @Test
    fun `a desk holds the live codes of its requests however they are written`() = runTest {
        val code = assertNotNull(offer())
        val other = desk()
        val elsewhere = assertNotNull(
            other.open(home, emptyList()).request(ChatAddress(home, "42"), ChatKind.DIRECT, null, ada),
        )

        assertTrue(desk.holds(code.text.lowercase().replace("-", " ")))
        assertFalse(desk.holds(elsewhere.text))
        assertTrue(other.holds(elsewhere.text))
        assertFalse(desk.holds("hello"))

        assertIs<PairingDecision.Approved>(desk.approve(code.text, null))
        assertFalse(desk.holds(code.text))
        clock.advance(600)
        assertFalse(other.holds(elsewhere.text))
    }

    @Test
    fun `a store that fails keeps the chat unpaired and its request waiting under a new code`() = runTest {
        val code = assertNotNull(offer())
        store.failure = IOException("The disk is full.")

        val failed = assertIs<PairingDecision.Failed>(desk.approve(code.text, null))

        assertEquals("The disk is full.", failed.reason)
        assertNotEquals(code.text, failed.request.code)
        assertFalse(pairing.isPaired(stranger))
        assertEquals(listOf(failed.request), desk.pending())
        assertNull(offer())
        assertIs<PairingDecision.Unknown>(desk.approve(code.text, null))
        store.failure = null
        assertIs<PairingDecision.Approved>(desk.approve(failed.request.code, null))
        assertTrue(pairing.isPaired(stranger))
    }

    @Test
    fun `unpairing removes the chat's record and stops its messages at once`() = runTest {
        desk.approve(assertNotNull(offer()).text, null)

        assertEquals(Unpairing.Unpaired, desk.unpair(ChatAddress(work, "42", "3")))

        assertFalse(pairing.isPaired(stranger))
        assertEquals(emptyList(), records())
        assertEquals(Unpairing.NotPaired, desk.unpair(stranger))
        assertEquals(Unpairing.NotPaired, desk.unpair(ChatAddress(home, "42")))
        assertEquals(listOf(PairedChat(configured, true, null)), desk.paired())
        clock.advance(600)
        assertNotNull(offer())
    }

    @Test
    fun `a chat the config declares, or one whose record cannot be removed, stays paired`() = runTest {
        desk.approve(assertNotNull(offer()).text, null)

        assertEquals(Unpairing.Declared(approvalRemoved = false), desk.unpair(ChatAddress(work, "7", "3")))
        assertTrue(pairing.isPaired(configured))

        store.failure = IOException("The disk is full.")
        assertEquals(Unpairing.Failed("The disk is full."), desk.unpair(stranger))
        assertTrue(pairing.isPaired(stranger))
        assertEquals(listOf("$stranger by null"), records())
    }

    @Test
    fun `unpairing a declared chat removes its approval too, so taking it out of the config unpairs it`() = runTest {
        desk.approve(assertNotNull(offer()).text, null)
        val declaring = desk()
        val declared = declaring.open(work, listOf(configured, stranger))
        assertEquals(listOf(PairedChat(stranger, true, approval())), declaring.paired().drop(1))

        assertEquals(Unpairing.Declared(approvalRemoved = true), declaring.unpair(stranger))

        assertTrue(declared.isPaired(stranger))
        assertEquals(emptyList(), records())
        assertEquals(listOf(PairedChat(stranger, true, null)), declaring.paired().drop(1))
        assertEquals(Unpairing.Declared(approvalRemoved = false), declaring.unpair(stranger))
        assertFalse(desk().open(work, listOf(configured)).isPaired(stranger))
    }

    // Instances.

    @Test
    fun `one desk pairs the chats of every instance it opened`() = runTest {
        val other = desk.open(home, emptyList())
        val homeChat = ChatAddress(home, "42")
        val code = assertNotNull(other.request(homeChat, ChatKind.DIRECT, null, null))
        assertNotNull(offer())

        assertIs<PairingDecision.Approved>(desk.approve(code.text, null))

        assertTrue(other.isPaired(homeChat))
        assertFalse(pairing.isPaired(stranger))
        assertEquals(listOf(configured, homeChat), desk.paired().map { it.chat })
        assertEquals(listOf(stranger), desk.pending().map { it.chat })
    }

    @Test
    fun `the approvals of an instance that does not run are listed and can be removed before it runs again`() =
        runTest {
            desk.approve(assertNotNull(offer()).text, null)
            desk.approve(assertNotNull(offer(group)).text, null)
            val next = desk()
            next.open(home, emptyList())

            assertEquals(
                listOf(stranger, group).map { PairedChat(it, false, approval(), instanceRunning = false) },
                next.paired(),
            )
            assertEquals(Unpairing.Unpaired, next.unpair(ChatAddress(work, "42", "3")))
            assertEquals(Unpairing.NotPaired, next.unpair(stranger))
            assertEquals(Unpairing.NotPaired, next.unpair(ChatAddress(home, "42")))

            assertEquals(listOf("$group by null"), records())
            val reopened = next.open(work, emptyList())
            assertFalse(reopened.isPaired(stranger))
            assertTrue(reopened.isPaired(group))
            assertEquals(listOf(PairedChat(group, false, approval())), next.paired())
        }

    @Test
    fun `a closed instance takes its requests and codes along, and may open again`() = runTest {
        val code = assertNotNull(offer())

        desk.close(work)

        assertEquals(emptyList(), desk.pending())
        assertEquals(emptyList(), desk.paired())
        assertIs<PairingDecision.Unknown>(desk.approve(code.text, null))
        assertNull(offer())
        val reopened = desk.open(work, emptyList())
        assertNotNull(offer(pairing = reopened))
    }

    @Test
    fun `an instance opens once, with its own chats only`() = runTest {
        assertFailsWith<IllegalArgumentException> { desk.open(work, emptyList()) }
        assertFailsWith<IllegalArgumentException> { desk.open(home, listOf(configured)) }
    }

    private companion object {
        const val PLUGIN = "chat-channel"
    }
}
