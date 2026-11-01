package org.unitedsoftware.alexandrite.channel.common.link

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.common.pairing.TestClock
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.testkit.testRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An operator's own identity reaching a chat where the platform names them by another id. */
class OperatorLinksTest {
    private val clock = TestClock()
    private val instance = ChannelInstanceId(ChannelType("test"), "main")
    private val operator = UserAddress(instance, "operator-1")
    private val group = ChatAddress(instance, "group:g1")
    private val links = OperatorLinks(clock, testRecords("test-operator-links"))

    @Test
    fun `a spent code links the alias to the operator who asked for it, in that chat alone`() = runBlocking {
        val code = links.issue(operator)

        val decision = assertIs<LinkDecision.Linked>(links.redeem(code.text, group, "member-1"))
        assertEquals(operator, decision.operator)
        assertEquals(operator, links.resolve(group, "member-1"))
        assertNull(links.resolve(ChatAddress(instance, "group:g2"), "member-1"), "the alias is of this chat alone")
        assertNull(links.resolve(group, "member-2"), "no code was spent for this alias")
    }

    @Test
    fun `a code is spent once`() = runBlocking {
        val code = links.issue(operator)

        assertIs<LinkDecision.Linked>(links.redeem(code.text, group, "member-1"))
        assertIs<LinkDecision.Rejected>(links.redeem(code.text, group, "member-2"))
    }

    @Test
    fun `a code expires`() = runBlocking {
        val code = links.issue(operator)

        clock.advance(links.codeLifetime.inWholeSeconds + 1)

        assertIs<LinkDecision.Rejected>(links.redeem(code.text, group, "member-1"))
    }

    @Test
    fun `asking again replaces the operator's own earlier code`() = runBlocking {
        val first = links.issue(operator)
        val second = links.issue(operator)

        assertIs<LinkDecision.Rejected>(links.redeem(first.text, group, "member-1"))
        assertIs<LinkDecision.Linked>(links.redeem(second.text, group, "member-1"))
    }

    @Test
    fun `a malformed code is rejected without counting as a wrong try`() = runBlocking {
        assertIs<LinkDecision.Malformed>(links.redeem("not a code", group, "member-1"))
    }

    @Test
    fun `a revoked link is forgotten`() = runBlocking {
        val code = links.issue(operator)
        links.redeem(code.text, group, "member-1")

        assertTrue(links.unlink(group, "member-1"))
        assertNull(links.resolve(group, "member-1"))
        assertEquals(false, links.unlink(group, "member-1"), "nothing was left to unlink")
    }

    @Test
    fun `two operators may each link their own alias into the same chat`() = runBlocking {
        val other = UserAddress(instance, "operator-2")
        links.redeem(links.issue(operator).text, group, "member-1")
        links.redeem(links.issue(other).text, group, "member-2")

        assertEquals(operator, links.resolve(group, "member-1"))
        assertEquals(other, links.resolve(group, "member-2"))

        assertTrue(links.unlink(group, "member-1"))
        assertEquals(other, links.resolve(group, "member-2"), "unlinking one alias keeps the chat's other one")
    }

    @Test
    fun `an operator's links are listed across every chat`() = runBlocking {
        val other = ChatAddress(instance, "group:g2")
        links.redeem(links.issue(operator).text, group, "member-1")
        links.redeem(links.issue(operator).text, other, "member-9")

        val listed = links.of(operator)
        assertEquals(setOf(group to "member-1", other to "member-9"), listed.map { it.chat to it.alias }.toSet())
    }
}
