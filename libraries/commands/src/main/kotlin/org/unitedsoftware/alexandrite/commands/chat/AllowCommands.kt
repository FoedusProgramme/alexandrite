package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.isKeyword
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.rebuild
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** `/allow`. */
@Singleton
@Contribute(CommandHandler::class)
internal class AllowCommands(
    private val permissionRules: PermissionRules,
    private val texts: Texts,
    private val clock: Clock,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(ALLOW).rebuild { operatorsOnly(CommandForms.ALL) })

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn)
        val words = words(invocation.arguments)
        val number = words.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        when {
            words.isEmpty() -> list(invocation, context, replies)

            words.size == 2 && words[0].isKeyword(
                REVOKE,
            ) && number != null -> revoke(number, invocation, context, replies)

            else -> context.reply(replies.usage(ALLOW))
        }
    }

    private suspend fun list(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val rules = context.refusing { permissionRules.allowRules(invocation.chat) } ?: return
        val text = if (rules.isEmpty()) {
            replies.text(TextKeys.ALLOW_NONE)
        } else {
            replies.text(TextKeys.ALLOW_LIST, "rules" to rules.joinToString("\n") { described(it, replies) })
        }
        context.reply(text)
    }

    private suspend fun revoke(number: Int, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val revocation =
            context.refusing { permissionRules.revokeAllowRule(invocation.chat, number, invocation.issuer) }
                ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.ALLOW_REVOKED_DECLARED
            revocation.declared -> TextKeys.ALLOW_DECLARED_KEPT
            revocation.removed -> TextKeys.ALLOW_REVOKED
            else -> TextKeys.ALLOW_UNKNOWN
        }
        context.reply(replies.text(key, "number" to number))
    }

    /** [rule] with where it comes from. */
    private fun described(rule: AllowRule, replies: Replies): String {
        val at = rule.grantedAt
        val by = rule.grantedBy
        val source = when {
            at == null -> replies.text(TextKeys.ALLOW_DECLARED)
            by == null -> replies.text(TextKeys.ALLOW_GRANTED_BY_HOST, "date" to dateOf(at, replies))
            else -> replies.text(TextKeys.ALLOW_GRANTED, "user" to by.displayName, "date" to dateOf(at, replies))
        }
        val subject = rule.subject
        return replies.text(
            if (subject.tokens.isEmpty()) TextKeys.ALLOW_RULE_TOOL else TextKeys.ALLOW_RULE,
            "number" to rule.number,
            "tool" to subject.tool,
            "rule" to subject.display,
            "source" to source,
        )
    }

    private fun dateOf(instant: Instant, replies: Replies): LocalDate =
        instant.atZone(replies.zone ?: clock.zone).toLocalDate()

    private companion object {
        const val ALLOW = "allow"
        const val REVOKE = "revoke"
    }
}
