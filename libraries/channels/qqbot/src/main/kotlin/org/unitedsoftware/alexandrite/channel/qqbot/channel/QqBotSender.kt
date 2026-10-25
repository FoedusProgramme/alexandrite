package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotChats
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotMarkdown
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFailure
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotMessageType
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotSent
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.asTextOrNull
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/**
 * What this instance sends, as the open platform takes it.
 *
 * A message written to a chat as an answer to one the bot was sent names that message as its `msg_id` and a sequence
 * number, which is what makes it a passive reply the platform takes within the minutes it allows. A message written
 * with nothing to answer is an active one, which the platform limits by the chat and by the bot: the limits are its
 * own and the channel sends nothing beyond what it is told.
 *
 * The sequence number of one message a chat is answered counts up: the same pair of a message and a sequence is sent
 * once, and a second answer to the same message sends the next number. A reply the platform no longer takes because
 * the minutes passed is sent once more as an active message, so a slow answer still reaches the chat.
 */
@ChannelInstanceScoped
internal class QqBotSender(
    private val api: QqBotApi,
    private val uploads: QqBotUploads,
    private val sent: QqBotSentMessages,
    private val index: QqBotMessageIndex,
) {
    private val sequences = Seqs()

    /**
     * Sends [message] to [chat].
     *
     * The platform has no caption on a media message: a text and its files are separate messages. A message with files
     * uploads all of them first, so a file the platform refuses is known before the chat is told anything, then sends
     * the text when it has one and a `msg_type=7` message for each file. The ids of everything it sent are delivered
     * together.
     */
    suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery {
        val path = QqBotChats.sendPath(chat)
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        if (message.files.isEmpty()) return sendText(path, chat, message)
        message.files.firstOrNull { it.size > QQ_BOT_MAX_FILE_BYTES }?.let { file ->
            return Delivery.NotDelivered(
                DeliveryFailure.UNKNOWN,
                "'${file.name}' holds ${file.size} bytes, more than the $QQ_BOT_MAX_FILE_BYTES bytes a message of " +
                    "this platform may take.",
            )
        }
        val uploaded = mutableListOf<String>()
        for (file in message.files) {
            when (val answer = uploads.upload(chat, file)) {
                is QqBotUpload.Failed -> return Delivery.NotDelivered(
                    DeliveryFailure.UNKNOWN,
                    "'${file.name}' was not sent: ${answer.reason}",
                )

                is QqBotUpload.Uploaded -> uploaded += answer.fileInfo
            }
        }
        val refs = mutableListOf<ChannelMessageRef>()
        if (message.text.isNotBlank()) {
            val delivery = sendText(path, chat, message)
            if (delivery !is Delivery.Delivered) return delivery
            refs += delivery.messages
        }
        val replyTo = message.replyTo?.id?.takeIf { it.isNotEmpty() }
        for (fileInfo in uploaded) {
            val delivery = sendMedia(path, chat, fileInfo, replyTo)
            if (delivery !is Delivery.Delivered) return delivery
            refs += delivery.messages
        }
        return Delivery.Delivered(refs)
    }

    /** Sends [message] as one text or markdown message, [path] being the messages endpoint of [chat]. */
    private suspend fun sendText(path: String, chat: ChatAddress, message: OutboundMessage): Delivery {
        val replyTo = message.replyTo?.id?.takeIf { it.isNotEmpty() }
        val reference = replyTo?.let { index.referenceOf(chat, it) }
        var markdown = message.markup.id == Markup.MARKDOWN.id
        var text = if (markdown) QqBotMarkdown.render(message.text) else message.text
        var answer = post(path, text, markdown, replyTo, replyTo?.let(sequences::next), reference)
        var failure = (answer as? QqBotAnswer.Failed)?.failure
        if (failure != null && failure.code in MARKDOWN_REFUSED && markdown) {
            logger.info(
                "{}: the platform takes no markdown in {} ({}); sending the message as plain text",
                PLUGIN,
                chat,
                failure.code,
            )
            markdown = false
            text = message.text
            answer = post(path, text, markdown, replyTo, replyTo?.let(sequences::next), reference)
            failure = (answer as? QqBotAnswer.Failed)?.failure
        }
        if (failure != null && failure.code in URL_REFUSED) {
            logger.info("{}: the platform refuses a message of {} with a URL; sending it without links", PLUGIN, chat)
            markdown = false
            text = QqBotMarkdown.withoutUrls(message.text)
            answer = post(path, text, markdown, replyTo, replyTo?.let(sequences::next), reference)
            failure = (answer as? QqBotAnswer.Failed)?.failure
        }
        if (failure != null && failure.code in PASSIVE_GONE && replyTo != null) {
            logger.info(
                "{}: the message {} is no longer answered passively; sending as an active message",
                PLUGIN,
                replyTo,
            )
            answer = post(path, text, markdown, msgId = null, msgSeq = null, reference = reference)
        }
        return delivery(chat, answer)
    }

    /** Sends one uploaded file as the platform's media message, answering [replyTo] when it names one. */
    private suspend fun sendMedia(path: String, chat: ChatAddress, fileInfo: String, replyTo: String?): Delivery {
        var answer = postMedia(path, fileInfo, replyTo, replyTo?.let(sequences::next))
        var failure = (answer as? QqBotAnswer.Failed)?.failure
        if (failure != null && failure.code in PASSIVE_GONE && replyTo != null) {
            logger.info(
                "{}: the media reply {} is no longer answered passively; sending it as an active message",
                PLUGIN,
                replyTo,
            )
            answer = postMedia(path, fileInfo, msgId = null, msgSeq = null)
            failure = (answer as? QqBotAnswer.Failed)?.failure
        }
        if (failure != null) {
            logger.warn(
                "{}: the platform took no media message in {}: {} {}",
                PLUGIN,
                chat,
                failure.code,
                failure.message,
            )
        }
        return delivery(chat, answer)
    }

    /** Sends the uploaded [fileInfo] as a `msg_type=7` message. */
    private suspend fun postMedia(path: String, fileInfo: String, msgId: String?, msgSeq: Int?): QqBotAnswer {
        val body = buildJsonObject {
            put("msg_type", QqBotMessageType.MEDIA)
            putJsonObject("media") { put("file_info", fileInfo) }
            if (msgId != null) {
                put("msg_id", msgId)
                put("msg_seq", msgSeq ?: 1)
            }
        }
        return api.post(path, body)
    }

    /** Sends the approval message [text] of [id] to [chat], with the buttons that answer it. */
    internal suspend fun sendApproval(
        chat: ChatAddress,
        text: String,
        id: String,
        allow: String,
        deny: String,
        always: String? = null,
    ): Delivery {
        val path = QqBotChats.sendPath(chat)
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        var answer = api.post(path, approvalBody(text, id, allow, deny, always, markdown = true))
        var failure = (answer as? QqBotAnswer.Failed)?.failure
        // The client shows a keyboard on a markdown message; where this bot has no markdown permission, a text
        // message is sent instead, which is at least the request itself.
        if (failure != null && failure.code in MARKDOWN_REFUSED) {
            logger.info("{}: the platform takes no markdown approval; sending the text without it", PLUGIN)
            answer = api.post(path, approvalBody(text, id, allow, deny, always, markdown = false))
            failure = (answer as? QqBotAnswer.Failed)?.failure
        }
        if (failure != null) {
            logger.warn("{}: the platform took no approval in {}: {} {}", PLUGIN, chat, failure.code, failure.message)
        }
        return delivery(chat, answer)
    }

    /** The body of an approval message, with the keyboard that answers it. */
    private fun approvalBody(
        text: String,
        id: String,
        allow: String,
        deny: String,
        always: String?,
        markdown: Boolean,
    ): JsonObject = buildJsonObject {
        if (markdown) {
            put("msg_type", QqBotMessageType.MARKDOWN)
            putJsonObject("markdown") { put("content", text.trim()) }
        } else {
            put("msg_type", QqBotMessageType.TEXT)
            put("content", text)
        }
        putJsonObject("keyboard") {
            putJsonObject("content") {
                putJsonArray("rows") {
                    addJsonObject {
                        putJsonArray("buttons") {
                            add(approvalButton("allow", allow, "/approve $id"))
                            add(approvalButton("deny", deny, "/deny $id"))
                        }
                    }
                    if (always != null) {
                        addJsonObject {
                            putJsonArray("buttons") {
                                add(approvalButton("always", always.take(10), "/approve $id always"))
                            }
                        }
                    }
                }
            }
        }
    }

    /** A button whose press writes [data] as a message, which the platform may send by itself. */
    private fun approvalButton(id: String, label: String, data: String): JsonObject = buildJsonObject {
        put("id", id)
        putJsonObject("render_data") {
            put("label", label)
            put("style", if (id == "allow") 4 else 0)
        }
        putJsonObject("action") {
            put("type", 2)
            putJsonObject("permission") { put("type", 2) }
            put("data", data)
            put("enter", true)
            put("reply", false)
        }
    }

    /** Tells [chat] that the bot is writing, where the platform has such a state. */
    internal suspend fun sendTyping(chat: ChatAddress): Delivery {
        if (QqBotChats.kind(chat) != org.unitedsoftware.alexandrite.sdk.chat.ChatKind.DIRECT) {
            return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance types in no $chat.")
        }
        val path = QqBotChats.sendPath(chat)
            ?: return Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "This instance serves no $chat.")
        val body = buildJsonObject {
            put("msg_type", QqBotMessageType.TYPING)
            putJsonObject("input_notify") {
                put("input_type", 1)
                put("input_second", TYPING_SECONDS)
            }
        }
        return when (val answer = api.post(path, body)) {
            is QqBotAnswer.Ok -> Delivery.Delivered(listOf(ChannelMessageRef(chat, sentId(answer) ?: "typing")))

            is QqBotAnswer.Failed -> Delivery.NotDelivered(
                failureKind(answer.failure, answer.status),
                answer.failure.message,
            )
        }
    }

    /** The sequence number that answers [msgId] next. */
    internal fun sequenceFor(msgId: String): Int = sequences.next(msgId)

    /**
     * One piece of the streaming message that answers [msgId].
     *
     * The platform serves streaming for single chats only. A piece either starts the message, when [streamId] is null,
     * or replaces its content with the whole [text] so far; [last] ends it.
     */
    internal suspend fun stream(
        chat: ChatAddress,
        text: String,
        msgId: String,
        msgSeq: Int,
        streamId: String?,
        index: Int,
        last: Boolean,
        append: Boolean = false,
    ): QqBotAnswer {
        val openid = QqBotChats.openid(chat)
            ?.takeIf { QqBotChats.kind(chat) == org.unitedsoftware.alexandrite.sdk.chat.ChatKind.DIRECT }
            ?: return QqBotAnswer.Failed(QqBotFailure(null, "This instance streams no $chat.", null), null)
        val body = buildJsonObject {
            put("input_mode", if (append) "append" else "replace")
            put("input_state", if (last) STREAM_FINISHED else STREAM_GENERATING)
            put("index", index)
            put("content_type", "markdown")
            if (text.isNotEmpty()) put("content_raw", QqBotMarkdown.render(text))
            put("msg_id", msgId)
            put("msg_seq", msgSeq)
            if (streamId != null) put("stream_msg_id", streamId)
        }
        val answer = api.post("/v2/users/$openid/stream_messages", body)
        if (answer is QqBotAnswer.Failed) {
            logger.warn(
                "{}: could not stream a piece to {}: {} {}",
                PLUGIN,
                chat,
                answer.failure.code,
                answer.failure.message,
            )
        }
        return answer
    }

    /** The id of the message [answer] reports, null when it reports none. */
    internal fun sentId(answer: QqBotAnswer): String? = when (answer) {
        is QqBotAnswer.Ok -> answer.body?.let {
            runCatching { QqBotWire.json.decodeFromJsonElement(QqBotSent.serializer(), it) }.getOrNull()
        }?.id ?: (answer.body as? JsonObject)?.get("id")?.asTextOrNull()

        is QqBotAnswer.Failed -> null
    }

    /** The body that sends [text] as a reply to [msgId] with [msgSeq], or as an active message with neither. */
    private fun body(text: String, markdown: Boolean, msgId: String?, msgSeq: Int?, reference: String?): JsonObject =
        buildJsonObject {
            if (markdown) {
                put("msg_type", QqBotMessageType.MARKDOWN)
                putJsonObject("markdown") { put("content", text.trim()) }
            } else {
                put("msg_type", QqBotMessageType.TEXT)
                put("content", text)
            }
            if (msgId != null) {
                put("msg_id", msgId)
                put("msg_seq", msgSeq ?: 1)
            }
            if (reference != null) {
                putJsonObject("message_reference") { put("message_id", reference) }
            }
        }

    /** Sends [text] as [markdown] or as plain text, answering [msgId] with [msgSeq] when it names one. */
    private suspend fun post(
        path: String,
        text: String,
        markdown: Boolean,
        msgId: String?,
        msgSeq: Int?,
        reference: String? = null,
    ): QqBotAnswer = api.post(path, body(text, markdown, msgId, msgSeq, reference))

    /** What [answer] means for [chat]. */
    internal fun delivery(chat: ChatAddress, answer: QqBotAnswer): Delivery = when (answer) {
        is QqBotAnswer.Ok -> {
            val parsed = answer.body?.let {
                runCatching { QqBotWire.json.decodeFromJsonElement(QqBotSent.serializer(), it) }.getOrNull()
            }
            val id = parsed?.id ?: (answer.body as? JsonObject)?.get("id")?.asTextOrNull() ?: "sent"
            logger.info("{}: sent a message to {} as {}", PLUGIN, chat, id)
            this.sent.record(chat, id)
            index.record(chat, parsed?.extInfo?.refIdx, id)
            Delivery.Delivered(listOf(ChannelMessageRef(chat, id)))
        }

        is QqBotAnswer.Failed -> {
            val kind = failureKind(answer.failure, answer.status)
            val detail = answer.failure.message.ifBlank { "the platform gave no reason" }
            logger.warn(
                "{}: could not send to {}: {} {} {}",
                PLUGIN,
                chat,
                kind,
                answer.failure.code,
                detail,
            )
            Delivery.NotDelivered(
                kind,
                "The platform did not take the message: $detail" +
                    (answer.failure.code?.let { " (code $it)" } ?: ""),
            )
        }
    }

    /** What a failure of the platform means for a caller that may try again. */
    private fun failureKind(failure: QqBotFailure, status: Int?): DeliveryFailure {
        val code = failure.code
        return when {
            status == 429 || code in RATE_LIMITED_CODES -> DeliveryFailure.RATE_LIMITED
            code in TOO_LONG_CODES -> DeliveryFailure.TOO_LONG
            status == 401 || status == 403 || code in FORBIDDEN_CODES -> DeliveryFailure.FORBIDDEN
            status != null && status >= 500 -> DeliveryFailure.TRANSIENT
            else -> DeliveryFailure.UNKNOWN
        }
    }

    /** The sequence numbers of the messages this channel answered, oldest forgotten first. */
    private class Seqs {
        private val byMessage = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > KEPT
        }

        /** The sequence number of the next reply to [id]. */
        fun next(id: String): Int = synchronized(byMessage) {
            val next = (byMessage[id] ?: 0) + 1
            byMessage[id] = next
            next
        }

        private companion object {
            /** How many messages a sequence is kept for. */
            const val KEPT = 512
        }
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-qqbot"

        /** The codes with which the platform says the minutes or replies of a passive answer ran out. */
        val PASSIVE_GONE = setOf(304103, 40034005, 40034128)

        /** The codes with which the platform says it takes no markdown of this bot, which plain text may pass. */
        val MARKDOWN_REFUSED = setOf(304036, 40034008, 40034009, 40034011, 40034124, 40034127)

        /** The codes with which the platform says it refuses a message holding a URL. */
        val URL_REFUSED = setOf(40054010)

        /** What the streaming endpoint calls a message still being written, and the call that ends it. */
        const val STREAM_GENERATING = 1
        const val STREAM_FINISHED = 10

        /** How long one typing notification lasts, as the platform counts it. */
        const val TYPING_SECONDS = 60

        /** The codes with which the platform says it takes no more messages as fast as they are sent. */
        val RATE_LIMITED_CODES = setOf(1100100, 1100308, 40034100, 40034101, 40054002)

        val TOO_LONG_CODES = setOf(40054007, 40054018)

        /** The codes with which the platform says this bot may not write here at all. */
        val FORBIDDEN_CODES = setOf(
            11253,
            11254,
            11265,
            304004,
            304036,
            340067,
            40034105,
            40054003,
            40054004,
            40054013,
            40054016,
        )

        val logger = LoggerFactory.getLogger(QqBotSender::class.java)
    }
}
