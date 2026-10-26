package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotChats
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.asTextOrNull
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/** The largest file the platform takes in a message, whatever its type. */
internal const val QQ_BOT_MAX_FILE_BYTES: Long = 200L * 1024 * 1024

/** The answer to uploading one file: the platform's `file_info`, or why it gave none. */
internal sealed class QqBotUpload {
    class Uploaded(val fileInfo: String) : QqBotUpload()

    class Failed(val reason: String, val code: Int?) : QqBotUpload()
}

/**
 * Uploads a file of a message to the platform, which sends it in a later `msg_type=7` message.
 *
 * The platform takes a file in one of three ways. A small one goes as base64 in the upload call, whose data must stay
 * under 5 MiB. A larger one is cut into the blocks the pre-upload call names, each block is PUT to its presigned URL,
 * the block is confirmed, and the upload call merges them. Single chats and groups have their own endpoints, and a
 * file uploaded for one cannot be sent to the other.
 */
@ChannelInstanceScoped
internal class QqBotUploads(private val api: QqBotApi, private val config: QqBotInstanceConfig) : AutoCloseable {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun close() = client.close()

    /** Uploads [file] for a message to [chat], or says why the platform did not take it. */
    suspend fun upload(chat: ChatAddress, file: OutboundFile): QqBotUpload {
        val kind = QqBotChats.kind(chat)
            ?: return QqBotUpload.Failed("This instance serves no $chat.", null)
        val openid = QqBotChats.openid(chat)
            ?: return QqBotUpload.Failed("This instance serves no $chat.", null)
        if (file.size > QQ_BOT_MAX_FILE_BYTES) {
            return QqBotUpload.Failed(
                "'${file.name}' holds ${file.size} bytes, more than the $QQ_BOT_MAX_FILE_BYTES bytes a message of " +
                    "this platform may take.",
                null,
            )
        }
        val endpoints = endpoints(kind, openid)
        val type = fileType(file)
        val first = uploadAs(endpoints, file, type)
        // A format the platform will not show can still be sent as an ordinary file, which it takes as it is.
        if (first is QqBotUpload.Failed && first.code == UNSUPPORTED_FORMAT && type != FILE) {
            return uploadAs(endpoints, file, FILE)
        }
        return first
    }

    /** Uploads [file] as [type], in the call the file's size allows. */
    private suspend fun uploadAs(endpoints: Endpoints, file: OutboundFile, type: Int): QqBotUpload =
        if (file.size <= INLINE_LIMIT) {
            inline(endpoints.files, type, file)
        } else {
            chunked(endpoints, type, file)
        }

    /** Uploads [file] as base64 in the upload call, which the platform takes for at most [INLINE_LIMIT] bytes. */
    private suspend fun inline(path: String, type: Int, file: OutboundFile): QqBotUpload {
        val body = buildJsonObject {
            put("file_type", type)
            put("file_name", file.name)
            put("file_data", Base64.getEncoder().encodeToString(file.bytes()))
            put("srv_send_msg", false)
        }
        return uploaded(api.post(path, body))
    }

    /** Uploads [file] in the blocks the pre-upload call names, then has the platform merge them. */
    private suspend fun chunked(endpoints: Endpoints, type: Int, file: OutboundFile): QqBotUpload {
        val bytes = file.bytes()
        val prepare = api.post(
            endpoints.prepare,
            buildJsonObject {
                put("file_type", type)
                put("file_size", bytes.size.toString())
                put("file_name", file.name)
                put("md5", digest("MD5", bytes))
                put("sha1", digest("SHA-1", bytes))
                put("md5_10m", digest("MD5", bytes.copyOf(minOf(bytes.size, MD5_10M_BYTES))))
            },
        )
        val plan = prepare.objectOrNull() ?: return failed(prepare, "the pre-upload answered no plan")
        val uploadId = plan.text("upload_id") ?: return QqBotUpload.Failed("The pre-upload named no upload id.", null)
        val parts = plan["parts"] as? JsonArray
            ?: return QqBotUpload.Failed("The pre-upload named no parts.", null)
        if (parts.isEmpty()) return QqBotUpload.Failed("The pre-upload named no parts.", null)
        val fallbackBlock = plan.long("block_size")
        val ordered = parts.mapNotNull { it as? JsonObject }
            .sortedBy { it.int("index") ?: Int.MAX_VALUE }
        var offset = 0L
        val blocks = mutableListOf<Part>()
        for (part in ordered) {
            val index = part.int("index") ?: return QqBotUpload.Failed("A part named no index.", null)
            val url = part.text("presigned_url") ?: return QqBotUpload.Failed("A part named no URL.", null)
            val size = part.long("block_size") ?: fallbackBlock
                ?: return QqBotUpload.Failed("A part named no block size.", null)
            if (offset + size > bytes.size) {
                return QqBotUpload.Failed("A part of '${file.name}' reaches past the file.", null)
            }
            blocks += Part(index, url, offset, offset + size)
            offset += size
        }
        if (offset != bytes.size.toLong()) {
            return QqBotUpload.Failed("The blocks of '${file.name}' did not cover the file.", null)
        }
        val concurrency = (plan["upload_config"] as? JsonObject)?.int("concurrency")
            ?.coerceIn(1, blocks.size)
            ?: 1
        val outcomes = if (concurrency == 1) {
            blocks.map { sendPart(endpoints, uploadId, it, bytes) }
        } else {
            coroutineScope {
                val gate = Semaphore(concurrency)
                blocks.map { part ->
                    async { gate.withPermit { sendPart(endpoints, uploadId, part, bytes) } }
                }.awaitAll()
            }
        }
        outcomes.firstOrNull { it is QqBotUpload.Failed }?.let { return it }
        return uploaded(
            api.post(
                endpoints.files,
                buildJsonObject {
                    put("file_type", type)
                    put("upload_id", uploadId)
                    put("file_name", file.name)
                    put("srv_send_msg", false)
                },
            ),
        )
    }

    /** Sends [part], the bytes of [bytes] it covers, and tells the platform the part is complete. */
    private suspend fun sendPart(endpoints: Endpoints, uploadId: String, part: Part, bytes: ByteArray): QqBotUpload {
        val block = bytes.copyOfRange(part.start.toInt(), part.end.toInt())
        when (val put = put(part.url, block)) {
            is QqBotUpload.Failed -> return put
            is QqBotUpload.Uploaded -> Unit
        }
        val finish = api.post(
            endpoints.partFinish,
            buildJsonObject {
                put("upload_id", uploadId)
                put("part_index", part.index)
                put("block_size", (part.end - part.start).toString())
                put("md5", digest("MD5", block))
            },
        )
        if (finish !is QqBotAnswer.Ok) return failed(finish, "the platform did not take part ${part.index}")
        return QqBotUpload.Uploaded("")
    }

    /** PUTs [block] to the presigned [url] of the platform's storage, apart from its API. */
    private suspend fun put(url: String, block: ByteArray): QqBotUpload = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.firstByteTimeoutMillis))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(block))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() in 200..299) {
                QqBotUpload.Uploaded("")
            } else {
                QqBotUpload.Failed("The platform's storage answered HTTP ${response.statusCode()} for a block.", null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QqBotUpload.Failed("The block could not be sent to the platform's storage: $e", null)
        }
    }

    /** What the platform answered to an upload call, as the file info it gave or the failure it named. */
    private fun uploaded(answer: QqBotAnswer): QqBotUpload = when (answer) {
        is QqBotAnswer.Ok -> {
            val fileInfo = (answer.body as? JsonObject)?.text("file_info")
            if (fileInfo.isNullOrBlank()) {
                QqBotUpload.Failed("The platform took the file but named no file_info.", null)
            } else {
                QqBotUpload.Uploaded(fileInfo)
            }
        }

        is QqBotAnswer.Failed -> failed(answer, "the platform did not take the file")
    }

    /** [answer] as a failure, with the platform's code when it named one. */
    private fun failed(answer: QqBotAnswer, what: String): QqBotUpload.Failed {
        val failure = (answer as? QqBotAnswer.Failed)?.failure
        val said = failure?.message?.takeIf { it.isNotBlank() } ?: "the platform gave no reason"
        return QqBotUpload.Failed("$what: $said" + (failure?.code?.let { " (code $it)" } ?: ""), failure?.code)
    }

    /** The endpoints of [kind] for [openid], which the platform keeps separate between chats and groups. */
    private fun endpoints(kind: ChatKind, openid: String): Endpoints = when (kind) {
        ChatKind.DIRECT -> Endpoints(
            files = "/v2/users/$openid/files",
            prepare = "/v2/users/$openid/upload_prepare",
            partFinish = "/v2/users/$openid/upload_part_finish",
        )

        ChatKind.GROUP -> Endpoints(
            files = "/v2/groups/$openid/files",
            prepare = "/v2/groups/$openid/upload_prepare",
            partFinish = "/v2/groups/$openid/upload_part_finish",
        )

        else -> error("A chat this channel serves is a direct or a group chat.")
    }

    /**
     * The platform's `file_type` for [file]: an image, video or voice it shows as such, otherwise a file.
     *
     * A kind of file the platform shows may be sent as that kind only within its soft limit; past it the platform
     * turns the upload into a file, which is the type this names from the start.
     */
    private fun fileType(file: OutboundFile): Int {
        if (file.asFile) return FILE
        val name = file.name.lowercase()
        val mediaType = file.mediaType.lowercase()
        val type = when {
            name.endsWithAny(IMAGE_EXTENSIONS) || mediaType in IMAGE_TYPES -> IMAGE
            name.endsWithAny(VIDEO_EXTENSIONS) || mediaType in VIDEO_TYPES -> VIDEO
            name.endsWithAny(AUDIO_EXTENSIONS) || mediaType in AUDIO_TYPES -> VOICE
            else -> null
        } ?: return FILE
        val soft = when (type) {
            IMAGE -> IMAGE_SOFT_BYTES
            VIDEO -> VIDEO_SOFT_BYTES
            VOICE -> VOICE_SOFT_BYTES
            else -> QQ_BOT_MAX_FILE_BYTES
        }
        return if (file.size > soft) FILE else type
    }

    private fun String.endsWithAny(extensions: Set<String>): Boolean = extensions.any { endsWith(it) }

    private fun digest(name: String, bytes: ByteArray): String =
        MessageDigest.getInstance(name).digest(bytes).joinToString("") { "%02x".format(it) }

    private fun QqBotAnswer.objectOrNull(): JsonObject? = (this as? QqBotAnswer.Ok)?.body as? JsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.asTextOrNull()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(name: String): Long? = this[name]?.asTextOrNull()?.toLongOrNull()

    private fun JsonObject.int(name: String): Int? = this[name]?.asTextOrNull()?.toIntOrNull()

    private class Endpoints(val files: String, val prepare: String, val partFinish: String)

    /** One block of a file: where it starts in the file, where it ends, and where it is PUT. */
    private class Part(val index: Int, val url: String, val start: Long, val end: Long)

    private companion object {
        const val IMAGE = 1
        const val VIDEO = 2
        const val VOICE = 3
        const val FILE = 4

        /** The platform's code for a format it will not show, which an ordinary file may still pass. */
        const val UNSUPPORTED_FORMAT = 850019

        /** The largest file the upload call takes as base64 data. */
        const val INLINE_LIMIT: Long = 5L * 1024 * 1024

        /** The bytes of a file whose MD5 is the `md5_10m` the pre-upload asks for. */
        const val MD5_10M_BYTES = 10_002_432

        const val IMAGE_SOFT_BYTES = 20L * 1024 * 1024
        const val VIDEO_SOFT_BYTES = 30L * 1024 * 1024
        const val VOICE_SOFT_BYTES = 20L * 1024 * 1024

        val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")
        val VIDEO_EXTENSIONS = setOf(".mp4")
        val AUDIO_EXTENSIONS = setOf(".silk", ".mp3", ".wav", ".ogg")

        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")
        val VIDEO_TYPES = setOf("video/mp4")
        val AUDIO_TYPES = setOf("audio/silk", "audio/mpeg", "audio/wav", "audio/ogg")
    }
}
