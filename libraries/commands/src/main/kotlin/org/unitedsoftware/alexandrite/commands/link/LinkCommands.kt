package org.unitedsoftware.alexandrite.commands.link

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.commandWord
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.groupOf
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.internal.pairing.Redemption
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** `/link` and `/unlink`. */
@Singleton
@Contribute(CommandHandler::class)
internal class LinkCommands(
    private val links: ChatLinks,
    private val settings: ChatSettings,
    private val channels: ChannelDirectory,
    private val codes: LinkCodes,
    private val texts: Texts,
    private val clock: Clock,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(LINK), texts.spec(UNLINK))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val argument = invocation.arguments.trim()
        when (val name = commandWord(invocation.name)) {
            LINK -> when {
                argument.isEmpty() -> offer(invocation, context, replies)
                else -> redeem(argument, invocation, context, replies)
            }

            UNLINK -> {
                val words = words(argument)
                val target = words.singleOrNull()?.let(ChatAddress::parseOrNull)
                when {
                    words.isEmpty() -> unlink(null, invocation, context, replies)
                    target == null -> context.reply(replies.usage(name))
                    else -> unlink(target, invocation, context, replies)
                }
            }
        }
    }

    private suspend fun offer(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val chat = invocation.chat
        val agent = context.turn.agent
        val code = codes.offer(LinkOffer(agent, chat, invocation.issuer.address))
        val offer = replies.text(
            TextKeys.LINK_OFFER,
            "agent" to agent,
            "code" to code.text,
            "minutes" to minutesUntil(code.expiresAt),
        )
        val current = links.groupOf(agent, chat)?.let { group ->
            val others = (listOf(group.anchor) + group.members).filter { it != chat }
            replies.text(TextKeys.LINK_CURRENT, "chats" to others.joinToString(", ") { markdownLiteral("$it") })
        }
        context.reply(listOfNotNull(offer, current).joinToString("\n\n"), Markup.MARKDOWN, forModel = false)
    }

    private suspend fun redeem(
        input: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        when (val redemption = codes.redeem(input, invocation.chat)) {
            is Redemption.Redeemed -> link(redemption.payload, invocation, context, replies)

            is Redemption.Rejected ->
                context.reply(replies.text(TextKeys.LINK_REJECTED, "tries" to redemption.triesLeft))

            is Redemption.Locked ->
                context.reply(replies.text(TextKeys.LINK_LOCKED, "minutes" to minutesUntil(redemption.until)))

            Redemption.OwnCode -> context.reply(replies.text(TextKeys.LINK_OWN_CODE))

            Redemption.Malformed -> context.reply(replies.text(TextKeys.LINK_MALFORMED))
        }
    }

    private suspend fun link(
        offer: LinkOffer,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val chat = invocation.chat
        context.refusing { links.link(offer.agent, offer.anchor, chat, invocation.issuer) } ?: return
        logger.info(
            "{} is linked to {} at agent '{}' with a code that {} offered",
            chat,
            offer.anchor,
            offer.agent,
            offer.by,
        )
        val current = context.turn.agent
        val lines = mutableListOf(replies.text(TextKeys.LINK_LINKED, "agent" to offer.agent, "anchor" to offer.anchor))
        if (current != offer.agent) {
            lines += replies.text(TextKeys.LINK_OTHER_AGENT, "current" to current, "agent" to offer.agent)
        }
        context.reply(lines.joinToString("\n"))
        tellAnchor(offer, chat)
    }

    /** Tells the anchor of [offer] that [member] joined. */
    private suspend fun tellAnchor(offer: LinkOffer, member: ChatAddress) {
        val anchor = offer.anchor
        val delivery = try {
            val language = try {
                settings.snapshot(anchor).effectiveLanguage
            } catch (_: ControlRefusedException) {
                null
            }
            val text = texts.text(
                TextKeys.LINK_ANCHOR_NOTICE,
                language,
                "chat" to member,
                "agent" to offer.agent,
            )
            channels.channel(anchor.instance)?.send(anchor, OutboundMessage.builder(text, MessageKind.NOTICE).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot tell {} that {} is linked to it: {}", anchor, member, e.toString())
            return
        }
        if (delivery !is Delivery.Delivered) {
            logger.warn(
                "Cannot tell {} that {} is linked to it: {}",
                anchor,
                member,
                delivery ?: "its channel is closed",
            )
        }
    }

    private suspend fun unlink(
        target: ChatAddress?,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val chat = invocation.chat
        val agent = context.turn.agent
        if (target != null) {
            val group = links.groupOf(agent, chat)
            if (group == null) {
                context.reply(replies.text(TextKeys.UNLINK_NOT_LINKED, "agent" to agent))
                return
            }
            if (target != group.anchor && target !in group.members) {
                context.reply(replies.text(TextKeys.UNLINK_NOT_IN_GROUP, "chat" to target))
                return
            }
        }
        val member = target ?: chat
        val revocation = context.refusing { links.unlink(agent, member, invocation.issuer) } ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.UNLINK_DECLARED_REMOVED
            revocation.declared -> TextKeys.UNLINK_DECLARED
            revocation.removed -> TextKeys.UNLINK_DONE
            else -> TextKeys.UNLINK_NOT_LINKED
        }
        context.reply(replies.text(key, "chat" to member, "agent" to agent))
    }

    /** The whole minutes from now until [instant], at least 1. */
    private fun minutesUntil(instant: Instant): Long {
        val millis = Duration.between(clock.instant(), instant).toMillis()
        return maxOf(1, -Math.floorDiv(-millis, MILLIS_PER_MINUTE))
    }

    private companion object {
        const val LINK = "link"
        const val UNLINK = "unlink"
        const val MILLIS_PER_MINUTE = 60_000L
        val logger = LoggerFactory.getLogger(LinkCommands::class.java)
    }
}

/** [text] with the characters that start inline Markdown escaped. */
private fun markdownLiteral(text: String): String = text.replace(MARKDOWN_SIGNS) { "\\${it.value}" }

private val MARKDOWN_SIGNS = Regex("[\\\\`*_~\\[\\]<&]")
