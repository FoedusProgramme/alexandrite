package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
) : Lifecycle {
    private val pending = ConcurrentHashMap<String, Pending>()

    /** The answer of the first administrator who presses a button of [request], else how the request ended. */
    suspend fun ask(request: ApprovalRequest): ApprovalAnswer {
        val waiting = Pending(request)
        if (pending.putIfAbsent(request.id, waiting) != null) {
            return ApprovalAnswer.Unavailable("another request has the id ${request.id}")
        }
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
            val answered = withTimeoutOrNull(config.approvalMinutes.minutes) { waiting.ending.await() }
            val answer = answered ?: ApprovalAnswer.Expired
            waiting.ending.complete(answer)
            conclude(waiting, answer)
            return answer
        } catch (e: CancellationException) {
            waiting.ending.complete(ApprovalAnswer.Unavailable("the turn was withdrawn"))
            conclude(waiting, ApprovalAnswer.Unavailable("the turn was withdrawn"))
            throw e
        } finally {
            pending.remove(request.id)
        }
    }

    /** Whether [name] is a command this desk answers. */
    fun answers(name: String): Boolean = name == QqBotApprovalCommands.APPROVE || name == QqBotApprovalCommands.DENY

    /** Takes [name] and [id] that [by] wrote in [chat], returning what to reply, null when nothing should be said. */
    suspend fun answer(name: String, id: String, always: Boolean, by: ChatUser, chat: ChatAddress): String? {
        val waiting = pending[id]
            ?: return text(TextKeys.APPROVAL_NOTHING_WAITING, null)
        if (chat !in waiting.chats) return null
        if (!by.isOperator) return text(TextKeys.APPROVAL_ADMINS_ONLY, waiting.request.turn.language)
        val answer = if (name == QqBotApprovalCommands.DENY) {
            ApprovalAnswer.Denied(by)
        } else {
            ApprovalAnswer.Allowed(by, always && waiting.request.alwaysRules.isNotEmpty())
        }
        if (!waiting.ending.complete(answer)) return null
        logger.info("{}: approval {} of {} answered by {}", instance.id, id, waiting.request.tool, by.address)
        return null
    }

    override fun onStop() {
        pending.values.forEach { it.ending.complete(ApprovalAnswer.Unavailable("the bot stopped")) }
    }

    /** Shows the request of [waiting] in each chat it is meant for, returning the chats that took it. */
    private suspend fun show(waiting: Pending): List<ChatAddress> {
        val request = waiting.request
        val shown = mutableListOf<ChatAddress>()
        for (chat in recipients(request)) {
            // Register the chat before the platform can send its press: the button can be pressed as soon as the
            // message is delivered, which may be before sendApproval returns here.
            waiting.chats += chat
            val delivery = sender.sendApproval(
                chat,
                render(request),
                request.id,
                text(TextKeys.APPROVAL_ALLOW, request.turn.language),
                text(TextKeys.APPROVAL_DENY, request.turn.language),
                request.alwaysRules.firstOrNull()?.let {
                    text(TextKeys.APPROVAL_ALWAYS, request.turn.language, "rule" to it.display)
                },
            )
            if (delivery is Delivery.Delivered) {
                shown += chat
            } else {
                waiting.chats.remove(chat)
                logger.warn("{}: approval {} was not shown in {}: {}", instance.id, request.id, chat, delivery)
            }
        }
        return shown
    }

    /** The chats [request] is shown in: the chat of the turn, and the administrators' own chats for a member's turn. */
    private fun recipients(request: ApprovalRequest): List<ChatAddress> {
        val chats = mutableListOf(request.turn.chat)
        if (!request.turn.actorIsOperator) {
            config.admins.mapTo(chats) { ChatAddress(instance.id, "private:$it") }
        }
        return chats.distinct()
    }

    /** Says [answer] in every chat that showed [waiting]. */
    private suspend fun conclude(waiting: Pending, answer: ApprovalAnswer) {
        val request = waiting.request
        val said = when (answer) {
            is ApprovalAnswer.Allowed -> text(TextKeys.APPROVAL_ALLOWED, request.turn.language, "id" to request.id)
            is ApprovalAnswer.Denied -> text(TextKeys.APPROVAL_DENIED, request.turn.language, "id" to request.id)
            ApprovalAnswer.Expired -> text(TextKeys.APPROVAL_EXPIRED, request.turn.language, "id" to request.id)
            else -> return
        }
        for (chat in waiting.chats) {
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
        appendLine(text(TextKeys.APPROVAL_ARGUMENTS, request.turn.language))
        append(request.arguments.take(MAX_ARGUMENTS))
    }

    private fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String =
        texts.text(key, language, *args)

    private class Pending(val request: ApprovalRequest) {
        val ending = CompletableDeferred<ApprovalAnswer>()

        val chats = CopyOnWriteArrayList<ChatAddress>()
    }

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
