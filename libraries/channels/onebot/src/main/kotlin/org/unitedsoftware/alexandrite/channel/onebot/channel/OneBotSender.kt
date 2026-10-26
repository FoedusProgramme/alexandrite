package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotMessages
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotMessages.MAX_FILE_BYTES
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SendGroupMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SendPrivateMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegmentCodec
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.util.Base64

/**
 * What this instance sends, and the record of what it sent.
 *
 * Every delivery goes through here: the answer of a turn, the notice of a refusal, and the message that shows an
 * approval request. The ids that come back are remembered as they arrive, because what answers the bot is told from
 * them and from nothing else.
 */
@ChannelInstanceScoped
internal class OneBotSender(private val link: OneBotLink, private val sent: OneBotSentMessages) {
    private val logger = LoggerFactory.getLogger(OneBotSender::class.java)

    /**
     * The action that sends a message to [chat], null when this instance serves no such chat.
     *
     * A temporary session of a group is a private chat whose thread names the sender, so it is asked for first: a reply
     * to it goes to the sender and not into the group, which is what its address means.
     */
    fun action(chat: ChatAddress): Pair<String, ChatAddress>? = when {
        OneBotMessages.privateTemporary(chat) != null -> "send_private_msg" to chat
        OneBotMessages.isGroup(chat) && OneBotMessages.group(chat) != null -> "send_group_msg" to chat
        OneBotMessages.privateUser(chat) != null -> "send_private_msg" to chat
        else -> null
    }

    /** The parameters that send [message] to [chat], null when this instance serves no such chat. */
    fun params(chat: ChatAddress, message: OutboundMessage): JsonObject? {
        val temporary = OneBotMessages.privateTemporary(chat)
        val user = OneBotMessages.privateUser(chat)
        val group = OneBotMessages.group(chat)
        val segments = OneBotMessages.outgoing(chat, message)
        return when {
            temporary != null -> SendPrivateMessage(temporary, segments).toJson()
            group != null -> SendGroupMessage(group, segments).toJson()
            user != null -> SendPrivateMessage(user, segments).toJson()
            else -> null
        }
    }

    /**
     * Sends [message] to [chat], remembering the ids of what it delivered.
     *
     * A message that carries files goes as its files alone: this platform shows each file's own name and size, so a
     * line of text beside them says nothing the file does not. A file past [MAX_FILE_BYTES] is refused before any of
     * them is sent, so a chat is never told half of what a turn meant to say.
     */
    suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery {
        message.files.firstOrNull { it.size > MAX_FILE_BYTES }?.let { file ->
            return Delivery.NotDelivered(
                DeliveryFailure.UNKNOWN,
                "The file holds ${file.size} bytes, more than the $MAX_FILE_BYTES bytes a chat of this platform takes.",
            )
        }
        val target = action(chat)
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        var delivery: Delivery? = null
        if (message.files.isEmpty() && message.text.isNotBlank()) {
            val params = params(chat, message)
                ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
            delivery = remember(OneBotMessages.delivery(link.call(target.first, params), chat))
            log(message.kind.toString(), chat, delivery, said(message.text))
        }
        for (file in message.files) {
            val (action, params) = upload(chat, file) ?: continue
            // An upload is not a message, so the implementation names no message of it. The answer is matched to
            // this call alone and is not remembered: what an answer is matched against is the id of a message a chat
            // can answer, and an upload names none.
            delivery = try {
                OneBotMessages.delivery(link.call(action, params, UPLOAD_WAIT_MILLIS), chat)
            } catch (e: CancellationException) {
                // The wait asked for ran out while the platform was still taking the file in, and an implementation
                // goes on taking it: the file may arrive after the turn was told it did not, so a reader of this is
                // told to look at the chat before sending it again. A turn that gave up itself travels as it is.
                if (!currentCoroutineContext().isActive) throw e
                Delivery.NotDelivered(
                    DeliveryFailure.UNKNOWN,
                    "The platform did not answer in time about '${file.name}', which it may still be taking in; " +
                        "look at the chat before sending it again.",
                )
            }
            if (delivery is Delivery.Delivered) {
                logger.info("Uploaded {} of {} bytes to {}", file.name, file.size, chat)
            } else {
                val failure = delivery as? Delivery.NotDelivered
                logger.warn(
                    "Could not upload {} to {}: {}: {}",
                    file.name,
                    chat,
                    failure?.kind,
                    failure?.detail.orEmpty(),
                )
            }
        }
        return delivery ?: Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
    }

    /**
     * Logs what became of [delivery] of [what] to [chat], which [about] describes where it arrived.
     *
     * What came back is written as it is: a delivery that did not happen is logged as a failure with the reason the
     * platform gave, never as a send, which is what a reader of the log took for one.
     */
    private fun log(what: String, chat: ChatAddress, delivery: Delivery, about: String) {
        if (delivery is Delivery.Delivered) {
            val ids = delivery.messages.joinToString { it.id }
            logger.info("Sent {} to {} as [{}]: {}", what, chat, ids, about)
        } else {
            val failure = delivery as? Delivery.NotDelivered
            logger.warn(
                "Could not send {} to {}: {}: {}",
                what,
                chat,
                failure?.kind,
                failure?.detail.orEmpty(),
            )
        }
    }

    /**
     * The action and parameters that upload [file] to [chat], null when this instance serves no such chat.
     *
     * The bytes go as `base64://`, which is how a file the implementation cannot reach itself is handed over, and the
     * name is what the chat shows. A temporary session of a group uploads to the sender, which is whom its address
     * means, as its replies do.
     */
    private fun upload(chat: ChatAddress, file: OutboundFile): Pair<String, JsonObject>? {
        val carried = BYTES_PREFIX + Base64.getEncoder().encodeToString(file.bytes())
        val temporary = OneBotMessages.privateTemporary(chat)
        val group = OneBotMessages.group(chat)
        val user = OneBotMessages.privateUser(chat)
        return when {
            group != null -> "upload_group_file" to buildJsonObject {
                put("group_id", OneBotSegmentCodec.number(group.value))
                put("file", carried)
                put("name", file.name)
            }

            temporary != null -> "upload_private_file" to buildJsonObject {
                put("user_id", OneBotSegmentCodec.number(temporary.value))
                put("file", carried)
                put("name", file.name)
            }

            user != null -> "upload_private_file" to buildJsonObject {
                put("user_id", OneBotSegmentCodec.number(user.value))
                put("file", carried)
                put("name", file.name)
            }

            else -> null
        }
    }

    /** [text] as a log line shows it: one line, cut short, so that a long answer cannot flood the console. */
    private fun said(text: String): String {
        val one = text.replace("\n", " ")
        return if (one.length <= SAID_LIMIT) one else one.take(SAID_LIMIT) + "…"
    }

    /** Remembers the ids of what [delivery] delivered, which is what a reply is matched against. */
    private fun remember(delivery: Delivery): Delivery {
        (delivery as? Delivery.Delivered)?.messages?.forEach { sent.remember(it.id) }
        return delivery
    }
}

/** How much of a message a log line shows. */
private const val SAID_LIMIT: Int = 120

/**
 * What the platform is told a file's bytes are, which is how it is handed one it cannot reach itself.
 *
 * A file of a chat cannot be read from this machine by the implementation, so its bytes travel in the call. The
 * tools of this channel hand one over the same way.
 */
private const val BYTES_PREFIX: String = "base64://"

/**
 * How long an upload of a file waits for the platform's answer.
 *
 * An implementation takes in a large file before it answers, which is longer than the instance's own timeout of a call
 * allows: a file of thirty megabytes has taken about a minute. A call that gives up sooner is answered as a failure
 * while the upload goes on, so the file arrives and the turn is told it did not.
 */
private const val UPLOAD_WAIT_MILLIS: Long = 10 * 60 * 1000
