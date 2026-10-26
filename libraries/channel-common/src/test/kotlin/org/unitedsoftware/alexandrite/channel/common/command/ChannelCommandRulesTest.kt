package org.unitedsoftware.alexandrite.channel.common.command

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinkGroup
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules every channel's own command rules wrap, pinned once here for the matrix, the floor and overrides alike. */
class ChannelCommandRulesTest {
    private val instance = ChannelInstanceId(ChannelType("test"), "main")
    private val direct = ChatAddress(instance, "direct:u1")
    private val group = ChatAddress(instance, "group:g1")

    private fun isDirect(chat: ChatAddress) = chat.chat.startsWith("direct:")

    @Test
    fun `an operator issuer is refused nothing`() {
        val rules = rules(handler("trust" to CommandForms.ALL))
        val issuer = user(isOperator = true)
        val invocation = CommandInvocation.builder(group, "trust", issuer).build()
        assertNull(rules.refusal(invocation, ChatKind.GROUP))
    }

    @Test
    fun `a command the matrix says anyone may run is refused nobody`() {
        val rules = rules(handler("help" to CommandForms.NONE))
        val issuer = user(isOperator = false)
        val invocation = CommandInvocation.builder(group, "help", issuer).build()
        assertNull(rules.refusal(invocation, ChatKind.GROUP))
    }

    @Test
    fun `the matrix keeps a form for admins and leaves the other to anyone`() {
        val rules = rules(handler("model" to CommandForms.NONE))
        val issuer = user(isOperator = false)
        val bare = CommandInvocation.builder(group, "model", issuer).build()
        val withArguments = CommandInvocation.builder(group, "model", issuer).arguments("x").build()
        assertNull(rules.refusal(bare, ChatKind.GROUP))
        assertEquals(CommandRefusal.ADMIN, rules.refusal(withArguments, ChatKind.GROUP))
    }

    @Test
    fun `a direct-only command is refused in a group`() {
        val rules = rules(handler("cancel" to CommandForms.NONE))
        val issuer = user(isOperator = false)
        val inGroup = CommandInvocation.builder(group, "cancel", issuer).build()
        val inDirect = CommandInvocation.builder(direct, "cancel", issuer).build()
        assertEquals(CommandRefusal.ADMIN_HERE, rules.refusal(inGroup, ChatKind.GROUP))
        assertNull(rules.refusal(inDirect, ChatKind.DIRECT))
    }

    @OptIn(InternalAlexandriteApi::class)
    @Test
    fun `an unlinked-direct-only command is refused in a direct chat linked with another`() {
        val member = ChatAddress(instance, "direct:u2")
        val links = object : ChatLinks {
            override suspend fun link(agent: AgentId, anchor: ChatAddress, member: ChatAddress, by: ChatUser?) =
                error("not used")

            override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?) = error("not used")

            override fun groups(agent: AgentId?): List<ChatLinkGroup> =
                listOf(ChatLinkGroup(agent = AgentId("main"), anchor = group, members = listOf(direct, member)))
        }
        val rules = ChannelCommandRules(listOf(handler("new" to CommandForms.NONE)), links, isDirect = ::isDirect)
        val issuer = user(isOperator = false)
        val invocation = CommandInvocation.builder(direct, "new", issuer).build()
        assertEquals(CommandRefusal.ADMIN_HERE, rules.refusal(invocation, ChatKind.DIRECT))
        assertFalse(rules.mayRun(direct, admin = false, "new", withArguments = false))
    }

    @Test
    fun `a command the matrix does not list is refused unless its spec opens it`() {
        val open = rules(handler("usage" to CommandForms.NONE))
        val restricted = rules(handler("usage" to CommandForms.WITH_ARGUMENTS))
        val issuer = user(isOperator = false)
        val invocation = CommandInvocation.builder(group, "usage", issuer).build()
        assertNull(open.refusal(invocation, ChatKind.GROUP))
        assertEquals(CommandRefusal.ADMIN, restricted.refusal(invocation, ChatKind.GROUP))
    }

    @Test
    fun `a command the handler does not claim is refused nobody`() {
        val rules = rules(handler("help" to CommandForms.NONE))
        val issuer = user(isOperator = false)
        val invocation = CommandInvocation.builder(group, "unclaimed", issuer).build()
        assertNull(rules.refusal(invocation, ChatKind.GROUP))
    }

    @Test
    fun `the form a spec keeps for operators stays with admins however the matrix or a configured rule read`() {
        val rules = ChannelCommandRules(
            listOf(handler("settings" to CommandForms.ALL)),
            links = null,
            configured = mapOf("settings" to CommandWho.ANYONE),
            isDirect = ::isDirect,
        )
        assertFalse(rules.mayRun(group, admin = false, "settings", withArguments = false))
    }

    @Test
    fun `a configured rule overrides the default matrix`() {
        val rules = ChannelCommandRules(
            listOf(handler("model" to CommandForms.NONE)),
            links = null,
            configured = mapOf("model" to CommandWho.ANYONE),
            isDirect = ::isDirect,
        )
        assertTrue(rules.mayRun(group, admin = false, "model", withArguments = true))
    }

    private fun rules(handler: CommandHandler) =
        ChannelCommandRules(listOf(handler), links = null, isDirect = ::isDirect)

    private fun handler(vararg commands: Pair<String, CommandForms>): CommandHandler = object : CommandHandler {
        override val commands: List<CommandSpec> = commands.map { (name, forms) ->
            CommandSpec.builder(name, "a test command").operatorsOnly(forms).build()
        }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) = Unit
    }

    private fun user(isOperator: Boolean): ChatUser =
        ChatUser(UserAddress(instance, "u1"), "someone", null, isBot = false, isOperator = isOperator)
}
