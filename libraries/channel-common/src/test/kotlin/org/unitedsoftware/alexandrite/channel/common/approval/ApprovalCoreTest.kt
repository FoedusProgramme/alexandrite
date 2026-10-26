package org.unitedsoftware.alexandrite.channel.common.approval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalCoreTest {
    private fun request(id: String, rules: List<String> = emptyList()): ApprovalRequest = ApprovalRequest.builder(
        id = id,
        turn = testTurn(),
        tool = "fs.write",
        risk = ToolRisk.WORKSPACE_WRITE,
        arguments = """{"path":"notes.md"}""",
        reason = "the chat is in ask mode",
    ).alwaysRules(rules.map { RuleSubject("exec", emptyList(), it) }).build()

    // ApprovalRegistry.

    @Test
    fun `a request registers once, and a second of the same id is refused`() {
        val registry = ApprovalRegistry()

        val first = registry.register(request("a"))
        val second = registry.register(request("a"))

        assertTrue(first != null)
        assertNull(second)
        assertEquals(listOf("a"), registry.values.map { it.request.id })
    }

    @Test
    fun `forgetting a request drops it from values and by id`() {
        val registry = ApprovalRegistry()
        registry.register(request("a"))

        registry.forget("a")

        assertNull(registry["a"])
        assertEquals(emptyList(), registry.values.toList())
    }

    @Test
    fun `stopAll answers every request still pending as unavailable, once`() = runTest {
        val registry = ApprovalRegistry()
        val waiting = registry.register(request("a"))!!

        registry.stopAll("the bot stopped")

        val ending = assertIs<ApprovalEnding.Answered>(waiting.ending.await())
        assertEquals("the bot stopped", (ending.answer as ApprovalAnswer.Unavailable).reason)
    }

    // carrying().

    @Test
    fun `carrying marks the chat before send, and keeps it marked once send delivers`() = runTest {
        val waiting = PendingApproval(request("a"))
        val chat = testChat()

        val delivered = waiting.carrying(chat) { true }

        assertTrue(delivered)
        assertTrue(waiting.carries(chat))
        assertEquals(setOf(chat), waiting.carriedChats)
    }

    @Test
    fun `carrying undoes the mark once send never truly delivers`() = runTest {
        val waiting = PendingApproval(request("a"))
        val chat = testChat()

        val delivered = waiting.carrying(chat) { false }

        assertFalse(delivered)
        assertFalse(waiting.carries(chat))
    }

    @Test
    fun `carrying undoes the mark when send throws, cancellation included`() = runTest {
        val waiting = PendingApproval(request("a"))
        val chat = testChat()

        assertFailsWith<IllegalStateException> {
            waiting.carrying(chat) { error("the platform refused it") }
        }
        assertFalse(waiting.carries(chat))

        assertFailsWith<CancellationException> {
            waiting.carrying(chat) { throw CancellationException("withdrawn") }
        }
        assertFalse(waiting.carries(chat))
    }

    @Test
    fun `a chat marked by one request does not carry another`() {
        val a = PendingApproval(request("a"))
        val b = PendingApproval(request("b"))
        val chat = testChat()

        a.carry(chat)

        assertTrue(a.carries(chat))
        assertFalse(b.carries(chat))
    }

    // resolveApproval.

    @Test
    fun `no code lists every request the chat carries, none of the ones it does not`() {
        val chat = testChat()
        val elsewhere = testChat("elsewhere")
        val a = PendingApproval(request("a")).also { it.carry(chat) }
        val b = PendingApproval(request("b")).also { it.carry(elsewhere) }

        val resolution = resolveApproval(listOf(a, b), chat, null)

        val listing = assertIs<ApprovalResolution.Listing>(resolution)
        assertEquals(listOf("a"), listing.requests.map { it.id })
    }

    @Test
    fun `no request carried and no code is none, not an empty listing`() {
        val resolution = resolveApproval(emptyList(), testChat(), null)

        assertIs<ApprovalResolution.None>(resolution)
    }

    @Test
    fun `an id written out in full is matched exactly before any prefix`() {
        val chat = testChat()
        val aaa = PendingApproval(request("aaa")).also { it.carry(chat) }
        val aaa111 = PendingApproval(request("aaa111")).also { it.carry(chat) }

        val resolution = resolveApproval(listOf(aaa, aaa111), chat, "aaa")

        assertEquals("aaa", (resolution as ApprovalResolution.One).id)
    }

    @Test
    fun `a prefix that names one request answers it`() {
        val chat = testChat()
        val waiting = PendingApproval(request("abcdef")).also { it.carry(chat) }

        val resolution = resolveApproval(listOf(waiting), chat, "abc")

        assertEquals("abcdef", (resolution as ApprovalResolution.One).id)
    }

    @Test
    fun `a prefix that names several requests is ambiguous`() {
        val chat = testChat()
        val a = PendingApproval(request("abc1")).also { it.carry(chat) }
        val b = PendingApproval(request("abc2")).also { it.carry(chat) }

        val resolution = resolveApproval(listOf(a, b), chat, "abc")

        assertEquals(setOf("abc1", "abc2"), (resolution as ApprovalResolution.Ambiguous).ids.toSet())
    }

    @Test
    fun `a code that names nothing the chat carries is none`() {
        val chat = testChat()
        val waiting = PendingApproval(request("abc")).also { it.carry(chat) }

        val resolution = resolveApproval(listOf(waiting), chat, "zzz")

        assertIs<ApprovalResolution.None>(resolution)
    }

    @Test
    fun `a chat that does not carry a request cannot name it, even in full`() {
        val waiting = PendingApproval(request("abc")).also { it.carry(testChat("elsewhere")) }

        val resolution = resolveApproval(listOf(waiting), testChat(), "abc")

        assertIs<ApprovalResolution.None>(resolution)
    }

    // answerWords.

    @Test
    fun `always is read case-insensitively and is not mistaken for a code`() {
        val words = answerWords(listOf("abc", "ALWAYS"))

        assertEquals("abc", words.code)
        assertTrue(words.always)
    }

    @Test
    fun `always alone, with no code, still reads as always`() {
        val words = answerWords(listOf("Always"))

        assertNull(words.code)
        assertTrue(words.always)
    }

    @Test
    fun `no words at all names no code and no always`() {
        val words = answerWords(emptyList())

        assertNull(words.code)
        assertFalse(words.always)
    }

    // decideApproval.

    @Test
    fun `an operator may allow or deny, with always granting every rule the request offers`() {
        val by = testUser(isOperator = true)
        val req = request("a", listOf("rule-one", "rule-two"))

        val allowed = decideApproval(req, by, allow = true, always = true) as ApprovalAnswer.Allowed
        val denied = decideApproval(req, by, allow = false, always = true) as ApprovalAnswer.Denied

        assertTrue(allowed.always)
        assertEquals(by, denied.by)
    }

    @Test
    fun `always grants nothing when the request offers no rule to grant`() {
        val by = testUser(isOperator = true)
        val req = request("a")

        val allowed = decideApproval(req, by, allow = true, always = true) as ApprovalAnswer.Allowed

        assertFalse(allowed.always)
    }

    @Test
    fun `someone who is not an operator may not answer at all`() {
        val by = testUser(isOperator = false)

        val decision = decideApproval(request("a"), by, allow = true, always = false)

        assertNull(decision)
    }

    // alwaysRulesText.

    @Test
    fun `every rule is named, not only the first`() {
        val rules = listOf(
            RuleSubject("exec", listOf("curl"), "exec(curl)"),
            RuleSubject("exec", listOf("wget"), "exec(wget)"),
        )

        assertEquals("exec(curl), exec(wget)", alwaysRulesText(rules))
    }

    // Approval minutes bounds.

    @Test
    fun `the approval wait bounds match the minute and the day`() {
        assertTrue(isApprovalMinutes(MIN_APPROVAL_MINUTES))
        assertTrue(isApprovalMinutes(MAX_APPROVAL_MINUTES))
        assertFalse(isApprovalMinutes(MIN_APPROVAL_MINUTES - 1))
        assertFalse(isApprovalMinutes(MAX_APPROVAL_MINUTES + 1))
    }

    // approvalRecipients.

    @Test
    fun `an operator's own turn is shown only in that turn's own chat`() {
        val chat = testChat("op-chat")
        val turn = testTurn(chat = chat) { actor(testUser(isOperator = true)) }

        val recipients = approvalRecipients(turn, listOf("a", "b"), isPaired = { true }) { testChat("private-$it") }

        assertEquals(listOf(chat), recipients)
    }

    @Test
    fun `a member's turn is shown in every paired admin's own chat, never the turn's own chat`() {
        val turn = testTurn(chat = testChat("group-chat"))

        val recipients = approvalRecipients(turn, listOf("a", "b"), isPaired = { true }) { testChat("private-$it") }

        assertEquals(listOf(testChat("private-a"), testChat("private-b")), recipients)
    }

    @Test
    fun `a member's turn never reaches an admin whose own chat is not paired`() {
        val turn = testTurn(chat = testChat("group-chat"))
        val pairedOnly = testChat("private-a")

        val recipients = approvalRecipients(turn, listOf("a", "b"), isPaired = { it == pairedOnly }) {
            testChat("private-$it")
        }

        assertEquals(listOf(pairedOnly), recipients)
    }

    @Test
    fun `a member's turn names a duplicate admin id only once`() {
        val turn = testTurn(chat = testChat("group-chat"))

        val recipients = approvalRecipients(turn, listOf("a", "a", "b"), isPaired = { true }) {
            testChat("private-$it")
        }

        assertEquals(listOf(testChat("private-a"), testChat("private-b")), recipients)
    }
}
