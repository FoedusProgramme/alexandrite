package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.channel.common.command.CommandWho
import org.unitedsoftware.alexandrite.channel.qqbot.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QqBotCommandRulesTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")
    private val direct = ChatAddress(instance, "private:u1")
    private val group = ChatAddress(instance, "group:g1")
    private val rules = QqBotCommandRules(listOf(Commands()), links = null)

    @Test
    fun `mayRun answers the matrix for a member`() {
        assertTrue(rules.mayRun(direct, admin = false, "model", withArguments = false))
        assertFalse(rules.mayRun(direct, admin = false, "model", withArguments = true))
        assertTrue(rules.mayRun(direct, admin = false, "help", withArguments = false))
        assertTrue(rules.mayRun(group, admin = false, "help", withArguments = false))
        assertTrue(rules.mayRun(group, admin = false, "context", withArguments = true))
        assertFalse(rules.mayRun(group, admin = false, "agent", withArguments = true))
        assertFalse(rules.mayRun(group, admin = false, "trust", withArguments = false))
        assertTrue(rules.mayRun(group, admin = true, "trust", withArguments = false))
        assertTrue(rules.mayRun(group, admin = false, "not_a_command", withArguments = true))
        assertTrue(rules.mayRun(group, admin = false, "heartbeat", withArguments = false))
        assertFalse(rules.mayRun(direct, admin = false, "schedule", withArguments = true))
        assertTrue(rules.mayRun(direct, admin = false, "timezone", withArguments = true))
        assertFalse(rules.mayRun(group, admin = false, "timezone", withArguments = true))
        assertFalse(rules.mayRun(direct, admin = false, "home", withArguments = false))
    }

    @Test
    fun `a form a handler keeps for operators stays with admins whatever the matrix says`() {
        val kept = QqBotCommandRules(listOf(Commands(mapOf("settings" to CommandForms.ALL))), links = null)
        val chat = ChatAddress(instance, "group:g1")
        val issuer = ChatUser(UserAddress(instance, "u1"), "someone", null, isBot = false, isOperator = false)
        val invocation = CommandInvocation.builder(chat, "settings", issuer).build()
        assertEquals(TextKeys.REFUSED_ADMIN, kept.refusal(invocation, ChatKind.GROUP))
        assertFalse(kept.mayRun(chat, admin = false, "settings", withArguments = false))
    }

    @Test
    fun `a configured rule overrides the default matrix`() {
        val configured =
            QqBotCommandRules(listOf(Commands()), links = null, configured = mapOf("model" to CommandWho.ANYONE))
        assertTrue(configured.mayRun(group, admin = false, "model", withArguments = true))
    }

    private class Commands(private val operatorsOnly: Map<String, CommandForms> = emptyMap()) : CommandHandler {
        override val commands: List<CommandSpec> = listOf(
            "help",
            "settings",
            "context",
            "agent",
            "model",
            "trust",
            "schedule",
            "heartbeat",
            "timezone",
            "home",
            "link",
            "unlink",
            "untaint",
        ).map {
            CommandSpec.builder(it, "a test command").operatorsOnly(operatorsOnly[it] ?: CommandForms.NONE).build()
        }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) = Unit
    }
}
