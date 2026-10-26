package org.unitedsoftware.alexandrite.channel.qqbot.mapping

import org.unitedsoftware.alexandrite.channel.qqbot.channel.QqBotMessageIndex
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotAttachment
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotMessageEvent
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotMsgElement
import org.unitedsoftware.alexandrite.sdk.channel.DisplayFact
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.Quote
import org.unitedsoftware.alexandrite.sdk.chat.QuoteTrust
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import java.time.Instant
import java.time.OffsetDateTime

/** The messages of a QQ bot instance, as the gateway reported them. */
internal object QqBotMessages {
    private val LEADING_MENTION = Regex("^\\s*<@!?[^>]+>\\s*")

    /**
     * The account the platform reports [event] from, by the open id of the single chat or, in a group, the member
     * id the platform names them by there alone.
     */
    fun authorId(event: QqBotMessageEvent): String {
        val group = !event.groupOpenid.isNullOrEmpty()
        return event.author.openid(group) ?: event.author.userOpenid ?: event.author.memberOpenid ?: "unknown"
    }

    /** The message [event] is, whose sender operates this instance when [isOperator]. */
    fun incoming(
        instance: ChannelInstanceId,
        event: QqBotMessageEvent,
        isOperator: Boolean,
        media: QqBotMedia,
        index: QqBotMessageIndex? = null,
    ): IncomingMessage {
        val group = !event.groupOpenid.isNullOrEmpty()
        val chat = QqBotChats.addressOf(instance, event)
        index?.record(chat, event.messageScene?.ext("msg_idx"), event.id)
        val authorId = authorId(event)
        val name = event.author.username?.takeIf { it.isNotBlank() } ?: authorId
        val sender = ChatUser(
            address = QqBotChats.user(instance, authorId),
            displayName = name,
            username = null,
            isBot = event.author.bot,
            isOperator = isOperator,
        )
        val quote = quote(event, chat, index)
        index?.recordQuote(chat, event.id, quote?.target?.id)
        return IncomingMessage.builder(
            ref = ChannelMessageRef(chat, event.id),
            sender = sender,
            chatInfo = ChatInfo(if (group) ChatKind.GROUP else ChatKind.DIRECT, null, null),
            receivedAt = instant(event.timestamp),
            forwarded = null,
        )
            .text(text(event))
            .media(attachments(event, media))
            .quote(quote)
            .facts(facts(event))
            .build()
    }

    /**
     * The text of [event] without the mention a full-mode event kept at its start.
     *
     * A `GROUP_AT_MESSAGE_CREATE` comes with the mention already stripped; a `GROUP_MESSAGE_CREATE` of the full
     * mode may still carry `<@…>` in front of the words, which is not part of what was said.
     */
    fun text(event: QqBotMessageEvent): String = event.content.replace(LEADING_MENTION, "")

    /** The media of [event], each with what reads the bytes its attachment names. */
    private fun attachments(event: QqBotMessageEvent, media: QqBotMedia): List<MediaAttachment> =
        event.attachments.map { attachment -> mediaOf(attachment, media) }

    /** The media of [attachment], with the kind its content type names. */
    private fun mediaOf(attachment: QqBotAttachment, media: QqBotMedia): MediaAttachment {
        val contentType = attachment.contentType
        val kind = when {
            contentType == "voice" -> MediaKind.AUDIO
            contentType == "file" -> MediaKind.FILE
            contentType?.startsWith("image/") == true -> MediaKind.IMAGE
            contentType?.startsWith("audio/") == true -> MediaKind.AUDIO
            contentType?.startsWith("video/") == true -> MediaKind.VIDEO
            else -> MediaKind.FILE
        }
        val mediaType = when {
            contentType == null || contentType == "voice" || contentType == "file" -> "application/octet-stream"
            else -> contentType
        }
        val url = attachment.voiceWavUrl ?: attachment.url
        val builder = MediaAttachment.builder(kind, mediaType, media.content(url.orEmpty()))
            .size(attachment.size)
            .width(attachment.width)
            .height(attachment.height)
            .name(attachment.filename)
        if (url == null) builder.unavailable("the platform named no address for it")
        return builder.build()
    }

    /** What a message of several quoted ones says, which the platform nests as elements. */
    private fun quote(event: QqBotMessageEvent, chat: ChatAddress, index: QqBotMessageIndex?): Quote? {
        if (event.msgElements.isEmpty()) return null
        val text = elementsText(event.msgElements)
        val reference = event.messageScene?.ext("ref_msg_idx") ?: event.msgElements.firstOrNull()?.msgIdx
        val target = index?.resolve(chat, reference)?.let { ChannelMessageRef(chat, it) }
        val builder = Quote.builder(text).trust(QuoteTrust.UNTRUSTED)
        if (target != null) builder.target(target)
        return builder.build()
    }

    /** The text of [elements] and what they nest, a blank line between two of them. */
    private fun elementsText(elements: List<QqBotMsgElement>): String = elements.joinToString("\n\n") { element ->
        val own = element.content.orEmpty()
        val nested = elementsText(element.msgElements)
        listOf(own, nested).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** What the agent is shown of [event] besides its text and media. */
    private fun facts(event: QqBotMessageEvent): List<DisplayFact> = buildList {
        event.arkData?.let { card ->
            val label = card.arkName ?: card.arkType
            val value = listOfNotNull(label, card.prompt).joinToString(": ").ifBlank { "a card" }
            add(DisplayFact("card", value))
        }
        if (event.mentions.isNotEmpty()) {
            val names = event.mentions.mapNotNull { it.username?.takeIf(String::isNotBlank) }
            add(DisplayFact("mentions", if (names.isEmpty()) "${event.mentions.size}" else names.joinToString(", ")))
        }
        for (attachment in event.attachments) {
            add(DisplayFact("attachment", attachment.filename ?: attachment.contentType ?: "unnamed"))
        }
    }

    /** When the platform received the message, now when it named no time this version can read. */
    private fun instant(text: String?): Instant = runCatching {
        if (text == null) return@runCatching Instant.now()
        OffsetDateTime.parse(text).toInstant()
    }.getOrElse { Instant.now() }
}
