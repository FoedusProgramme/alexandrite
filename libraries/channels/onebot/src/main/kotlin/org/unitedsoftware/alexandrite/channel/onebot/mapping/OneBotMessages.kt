package org.unitedsoftware.alexandrite.channel.onebot.mapping

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotMessageInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegmentCodec
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotFailureKind
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.DisplayFact
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.channel.MediaContent
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.Quote
import org.unitedsoftware.alexandrite.sdk.chat.QuoteTrust
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import java.time.Instant

/**
 * The chats of an OneBot instance, printed the way the rest of Alexandrite reads them.
 *
 * A private chat is `private:<user id>` and a group is `group:<group id>`, the two ids the standard reports. A
 * temporary session, which is a private message that came from inside a group, keeps the group as the chat and the
 * sender as its thread, so that a reply goes back to the sender.
 */
public object OneBotChats {
    /** The prefix of a private chat. */
    public const val PRIVATE: String = "private"

    /** The prefix of a group chat. */
    public const val GROUP: String = "group"

    /** The chat [event] happened in. */
    public fun addressOf(instance: ChannelInstanceId, event: OneBotEvent.Message): ChatAddress = when (event) {
        is OneBotEvent.Message.Private -> if (event.subType == OneBotEvent.Message.GROUP_TEMPORARY) {
            ChatAddress(instance, tempChat(event), thread = event.userId.value)
        } else {
            ChatAddress(instance, "$PRIVATE:${event.userId}")
        }

        is OneBotEvent.Message.Group -> ChatAddress(instance, "$GROUP:${event.groupId}")
    }

    /** The user of [id] in this instance. */
    public fun user(instance: ChannelInstanceId, id: UserId): UserAddress = UserAddress(instance, id.value)

    /** The chat `private:<id>`. */
    public fun privateChat(instance: ChannelInstanceId, id: UserId): ChatAddress =
        ChatAddress(instance, "$PRIVATE:${id.value}")

    /** The chat `group:<id>`. */
    public fun groupChat(instance: ChannelInstanceId, id: GroupId): ChatAddress =
        ChatAddress(instance, "$GROUP:${id.value}")

    /** The group a temporary session came from, as the implementation reported it, or a chat of the sender alone. */
    private fun tempChat(event: OneBotEvent.Message.Private): String {
        val group = (event.raw["group_id"] as? JsonPrimitive)?.content
        return if (group.isNullOrEmpty()) "$PRIVATE:temporary" else "$GROUP:$group"
    }
}

/**
 * The messages of an OneBot instance, in both directions.
 *
 * A segment the SDK has no shape for is not dropped: it becomes a [DisplayFact] on the way in, so the model still
 * reads that it was there, and every message keeps the raw event, so a consumer that needs the original sees it.
 */
public object OneBotMessages {
    /** What an OneBot channel can do, as this module reports it. */
    // The kinds of turn event a chat is told of while a turn works.
    private val TOOL_EVENTS: Set<TurnEventKind> =
        setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED)

    public fun capabilities(
        proactive: Boolean,
        /** Whether this instance tells a chat what a turn is doing while it works. */
        progress: Boolean = true,
        /** Whether this instance shows what the model says while a turn works on tools. */
        streaming: Boolean = true,
    ): ChannelCapabilities = ChannelCapabilities.builder()
        .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
        .streaming(streaming)
        .finalReplacesPreview(false)
        .proactive(proactive)
        .events(if (progress) TOOL_EVENTS else emptySet())
        .maxPartsPerReply(null)
        .files(true)
        .maxFileBytes(MAX_FILE_BYTES)
        .build()

/**
     * The bytes one file of a chat may hold, which is the ten gigabytes this platform takes.
     *
     * A larger one is refused, the way the telegram channel refuses one past what a bot of its own platform may
     * send.
     */
    internal const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024 * 1024

    /**
     * The message [event] is, where [fetched] is what the implementation reported about the message it answers.
     *
     * A report says which message is answered and nothing about it, so what it says is read from the implementation
     * before this is called; without it the quote names the message that is answered and carries no text.
     */
    public fun incoming(
        instance: ChannelInstanceId,
        event: OneBotEvent.Message,
        admins: Set<String> = emptySet(),
        quoted: QuotedMessage? = null,
        /** How the bytes of a reported image, voice or video are read, null when they cannot be. */
        reader: MediaReader? = null,
    ): IncomingMessage {
        val chat = OneBotChats.addressOf(instance, event)
        val segments = event.message.segments
        return IncomingMessage.builder(
            ref = ChannelMessageRef(chat, event.messageId.value),
            sender = sender(instance, event, admins),
            chatInfo = chatInfo(event),
            receivedAt = Instant.ofEpochSecond(event.time),
            forwarded = null,
        )
            .text(segments.filterIsInstance<OneBotSegment.Text>().joinToString("") { it.text })
            // The quoted message's own media too, since a person who quotes a picture and asks about it is asking
            // about that picture: without this the quote reaches the model as the marker for an image and no image.
            .media(media(segments, reader) + media(quoted?.segments.orEmpty(), reader))
            .quote(quote(event, chat, quoted))
            .facts(facts(event, segments))
            .build()
    }

    /** The message that sends [message] in [chat]. */
    public fun outgoing(chat: ChatAddress, message: OutboundMessage): OneBotMessage {
        val segments = when (message.markup.id) {
            Markup.MARKDOWN.id -> OneBotMarkdown.segments(message.text)
            else -> listOf(OneBotSegment.Text(message.text))
        }
        // A reply names a message of the implementation by its numeric id. A reference this version made up because
        // no id came back, `sent` or `async`, names no message there, so a message that answers one is sent without
        // the reply segment.
        val reply = message.replyTo
            ?.id
            ?.takeIf { it.toLongOrNull() != null }
            ?.let { OneBotSegment.Reply(MessageId(it)) }
        return OneBotMessage.ArrayValue(if (reply == null) segments else listOf(reply) + segments)
    }

    /** Whether [chat] is a group, which decides the action a message is sent with. */
    public fun isGroup(chat: ChatAddress): Boolean = chat.chat.startsWith(OneBotChats.GROUP + ":")

    /**
     * The user a temporary session belongs to, null when [chat] is no such session.
     *
     * A temporary session is a message of a group sent privately, and its thread is the user who sent it. A reply has
     * to go there.
     */
    public fun privateTemporary(chat: ChatAddress): UserId? = chat.thread
        ?.takeIf { isGroup(chat) && it.isNotEmpty() }
        ?.let(::UserId)

    /** The user a private chat belongs to, null when [chat] is no private chat. */
    public fun privateUser(chat: ChatAddress): UserId? = chat.chat
        .takeIf { it.startsWith(OneBotChats.PRIVATE + ":") }
        ?.removePrefix(OneBotChats.PRIVATE + ":")
        ?.takeIf { it.isNotEmpty() }
        ?.let(::UserId)

    /** The group a group chat belongs to, null when [chat] is no group chat. */
    public fun group(chat: ChatAddress): GroupId? = chat.chat
        .takeIf { it.startsWith(OneBotChats.GROUP + ":") }
        ?.removePrefix(OneBotChats.GROUP + ":")
        ?.takeIf { it.isNotEmpty() }
        ?.let(::GroupId)

    /** What a call that ended in [result] means for the delivery of a message to [chat]. */
    public fun delivery(result: OneBotResult<JsonElement>, chat: ChatAddress): Delivery = when (result) {
        is OneBotResult.Ok -> {
            // The data of the answer of a send is either the object that names the message or the message itself,
            // which is what an implementation that answers with the identifier alone sends.
            val data = (result.data as? JsonObject)?.get("data") as? JsonObject ?: result.data
            // The `echo` of an answer is the key this client sent to match it to its call, so an answer that names no
            // message is a delivery this version cannot name.
            val id =
                ((data as? JsonObject)?.get("message_id") as? JsonPrimitive)?.content
                    ?: "sent"
            Delivery.Delivered(listOf(ChannelMessageRef(chat, id)))
        }

        is OneBotResult.Async -> Delivery.Delivered(listOf(ChannelMessageRef(chat, "async")))

        is OneBotResult.Failed -> Delivery.NotDelivered(failureKind(result.retcode), result.message)

        is OneBotResult.Unreachable -> Delivery.NotDelivered(
            when {
                // Whether the same call may succeed again is the one thing a caller can act on, and the transport
                // already answers it: a 5xx is retryable where a 4xx is not.
                result.failure.retryable -> DeliveryFailure.TRANSIENT

                result.failure.kind == OneBotFailureKind.AUTHENTICATION -> DeliveryFailure.FORBIDDEN

                else -> DeliveryFailure.UNKNOWN
            },
            result.failure.message,
        )

        is OneBotResult.Malformed -> Delivery.NotDelivered(DeliveryFailure.UNKNOWN, result.detail)
    }

    /** The failure an implementation's [retcode] means, as far as this version can tell. */
    private fun failureKind(retcode: Int): DeliveryFailure = when (retcode) {
        RATE_LIMITED -> DeliveryFailure.RATE_LIMITED
        FORBIDDEN, NOT_ALLOWED -> DeliveryFailure.FORBIDDEN
        TOO_LONG -> DeliveryFailure.TOO_LONG
        NOT_FOUND -> DeliveryFailure.CHAT_GONE
        else -> DeliveryFailure.UNKNOWN
    }

    /** Who sent [event], an operator of the instance when the configured [admins] name them and nobody else. */
    public fun senderOf(instance: ChannelInstanceId, event: OneBotEvent.Message, admins: Set<String>): ChatUser =
        sender(instance, event, admins)

    private fun sender(instance: ChannelInstanceId, event: OneBotEvent.Message, admins: Set<String>): ChatUser {
        val reported = event.sender
        val id = event.userId.value
        val card = reported.card?.takeIf { it.isNotEmpty() }
        return ChatUser(
            address = OneBotChats.user(instance, event.userId),
            displayName = card ?: reported.nickname ?: id,
            username = null,
            isBot = reported.userId == event.selfId.value,
            // A platform role is not an operator of this bot: only the accounts the instance names in `admins` are.
            isOperator = id in admins,
        )
    }

    private fun chatInfo(event: OneBotEvent.Message): ChatInfo = when (event) {
        is OneBotEvent.Message.Private -> ChatInfo(ChatKind.DIRECT, null, null)

        // A group message carries no name of the group, and the card of whoever spoke is the name of that member, so
        // putting it here would label the chat with the last person who said something.
        is OneBotEvent.Message.Group -> ChatInfo(ChatKind.GROUP, null, null)
    }

    private fun media(segments: List<OneBotSegment>, reader: MediaReader?): List<MediaAttachment> =
        segments.mapNotNull { segment ->
            val attachment = when (segment) {
                is OneBotSegment.Image -> MediaKind.IMAGE to (segment.url ?: segment.file)
                is OneBotSegment.Record -> MediaKind.AUDIO to (segment.url ?: segment.file)
                is OneBotSegment.Video -> MediaKind.VIDEO to (segment.url ?: segment.file)
                else -> return@mapNotNull null
            }
            val (kind, reference) = attachment
            if (reference.isNullOrEmpty()) return@mapNotNull null
            val content = reader?.content(segment) ?: unread(reference)
            MediaAttachment.builder(kind, mediaType(kind), content)
                .name(reference.substringAfterLast('/').takeIf { it.isNotEmpty() })
                .build()
        }

    /**
     * The bytes of [reference], which this reader does not read.
     *
     * A segment is named whether or not its bytes can be read, so that the agent knows the message carried one: a
     * read that throws is what tells it the file could not be loaded, where naming no attachment at all would hide
     * that the message held anything.
     */
    private fun unread(reference: String): MediaContent = MediaContent {
        throw UnsupportedOperationException("Reading '$reference' is not implemented yet.")
    }

    /** The media type this version reports while it cannot read the file itself. */
    private fun mediaType(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "image/*"
        MediaKind.AUDIO -> "audio/*"
        else -> "video/*"
    }

    /**
     * What [event] is, as a message that another one can quote.
     *
     * Every report is read this way and kept for a while, because a report names the message it answers and says
     * nothing about it, and reading it back from the implementation costs a call it usually does not need.
     */
    public fun quotable(instance: ChannelInstanceId, event: OneBotEvent.Message): QuotedMessage {
        val reported = event.sender
        return QuotedMessage(
            text = quotedText(event.message.segments),
            authorName = reported.card?.takeIf { it.isNotEmpty() } ?: reported.nickname,
            author = OneBotChats.user(instance, event.userId),
            authorIsBot = reported.userId == event.selfId.value,
            segments = event.message.segments,
        )
    }

    /** What the implementation reported about a message is, as a message that another one can quote. */
    public fun quotable(instance: ChannelInstanceId, info: OneBotMessageInfo, selfId: String?): QuotedMessage {
        val reported = info.sender
        val segments = OneBotSegmentCodec.decodeMessage(info.message ?: JsonNull).segments
        return QuotedMessage(
            text = quotedText(segments),
            authorName = reported?.card?.takeIf { it.isNotEmpty() } ?: reported?.nickname,
            author = reported?.userId?.let { OneBotChats.user(instance, it) },
            authorIsBot = reported?.userId?.value != null && reported.userId.value == selfId,
            segments = segments,
        )
    }

    /**
     * What [event] answers, where [quoted] is what was read about the message it answers.
     *
     * A report names the message it answers and says nothing else about it, so [quoted] is read separately and can be
     * missing, since a message may be older than what the channel kept and no longer readable at all. The quote then
     * still names the message that is answered, and says that it holds nothing.
     */
    private fun quote(event: OneBotEvent.Message, chat: ChatAddress, quoted: QuotedMessage?): Quote? {
        val reply = event.message.segments.filterIsInstance<OneBotSegment.Reply>().firstOrNull() ?: return null
        val target = ChannelMessageRef(chat, reply.id.value)
        if (quoted == null) {
            return Quote.builder("").target(target).trust(QuoteTrust.UNTRUSTED).build()
        }
        return Quote.builder(quoted.text)
            .senderName(quoted.authorName)
            .sender(quoted.author)
            .senderIsBot(quoted.authorIsBot)
            .target(target)
            .trust(QuoteTrust.UNTRUSTED)
            .build()
    }

    /**
     * What a quoted message says: its texts, and what its other parts are.
     *
     * A part that carries something a reader would know is named after that, because a quote of a card that showed
     * only its type would say nothing of what the sender meant.
     */
    private fun quotedText(segments: List<OneBotSegment>): String = segments.joinToString("") { segment ->
        when (segment) {
            is OneBotSegment.Text -> segment.text

            is OneBotSegment.At -> "@${segment.userId}"

            is OneBotSegment.Image -> "[image]"

            is OneBotSegment.Record -> "[voice]"

            is OneBotSegment.Video -> "[video]"

            is OneBotSegment.Face -> "[face]"

            is OneBotSegment.Music -> {
                val named = listOfNotNull(segment.title, segment.content).joinToString(" - ")
                val where = segment.url ?: segment.audio
                "[music${if (named.isEmpty()) "" else ": $named"}]${if (where == null) "" else " $where"}"
            }

            is OneBotSegment.Share -> "[link: ${segment.title}] ${segment.url}"

            is OneBotSegment.Json -> "[json] ${segment.data.take(QUOTED_CHARS)}"

            is OneBotSegment.Xml -> "[xml] ${segment.data.take(QUOTED_CHARS)}"

            else -> "[${segment.type}]"
        }
    }

    private fun facts(event: OneBotEvent.Message, segments: List<OneBotSegment>): List<DisplayFact> = buildList {
        if (event is OneBotEvent.Message.Group) {
            event.anonymous?.let { add(DisplayFact("anonymous", it.name ?: it.flag ?: "unnamed")) }
        }
        for (segment in segments) {
            when (segment) {
                is OneBotSegment.Unknown -> add(DisplayFact("unsupported segment", segment.type))
                is OneBotSegment.Json -> add(DisplayFact("json message", segment.data))
                is OneBotSegment.Xml -> add(DisplayFact("xml message", segment.data))
                is OneBotSegment.Forward -> add(DisplayFact("forwarded message", segment.id))
                is OneBotSegment.At -> add(DisplayFact("mentioned", segment.userId))
                is OneBotSegment.Image -> add(DisplayFact("image", segment.file ?: segment.url ?: "unnamed"))
                is OneBotSegment.Record -> add(DisplayFact("voice", segment.file ?: segment.url ?: "unnamed"))
                is OneBotSegment.Video -> add(DisplayFact("video", segment.file ?: segment.url ?: "unnamed"))
                is OneBotSegment.Face -> add(DisplayFact("emoji", segment.id))
                is OneBotSegment.Share -> add(DisplayFact("link", segment.url))
                else -> Unit
            }
        }
    }

    private const val RATE_LIMITED = 1004

    private const val FORBIDDEN = 1003

    private const val NOT_ALLOWED = 1002

    private const val TOO_LONG = 1005

    private const val NOT_FOUND = 100
}

/** The characters of a quoted card that are kept, which is enough of it to know what it is. */
private const val QUOTED_CHARS = 500

/**
 * What a message holds, as the channel read it, so that a message answering it can say what it answered.
 *
 * This is what a channel keeps of the messages it read: a report names the message it answers and nothing about it,
 * so what that message holds is either read when it arrives and kept, or read back from the implementation.
 */
public class QuotedMessage(
    public val text: String,
    public val authorName: String?,
    public val author: UserAddress?,
    public val authorIsBot: Boolean,
    /**
     * What the quoted message was reported to hold, which is kept beside its text so that the media of a quoted
     * message reaches whoever answers it: a picture cannot be read out of a text, however it is written.
     */
    public val segments: List<OneBotSegment> = emptyList(),
)

/**
 * How the bytes a reported segment names are read.
 *
 * A report names an image or a voice message by what the implementation knows it as, and reading it needs this
 * plugin's transport, which the mapping does not have: it is given one of these by whoever asks for the bytes. A
 * segment it cannot read names no attachment, so the rest of the message is read either way.
 */
public fun interface MediaReader {
    /** How to read [segment], null when this reader does not read that kind. */
    public fun content(segment: OneBotSegment): MediaContent?
}
