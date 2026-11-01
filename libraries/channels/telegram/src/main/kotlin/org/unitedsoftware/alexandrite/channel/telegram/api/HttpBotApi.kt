package org.unitedsoftware.alexandrite.channel.telegram.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.BAD_REQUEST
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.CHAT_GONE
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.CONFLICT
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.EDIT_TARGET_GONE
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.ENTITIES_REJECTED
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.FORBIDDEN
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.NOT_MODIFIED
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.PROTOCOL
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.RATE_LIMITED
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.TOO_LONG
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.TRANSIENT
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure.UNAUTHORIZED
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpFailureKind
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.internal.http.MultipartBody
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The [BotApi] of the bot that [config] names, served over HTTP through [engine]. */
@ChannelInstanceScoped
@Binds(BotApi::class)
internal class HttpBotApi(config: TelegramInstanceConfig, engine: HttpEngine, timeouts: HttpTimeouts) :
    BotApi,
    AutoCloseable {
    @Inject
    constructor(config: TelegramInstanceConfig, engine: HttpEngine) : this(config, engine, TIMEOUTS)

    private val token = config.botToken
    private val base = config.apiBase
    private val transport = HttpTransport(timeouts, engine)

    override suspend fun getMe(): User = call("getMe", NO_PARAMETERS, User.serializer())

    override suspend fun getWebhookInfo(): WebhookInfo = call("getWebhookInfo", NO_PARAMETERS, WebhookInfo.serializer())

    override suspend fun getUpdates(offset: Long?, limit: Int, timeoutSeconds: Int): List<Update> {
        val body = JSON.encodeToString(GetUpdates.serializer(), GetUpdates(offset, limit, timeoutSeconds, UPDATES))
        return call("getUpdates", body, UPDATE_LIST, timeoutSeconds.seconds + POLL_MARGIN).map(::update)
    }

    override suspend fun sendMessage(request: SendMessage): Message =
        call("sendMessage", JSON.encodeToString(SendMessage.serializer(), request), Message.serializer())

    override suspend fun sendPhoto(request: SendFile): Message = upload("sendPhoto", "photo", request)

    override suspend fun sendDocument(request: SendFile): Message = upload("sendDocument", "document", request)

    override suspend fun getFile(fileId: String): TelegramFile =
        call("getFile", JSON.encodeToString(GetFile.serializer(), GetFile(fileId)), TelegramFile.serializer())

    override suspend fun download(path: String, maxBytes: Long): ByteArray {
        if (!FILE_PATH.matches(path) || path.split('/').any { it == ".." }) {
            throw BotApiException(PROTOCOL, DOWNLOAD, reason = "the file's path is malformed")
        }
        val secret = token.reveal()
        val call = HttpCall("GET", URI("$base/file/bot$secret/$path"), secrets = listOf(secret))
        return try {
            transport.open(call) { response ->
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val piece = response.read() ?: break
                    if (bytes.size() + piece.size > maxBytes) {
                        throw BotApiException(TOO_LONG, DOWNLOAD, reason = "the file holds more than $maxBytes bytes")
                    }
                    bytes.write(piece)
                }
                bytes.toByteArray()
            }
        } catch (e: HttpException) {
            throw failure(DOWNLOAD, call, e)
        }
    }

    override suspend fun editMessageText(request: EditMessageText): Message =
        call("editMessageText", JSON.encodeToString(EditMessageText.serializer(), request), Message.serializer())

    override suspend fun editMessageReplyMarkup(request: EditMessageReplyMarkup) {
        val body = JSON.encodeToString(EditMessageReplyMarkup.serializer(), request)
        call("editMessageReplyMarkup", body, JsonElement.serializer())
    }

    override suspend fun deleteMessage(chat: Long, message: Long) {
        val body = JSON.encodeToString(DeleteMessage.serializer(), DeleteMessage(chat, message))
        call("deleteMessage", body, Boolean.serializer())
    }

    override suspend fun answerCallbackQuery(request: AnswerCallbackQuery) {
        call(
            "answerCallbackQuery",
            JSON.encodeToString(AnswerCallbackQuery.serializer(), request),
            Boolean.serializer(),
        )
    }

    override suspend fun sendChatAction(chat: Long, thread: Long?) {
        val body = JSON.encodeToString(SendChatAction.serializer(), SendChatAction(chat, thread, "typing"))
        call("sendChatAction", body, Boolean.serializer())
    }

    override suspend fun setMyCommands(request: SetMyCommands) {
        call("setMyCommands", JSON.encodeToString(SetMyCommands.serializer(), request), Boolean.serializer())
    }

    override suspend fun deleteMyCommands(request: DeleteMyCommands) {
        call("deleteMyCommands", JSON.encodeToString(DeleteMyCommands.serializer(), request), Boolean.serializer())
    }

    override suspend fun act(method: String, params: JsonObject): JsonElement =
        call(method, params.toString(), JsonElement.serializer())

    override fun close() {
        transport.close()
    }

    /** Sends [request]'s file as the field [field] of [method]'s form. */
    private suspend fun upload(method: String, field: String, request: SendFile): Message {
        val file = request.file
        val form = MultipartBody.builder().field("chat_id", "${request.chatId}")
        request.thread?.let { form.field("message_thread_id", "$it") }
        request.caption?.let { form.field("caption", it) }
        request.captionEntities?.let { form.field("caption_entities", JSON.encodeToString(ENTITY_LIST, it)) }
        form.file(field, file.name, file.mediaType, file.size, file::inputStream)
        val secret = token.reveal()
        val wait = UPLOAD_FIRST_BYTE + (file.size / UPLOAD_BYTES_PER_SECOND).seconds
        val call = HttpCall(
            "POST",
            URI("$base/bot$secret/$method"),
            UPLOAD_HEADERS,
            secrets = listOf(secret),
            firstByte = wait,
            multipart = form.build(),
        )
        return exchange(method, call, Message.serializer())
    }

    private suspend fun <T> call(method: String, body: String, result: KSerializer<T>, firstByte: Duration? = null): T {
        val secret = token.reveal()
        val call = HttpCall("POST", URI("$base/bot$secret/$method"), HEADERS, body, listOf(secret), firstByte)
        return exchange(method, call, result)
    }

    private suspend fun <T> exchange(method: String, call: HttpCall, result: KSerializer<T>): T {
        val text = try {
            transport.send(call)
        } catch (e: HttpException) {
            throw failure(method, call, e)
        }
        val envelope = envelope(text)
            ?: throw BotApiException(PROTOCOL, method, reason = "the answer is no Bot API envelope")
        if (!envelope.ok) {
            val description = envelope.description?.let(call::mask)
            throw BotApiException(PROTOCOL, method, description, reason = description ?: "the answer is not ok")
        }
        val value = envelope.result ?: throw BotApiException(PROTOCOL, method, reason = "the answer has no result")
        return try {
            JSON.decodeFromJsonElement(result, value)
        } catch (e: IllegalArgumentException) {
            throw BotApiException(PROTOCOL, method, reason = "the result does not read")
        }
    }

    /** The update [element] holds, or an update of its id with nothing in it when the rest does not read. */
    private fun update(element: JsonElement): Update = try {
        JSON.decodeFromJsonElement(Update.serializer(), element)
    } catch (e: IllegalArgumentException) {
        val id = ((element as? JsonObject)?.get("update_id") as? JsonPrimitive)?.longOrNull
            ?: throw BotApiException(PROTOCOL, "getUpdates", reason = "an update has no id")
        logger.warn("Update {} does not read and is skipped", id)
        Update(id)
    }

    private fun failure(method: String, call: HttpCall, e: HttpException): BotApiException {
        val status = e.status
        if (status == null || status in 200..299) {
            val reason = if (e.kind == HttpFailureKind.TIMEOUT) "no answer in time" else "the connection failed"
            return BotApiException(TRANSIENT, method, cause = e, reason = reason)
        }
        val envelope = e.body?.let(::envelope)
        val description = envelope?.description?.let(call::mask)
        val migrateTo = envelope?.parameters?.migrateToChatId
        val failure = classify(method, status, description, migrateTo)
        val retryAfter = if (failure == RATE_LIMITED) {
            envelope?.parameters?.retryAfter?.seconds ?: e.retryAfter ?: 1.seconds
        } else {
            null
        }
        return BotApiException(failure, method, description, retryAfter, migrateTo, e, description ?: "HTTP $status")
    }

    private fun envelope(text: String): Envelope<JsonElement>? = try {
        JSON.decodeFromString(ENVELOPE, text)
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        val TIMEOUTS = HttpTimeouts(connect = 10.seconds, firstByte = 30.seconds, idle = 30.seconds)

        /** How much longer than its own timeout a long poll waits for the answer. */
        val POLL_MARGIN = 15.seconds

        val HEADERS = mapOf("content-type" to "application/json", "accept" to "application/json")
        val UPLOAD_HEADERS = mapOf("accept" to "application/json")

        /** How long an upload waits for its answer beside the time its bytes take. */
        val UPLOAD_FIRST_BYTE = 30.seconds

        /** The slowest upload that is waited for. */
        const val UPLOAD_BYTES_PER_SECOND = 256L * 1024

        const val DOWNLOAD = "download"

        /** A file path as Telegram gives it. */
        val FILE_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
        val ENTITY_LIST: KSerializer<List<MessageEntity>> = ListSerializer(MessageEntity.serializer())
        const val NO_PARAMETERS = "{}"
        val UPDATES = listOf("message", "callback_query")
        val UPDATE_LIST = ListSerializer(JsonElement.serializer())
        val ENVELOPE: KSerializer<Envelope<JsonElement>> = Envelope.serializer(JsonElement.serializer())
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}

/** What a refusal with the non-2xx [status] means for [method]. */
internal fun classify(method: String, status: Int, description: String?, migrateTo: Long?): BotApiFailure {
    val text = description.orEmpty().lowercase()
    return when {
        status == 400 && migrateTo != null -> CHAT_GONE
        status == 400 -> REFUSALS.firstOrNull { (_, phrases) -> phrases.any { it in text } }?.first ?: BAD_REQUEST
        status == 401 || (status == 404 && method == "getMe") -> UNAUTHORIZED
        status == 403 -> FORBIDDEN
        status == 409 -> CONFLICT
        status == 413 -> TOO_LONG
        status == 429 -> RATE_LIMITED
        status in 400..499 -> BAD_REQUEST
        status in 500..599 -> TRANSIENT
        else -> PROTOCOL
    }
}

/** The 400 refusals told apart by what their description holds. */
private val REFUSALS = listOf(
    NOT_MODIFIED to listOf("message is not modified"),
    EDIT_TARGET_GONE to listOf(
        "message to edit not found",
        "message can't be edited",
        "message to delete not found",
        "message can't be deleted",
    ),
    TOO_LONG to listOf("message is too long", "text is too long", "caption is too long", "file is too big"),
    CHAT_GONE to listOf("chat not found", "message thread not found"),
    ENTITIES_REJECTED to listOf("can't parse entities", "entity", "entities"),
)

private val logger = LoggerFactory.getLogger(HttpBotApi::class.java)
