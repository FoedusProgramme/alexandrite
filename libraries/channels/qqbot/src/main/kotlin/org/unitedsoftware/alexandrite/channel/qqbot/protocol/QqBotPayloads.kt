package org.unitedsoftware.alexandrite.channel.qqbot.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The JSON of the QQ open platform, whose answers carry fields this version does not read. */
internal object QqBotWire {
    /** The JSON this channel reads and writes, which keeps what it does not know and writes what it sets. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** The gateway frame [text] is, null when it is no frame this version knows. */
    fun frame(text: String): QqBotFrame? = runCatching {
        json.decodeFromString(QqBotFrame.serializer(), text)
    }.getOrNull()

    /** The message event [data] carries, null when it is no message event. */
    fun message(data: JsonElement?): QqBotMessageEvent? {
        if (data == null || data is kotlinx.serialization.json.JsonNull) return null
        return runCatching {
            json.decodeFromJsonElement(QqBotMessageEvent.serializer(), data)
        }.getOrNull()
    }

    /** The text of a frame field, null when the frame holds no such field. */
    fun string(element: JsonElement?, key: String): String? =
        (element as? JsonObject)?.get(key)?.let { it as? JsonPrimitive }?.contentOrNull()
}

/** One frame of the gateway, whatever its opcode carries. */
@Serializable
internal class QqBotFrame(
    /** The event id of a dispatch frame, null on the frames the client sends. */
    val id: String? = null,
    val op: Int,
    val d: JsonElement? = null,
    /** The sequence number of a dispatch frame, which a heartbeat and a resume name. */
    val s: Long? = null,
    /** The event name of a dispatch frame. */
    val t: String? = null,
)

/** A message of the QQ chats, which the gateway dispatches as C2C_MESSAGE_CREATE or GROUP_AT_MESSAGE_CREATE. */
@Serializable
internal class QqBotMessageEvent(
    /** The id of the message, which a passive reply names as `msg_id`. */
    val id: String,
    val author: QqBotAuthor = QqBotAuthor(),
    /** The text of the message, which the platform already stripped the mention of the bot from. */
    val content: String = "",
    /** The group the message came from, null for a single chat. */
    @SerialName("group_openid") val groupOpenid: String? = null,
    /** When the platform received the message, in RFC 3339. */
    val timestamp: String? = null,
    /** What the message is: 0 text, 3 a card, 103 a quote of other messages. */
    @SerialName("message_type") val messageType: Int = 0,
    @SerialName("message_scene") val messageScene: QqBotMessageScene? = null,
    val attachments: List<QqBotAttachment> = emptyList(),
    val mentions: List<QqBotAuthor> = emptyList(),
    @SerialName("ark_data") val arkData: QqBotArkData? = null,
    @SerialName("msg_elements") val msgElements: List<QqBotMsgElement> = emptyList(),
)

/** Who wrote a message, in a single chat or a group. */
@Serializable
internal class QqBotAuthor(
    val id: String? = null,
    val username: String? = null,
    val bot: Boolean = false,
    /** The user's id in a single chat. */
    @SerialName("user_openid") val userOpenid: String? = null,
    /** The user's id in a group. */
    @SerialName("member_openid") val memberOpenid: String? = null,
    /** The user's role in the group: member, admin or owner. */
    @SerialName("member_role") val memberRole: String? = null,
) {
    /** The id the chat the message came from names this author by. */
    fun openid(group: Boolean): String? = if (group) memberOpenid else userOpenid
}

/** What a message carries besides its text: an image, a voice message, a video or a file. */
@Serializable
internal class QqBotAttachment(
    val url: String? = null,
    val filename: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    /** The MIME type of the attachment, or `voice` for a voice message or `file` for a group file. */
    @SerialName("content_type") val contentType: String? = null,
    /** The address a voice message's SILK was converted to WAV at, null for the other kinds. */
    @SerialName("voice_wav_url") val voiceWavUrl: String? = null,
    @SerialName("asr_refer_text") val asrReferText: String? = null,
)

/** Where a message was written and what indexes it carries: the message index and the one it quotes. */
@Serializable
internal class QqBotMessageScene(
    val source: String? = null,
    /** `key=value` entries, among them `msg_idx`, `ref_msg_idx` and `auth_token`. */
    val ext: List<String> = emptyList(),
) {
    /** The value of [key] among the entries, null when they carry none. */
    fun ext(key: String): String? = ext.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
}

/** The card a card message carries. */
@Serializable
internal class QqBotArkData(
    /** What the platform says the card is, in the words a chat is shown. */
    val prompt: String? = null,
    @SerialName("ark_type") val arkType: String? = null,
    @SerialName("ark_name") val arkName: String? = null,
)

/** One element of a quote message, which nests the quoted messages it gathers. */
@Serializable
internal class QqBotMsgElement(
    @SerialName("msg_idx") val msgIdx: String? = null,
    val author: QqBotAuthor? = null,
    @SerialName("message_type") val messageType: Int? = null,
    val content: String? = null,
    val attachments: List<QqBotAttachment> = emptyList(),
    @SerialName("ark_data") val arkData: QqBotArkData? = null,
    @SerialName("msg_elements") val msgElements: List<QqBotMsgElement> = emptyList(),
)

/** The token the open platform sells for the AppID and AppSecret of a bot. */
internal class QqBotAccessToken(val value: String, val expiresInSeconds: Long)

/** What the open platform said when a call did not succeed. */
internal class QqBotFailure(
    /** The `err_code` or `code` of the answer, null when it named none. */
    val code: Int?,
    /** The message the platform wrote, which is for people and may change. */
    val message: String,
    /** The platform's id of the request, which it asks for in a support request. */
    val traceId: String?,
    /** How many items the platform takes where it refused their number, null when it named none. */
    val limit: Int? = null,
    /** What the platform answered, for a log that has to say why it refused, null when the answer said nothing. */
    val details: String? = null,
)

/** What a failed answer says, as the fields both kinds of answer use. */
internal fun failureOf(body: JsonElement?): QqBotFailure {
    val objectBody = body as? JsonObject
    val code = objectBody?.get("err_code")?.asIntOrNull() ?: objectBody?.get("code")?.asIntOrNull()
    val message = objectBody?.get("message")?.asTextOrNull().orEmpty()
    val trace = objectBody?.get("trace_id")?.asTextOrNull()
    val limit = objectBody?.get("limit")?.asIntOrNull()
    return QqBotFailure(code, message, trace, limit, body?.toString()?.take(DETAILS_LIMIT))
}

/** How much of a failed answer is kept for the log that has to say why the platform refused. */
private const val DETAILS_LIMIT = 400

/** The int value of the field, null when it is no number. */
internal fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

/** The text of the field, null when it is blank or no text. */
internal fun JsonElement?.asTextOrNull(): String? = (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

/** The number of the token answer, which the platform writes as a string or as a number. */
internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

/** The boolean of the field, null when it is no boolean. */
internal fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.content?.let {
    when (it.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** The content of the string, null when it is null or not a primitive. */
internal fun JsonPrimitive?.contentOrNull(): String? = if (this == null || this is JsonNull) null else content

/** The platform's answer to a message being sent: the id it gave the message and its quote reference. */
@Serializable
internal class QqBotSent(
    val id: String? = null,
    val timestamp: String? = null,
    @SerialName("ext_info") val extInfo: QqBotSentInfo? = null,
)

/** What the send response says about quoting the sent message. */
@Serializable
internal class QqBotSentInfo(
    /** The reference a later quote of this message names, which the send's message id does not. */
    @SerialName("ref_idx") val refIdx: String? = null,
)

/** What a group or single chat message is: `message_type=0` text, `2` markdown, `7` media. */
internal object QqBotMessageType {
    const val TEXT: Int = 0
    const val MARKDOWN: Int = 2
    const val TYPING: Int = 6
    const val MEDIA: Int = 7
}

/** The kinds of attachment whose content type the platform names, mapped to the kind the SDK knows. */
internal fun attachmentKind(contentType: String?): String = when {
    contentType == null -> "file"
    contentType == "voice" -> "audio"
    contentType == "file" -> "file"
    contentType.startsWith("image/") -> "image"
    contentType.startsWith("audio/") -> "audio"
    contentType.startsWith("video/") -> "video"
    else -> "file"
}
