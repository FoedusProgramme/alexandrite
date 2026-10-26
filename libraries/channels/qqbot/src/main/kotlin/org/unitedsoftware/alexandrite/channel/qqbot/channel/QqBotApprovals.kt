package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.common.approval.ApprovalEnding
import org.unitedsoftware.alexandrite.channel.common.approval.ApprovalRegistry
import org.unitedsoftware.alexandrite.channel.common.approval.PendingApproval
import org.unitedsoftware.alexandrite.channel.common.approval.carrying
import org.unitedsoftware.alexandrite.channel.common.approval.decideApproval
import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.qqbot.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * Asks administrators for approval with a message whose buttons carry the answer.
 *
 * A button is an instruction button: a press writes `/approve <id>` or `/deny <id>` as a message, and the platform may
 * send it by itself where it supports that. The message comes back through the inbox like any other message, which
 * answers the waiting request if the sender is an administrator. A message of this platform cannot be changed after it
 * is sent, so the outcome is said in a second message.
 */
@ChannelInstanceScoped
internal class QqBotApprovals(
    private val instance: ChannelInstance,
    private val config: QqBotInstanceConfig,
    private val sender: QqBotSender,
    private val progress: QqBotProgress,
    private val texts: Texts,
    /** The chats this instance serves, whose pairing an admin's own direct chat may need before it can answer. */
    private val gate: QqBotAccessGate,
) : Lifecycle {
    private val pending = ApprovalRegistry()

    /** The answer of the first administrator who presses a button of [request], else how the request ended. */
    suspend fun ask(request: ApprovalRequest): ApprovalAnswer {
        val waiting = pending.register(request)
            ?: return ApprovalAnswer.Unavailable("another request has the id ${request.id}")
        try {
            val shown = show(waiting)
            if (shown.isEmpty()) {
                logger.warn("{} could not show approval {} in any chat", instance.id, request.id)
                return ApprovalAnswer.Unavailable(text(TextKeys.APPROVAL_UNREACHABLE, request.turn.language))
            }
            // The answer comes after the approval message, so the turn's stream ends here.
            progress.deferChat(request.turn.chat)
            logger.info(
                "{} asks for approval {} of {} in turn {} in {} chat(s)",
                instance.id,
                request.id,
                request.tool,
                request.turn.id,
                shown.size,
            )
            withTimeoutOrNull(config.approvalMinutes.minutes) { waiting.ending.await() }
            waiting.ending.complete(ApprovalEnding.Expired)
            val ending = waiting.ending.await()
            conclude(waiting, ending)
            return ending.answer
        } catch (e: CancellationException) {
            waiting.ending.complete(ApprovalEnding.Withdrawn)
            conclude(waiting, ApprovalEnding.Withdrawn)
            throw e
        } finally {
            pending.forget(request.id)
        }
    }

    /** Whether [name] is a command this desk answers. */
    fun answers(name: String): Boolean = name == QqBotApprovalCommands.APPROVE || name == QqBotApprovalCommands.DENY

    /** Takes [name] and [id] that [by] wrote in [chat], returning what to reply, null when nothing should be said. */
    suspend fun answer(name: String, id: String, always: Boolean, by: ChatUser, chat: ChatAddress): String? {
        val waiting = pending[id]
            ?: return text(TextKeys.APPROVAL_NOTHING_WAITING, null)
        if (!waiting.carries(chat)) return null
        val allow = name != QqBotApprovalCommands.DENY
        val answer = decideApproval(waiting.request, by, allow, always)
            ?: return text(TextKeys.APPROVAL_ADMINS_ONLY, waiting.request.turn.language)
        if (!waiting.ending.complete(ApprovalEnding.Answered(answer))) return null
        logger.info("{}: approval {} of {} answered by {}", instance.id, id, waiting.request.tool, by.address)
        return null
    }

    override fun onStop() {
        pending.stopAll("the bot stopped")
    }

    /** Shows the request of [waiting] in each chat it is meant for, returning the chats that took it. */
    private suspend fun show(waiting: PendingApproval): List<ChatAddress> {
        val request = waiting.request
        val shown = mutableListOf<ChatAddress>()
        for (chat in recipients(request)) {
            // carrying() registers the chat before send() can take the message: the button can be pressed as soon
            // as the message is delivered, which may be before sendApproval returns here, and undoes the
            // registration whenever nothing ends up shown after all — a cancellation included.
            val delivered = waiting.carrying(chat) {
                sender.sendApproval(
                    chat,
                    render(request),
                    request.id,
                    text(TextKeys.APPROVAL_ALLOW, request.turn.language),
                    text(TextKeys.APPROVAL_DENY, request.turn.language),
                    request.alwaysRules.firstOrNull()?.let {
                        text(TextKeys.APPROVAL_ALWAYS_SHORT, request.turn.language)
                    },
                ) is Delivery.Delivered
            }
            if (delivered) {
                shown += chat
            } else {
                logger.warn("{}: approval {} was not shown in {}", instance.id, request.id, chat)
            }
        }
        return shown
    }

    /**
     * The chats [request] is shown in: the chat of the turn, and the administrators' own paired chats for a
     * member's turn.
     */
    private fun recipients(request: ApprovalRequest): List<ChatAddress> {
        val chats = mutableListOf(request.turn.chat)
        if (!request.turn.actorIsOperator) {
            config.admins.mapTo(chats) { ChatAddress(instance.id, "private:$it") }
        }
        return chats.distinct().filter { gate.isPaired(it) }
    }

    /** Says how [waiting] ended in every chat that carries it. */
    private suspend fun conclude(waiting: PendingApproval, ending: ApprovalEnding) {
        val request = waiting.request
        val said = when (ending) {
            ApprovalEnding.Expired -> text(TextKeys.APPROVAL_EXPIRED, request.turn.language, "id" to request.id)

            ApprovalEnding.Withdrawn -> text(TextKeys.APPROVAL_WITHDRAWN, request.turn.language, "id" to request.id)

            is ApprovalEnding.Answered -> when (ending.answer) {
                is ApprovalAnswer.Allowed ->
                    text(TextKeys.APPROVAL_ALLOWED, request.turn.language, "id" to request.id)

                is ApprovalAnswer.Denied ->
                    text(TextKeys.APPROVAL_DENIED, request.turn.language, "id" to request.id)

                else -> return
            }
        }
        for (chat in waiting.carriedChats) {
            try {
                sender.send(chat, OutboundMessage.builder(said, MessageKind.NOTICE).build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "{}: could not say the outcome of approval {} in {}: {}",
                    instance.id,
                    request.id,
                    chat,
                    e.message,
                )
            }
        }
    }

    /** The text of [request] shown with its buttons. */
    private fun render(request: ApprovalRequest): String = buildString {
        appendLine(text(TextKeys.APPROVAL_TITLE, request.turn.language, "id" to request.id))
        appendLine(
            text(
                TextKeys.APPROVAL_TOOL,
                request.turn.language,
                "tool" to request.tool,
                "risk" to request.risk.name.lowercase(),
            ),
        )
        appendLine(text(TextKeys.APPROVAL_REASON, request.turn.language, "reason" to request.reason))
        request.alwaysRules.forEach { rule ->
            appendLine(text(TextKeys.APPROVAL_ALWAYS, request.turn.language, "rule" to rule.display))
        }
        appendLine(text(TextKeys.APPROVAL_ARGUMENTS, request.turn.language))
        append(request.arguments.take(MAX_ARGUMENTS))
    }

    private fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String =
        texts.text(key, language, *args)

    private companion object {
        const val MAX_ARGUMENTS = 1_500

        val logger = LoggerFactory.getLogger(QqBotApprovals::class.java)
    }
}

/** The approval commands a message of this channel may carry. */
internal object QqBotApprovalCommands {
    const val APPROVE = "approve"

    const val DENY = "deny"

    private val PRESS = Regex(
        "^/($APPROVE|$DENY)(?:@[A-Za-z0-9_]+)?\\s+([A-Za-z0-9_-]{1,32})(?:\\s+(always))?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** The command [text] carries, null when it is no approval command. */
    fun of(text: String): Parsed? {
        // A group may put the mention of the bot before the data of a button; the command starts after it.
        val command = text.trim().replace(LEADING_MENTIONS, "")
        val match = PRESS.matchEntire(command) ?: return null
        return Parsed(match.groupValues[1].lowercase(), match.groupValues[2], match.groupValues[3].isNotEmpty())
    }

    private val LEADING_MENTIONS = Regex("^(@\\S+\\s+)+")

    class Parsed(val name: String, val id: String, val always: Boolean)
}
