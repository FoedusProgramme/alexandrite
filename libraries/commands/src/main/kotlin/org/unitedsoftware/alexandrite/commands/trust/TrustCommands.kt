package org.unitedsoftware.alexandrite.commands.trust

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.commandWord
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.isKeyword
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TrustRule
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedPerson
import org.unitedsoftware.alexandrite.sdk.turn.rebuild
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** `/trust`, `/untrust` and `/untaint`: of folders, of the calls that trust rules cover, and of people. */
@Singleton
@Contribute(CommandHandler::class)
internal class TrustCommands(
    private val conversations: ConversationControl,
    private val trust: TrustControl,
    private val texts: Texts,
    private val clock: Clock,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(
        texts.spec(TRUST).rebuild { operatorsOnly(CommandForms.ALL) },
        texts.spec(UNTRUST).rebuild { operatorsOnly(CommandForms.ALL) },
        texts.spec(UNTAINT).rebuild { operatorsOnly(CommandForms.ALL) },
    )

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn)
        val argument = unquoted(invocation.arguments.trim())
        when (val name = commandWord(invocation.name)) {
            TRUST -> when {
                invocation.mentions.isNotEmpty() -> trustPeople(invocation.mentions, invocation, context, replies)

                argument.isEmpty() && invocation.replyTo != null ->
                    trustPeople(listOfNotNull(invocation.replyTo), invocation, context, replies)

                argument.isEmpty() -> trusted(invocation, context, replies)

                isRule(argument) -> trustRule(argument, invocation, context, replies)

                else -> trust(argument, invocation, context, replies)
            }

            UNTRUST -> when {
                invocation.mentions.isNotEmpty() -> untrustPeople(invocation.mentions, invocation, context, replies)

                argument.isEmpty() && invocation.replyTo != null ->
                    untrustPeople(listOfNotNull(invocation.replyTo), invocation, context, replies)

                argument.isEmpty() -> context.reply(replies.usage(name))

                isRule(argument) -> untrustRule(argument, invocation, context, replies)

                else -> untrust(argument, invocation, context, replies)
            }

            UNTAINT -> untaint(invocation, context, replies)
        }
    }

    /**
     * `/untaint` clears the conversations of every agent in the chat, `/untaint <agent>` one agent's, and
     * `/untaint heartbeat [<agent>]` the heartbeat conversation of the chat's agent or of the one named.
     */
    private suspend fun untaint(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val words = words(invocation.arguments)
        val heartbeat = words.firstOrNull()?.isKeyword(HEARTBEAT) == true
        val named = words.drop(if (heartbeat) 1 else 0)
        if (named.size > 1) {
            context.reply(replies.usage(UNTAINT))
            return
        }
        val agent = named.singleOrNull()?.let { word ->
            try {
                AgentId(word)
            } catch (e: IllegalArgumentException) {
                context.reply(replies.text(TextKeys.AGENT_MALFORMED, "text" to word))
                return
            }
        }
        if (heartbeat) {
            val shown = agent ?: context.turn.agent
            val cleared = context.refusing {
                conversations.untaintHeartbeat(invocation.chat, invocation.issuer, agent)
            }
            val text = if (cleared == null) {
                replies.text(TextKeys.UNTAINT_HEARTBEAT_CLEAN, "agent" to shown)
            } else {
                replies.text(
                    TextKeys.UNTAINT_HEARTBEAT_DONE,
                    "agent" to shown,
                    "reason" to cleared.reason,
                    "date" to dateOf(cleared.at, replies),
                )
            }
            context.reply(text)
            return
        }
        val cleared = context.refusing { conversations.untaint(invocation.chat, invocation.issuer, agent) } ?: return
        val text = when {
            cleared.isEmpty() && agent != null -> replies.text(TextKeys.UNTAINT_AGENT_CLEAN, "agent" to agent)

            cleared.isEmpty() -> replies.text(TextKeys.UNTAINT_CLEAN)

            cleared.size == 1 -> cleared.single().let {
                replies.text(
                    TextKeys.UNTAINT_DONE,
                    "agent" to it.agent,
                    "reason" to it.taint.reason,
                    "date" to dateOf(it.taint.at, replies),
                )
            }

            else -> replies.text(
                TextKeys.UNTAINT_DONE_MANY,
                "list" to cleared.joinToString("\n") {
                    replies.text(
                        TextKeys.UNTAINT_ITEM,
                        "agent" to it.agent,
                        "reason" to it.taint.reason,
                        "date" to dateOf(it.taint.at, replies),
                    )
                },
            )
        }
        context.reply(text)
    }

    private suspend fun trust(text: String, invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val folder = folder(TRUST, text, context, replies) ?: return
        val trusted = context.refusing { trust.trustFolder(invocation.chat, folder, invocation.issuer) } ?: return
        context.reply(replies.text(TextKeys.TRUST_DONE, "folder" to trusted, "agent" to context.turn.agent))
    }

    private suspend fun trustRule(
        rule: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val trusted = context.refusing { trust.trustRule(invocation.chat, rule, invocation.issuer) } ?: return
        val done = replies.text(TextKeys.TRUST_RULE_DONE, "rule" to trusted.rule, "agent" to context.turn.agent)
        context.reply(if (trusted.anyCode) "$done ${replies.text(TextKeys.TRUST_RULE_ANY_CODE)}" else done)
    }

    private suspend fun untrustRule(
        rule: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val revocation =
            context.refusing { trust.untrustRule(invocation.chat, rule, invocation.issuer) } ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.UNTRUST_RULE_DECLARED_REMOVED
            revocation.declared -> TextKeys.UNTRUST_RULE_DECLARED
            revocation.removed -> TextKeys.UNTRUST_RULE_DONE
            else -> TextKeys.UNTRUST_RULE_NOT_TRUSTED
        }
        context.reply(replies.text(key, "rule" to rule.trim(), "agent" to context.turn.agent))
    }

    private suspend fun untrust(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val folder = folder(UNTRUST, text, context, replies) ?: return
        val revocation = context.refusing { trust.untrustFolder(invocation.chat, folder, invocation.issuer) } ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.UNTRUST_DECLARED_REMOVED
            revocation.declared -> TextKeys.UNTRUST_DECLARED
            revocation.removed -> TextKeys.UNTRUST_DONE
            else -> TextKeys.UNTRUST_NOT_TRUSTED
        }
        context.reply(replies.text(key, "folder" to folder, "agent" to context.turn.agent))
    }

    private suspend fun trustPeople(
        people: List<ChatUser>,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val lines = people.map { person ->
            answered {
                trust.trustPerson(invocation.chat, person, invocation.issuer)
                replies.text(TextKeys.TRUST_PERSON_DONE, "person" to named(person), "agent" to context.turn.agent)
            }
        }
        context.reply(lines.joinToString("\n"))
    }

    private suspend fun untrustPeople(
        people: List<ChatUser>,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val lines = people.map { person ->
            answered {
                val revocation = trust.untrustPerson(invocation.chat, person, invocation.issuer)
                val key = when {
                    revocation.declared && revocation.removed -> TextKeys.UNTRUST_PERSON_DECLARED_REMOVED
                    revocation.declared -> TextKeys.UNTRUST_PERSON_DECLARED
                    revocation.removed -> TextKeys.UNTRUST_PERSON_DONE
                    else -> TextKeys.UNTRUST_PERSON_NOT_TRUSTED
                }
                replies.text(key, "person" to named(person), "agent" to context.turn.agent)
            }
        }
        context.reply(lines.joinToString("\n"))
    }

    private suspend fun trusted(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val agent = context.turn.agent
        val folders = context.refusing { trust.folders(invocation.chat) } ?: return
        val rules = context.refusing { trust.rules(invocation.chat) } ?: return
        val people = context.refusing { trust.people(invocation.chat) } ?: return
        val sections = mutableListOf(
            if (folders.isEmpty()) {
                replies.text(TextKeys.TRUST_NONE, "agent" to agent)
            } else {
                val list = replies.list(folders.map { described(it, replies) })
                replies.text(TextKeys.TRUST_LIST, "folders" to list, "agent" to agent)
            },
        )
        if (rules.isNotEmpty()) {
            val list = replies.list(rules.map { described(it, replies) })
            sections += replies.text(TextKeys.TRUST_RULES, "rules" to list, "agent" to agent)
        }
        sections += if (people.isEmpty()) {
            replies.text(TextKeys.TRUST_PEOPLE_NONE, "agent" to agent)
        } else {
            val list = replies.list(people.map { described(it, replies) })
            replies.text(TextKeys.TRUST_PEOPLE, "people" to list, "agent" to agent)
        }
        sections += replies.text(TextKeys.TRUST_PEOPLE_NOTE)
        context.reply(sections.joinToString("\n\n"))
    }

    /** [person] with who trusted them and when. */
    private fun described(person: TrustedPerson, replies: Replies): String {
        val by = person.trustedBy
        val date = dateOf(person.trustedAt, replies)
        return if (by == null) {
            replies.text(TextKeys.TRUST_PERSON_GRANTED_BY_HOST, "person" to named(person.person), "date" to date)
        } else {
            replies.text(
                TextKeys.TRUST_PERSON_GRANTED,
                "person" to named(person.person),
                "user" to by.displayName,
                "date" to date,
            )
        }
    }

    /** [rule] with where it comes from. */
    private fun described(rule: TrustRule, replies: Replies): String {
        val at = rule.grantedAt
        val by = rule.grantedBy
        return when {
            at == null -> replies.text(TextKeys.TRUST_RULE_DECLARED, "rule" to rule.rule)

            by == null ->
                replies.text(TextKeys.TRUST_RULE_GRANTED_BY_HOST, "rule" to rule.rule, "date" to dateOf(at, replies))

            else -> replies.text(
                TextKeys.TRUST_RULE_GRANTED,
                "rule" to rule.rule,
                "user" to by.displayName,
                "date" to dateOf(at, replies),
            )
        }
    }

    /** [folder] with where its trust comes from. */
    private fun described(folder: TrustedFolder, replies: Replies): String {
        val at = folder.trustedAt
        val by = folder.trustedBy
        return when {
            at == null -> replies.text(TextKeys.TRUST_DECLARED, "folder" to folder.path)

            folder.declared -> replies.text(
                TextKeys.TRUST_DECLARED_GRANTED,
                "folder" to folder.path,
                "user" to (by?.displayName ?: replies.text(TextKeys.VALUE_HOST)),
                "date" to dateOf(at, replies),
            )

            by == null ->
                replies.text(TextKeys.TRUST_GRANTED_BY_HOST, "folder" to folder.path, "date" to dateOf(at, replies))

            else -> replies.text(
                TextKeys.TRUST_GRANTED,
                "folder" to folder.path,
                "user" to by.displayName,
                "date" to dateOf(at, replies),
            )
        }
    }

    private fun dateOf(instant: Instant, replies: Replies): LocalDate =
        instant.atZone(replies.zone ?: clock.zone).toLocalDate()

    /** The absolute folder [text] names, null once the reply says why it names none. */
    private suspend fun folder(command: String, text: String, context: CommandContext, replies: Replies): Path? {
        val folder = try {
            Paths.get(text)
        } catch (e: InvalidPathException) {
            context.reply(replies.usage(command))
            return null
        }
        if (!folder.isAbsolute) {
            context.reply(replies.text(TextKeys.TRUST_RELATIVE, "folder" to text))
            return null
        }
        return folder
    }

    private companion object {
        const val TRUST = "trust"
        const val UNTRUST = "untrust"
        const val UNTAINT = "untaint"
        const val HEARTBEAT = "heartbeat"
    }
}

/** What [block] answers, or the message of the change it refused. */
private inline fun answered(block: () -> String): String = try {
    block()
} catch (e: ControlRefusedException) {
    e.message ?: throw e
}

/** [user]'s name, with their handle when the channel knows one. */
private fun named(user: ChatUser): String = user.displayName + user.username?.let { " (@$it)" }.orEmpty()

/** Whether [text] is written as a rule, `tool` or `tool(pattern)`, rather than as a folder. */
private fun isRule(text: String): Boolean = text.first() !in PATH_STARTS && RULE.matches(text)

private fun unquoted(text: String): String =
    if (text.length >= 2 && text.first() in QUOTES && text.last() == text.first()) text.drop(1).dropLast(1) else text

private const val QUOTES = "\"'"

private const val PATH_STARTS = "/~."

private val RULE = Regex("[A-Za-z*][A-Za-z0-9_.*]*(\\(.*\\))?", RegexOption.DOT_MATCHES_ALL)
