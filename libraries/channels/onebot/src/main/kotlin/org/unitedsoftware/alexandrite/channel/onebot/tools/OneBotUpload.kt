package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * How much of a file one call of the streaming upload carries.
 *
 * The implementation takes a file in pieces so that neither side holds all of it, and this is the piece it is given:
 * the size the reference implementation of this protocol sends, which the platform takes.
 */
internal const val STREAM_CHUNK: Int = 256 * 1024

/**
 * How long the implementation keeps the file it was sent, in milliseconds.
 *
 * The file is streamed to the implementation and then sent on to a chat by the reference it answers with, so it has to
 * outlive the streaming by however long sending takes.
 */
internal const val STREAM_RETENTION_MS: Long = 300_000

/** The action that takes a file in pieces, which the standard does not declare and an implementation may have. */
internal const val UPLOAD_STREAM: String = "upload_file_stream"

/** Where an implementation names the file it was sent, which is what sending it on refers to. */
private val STREAM_REFERENCE: List<String> = listOf("file_path", "file", "path", "url")

/**
 * [path] sent to the implementation in pieces, answered by what the file can then be sent on by.
 *
 * The file is read twice and never held whole: once to say how large it is and what its digest is, which every piece
 * is sent with, and once to send the pieces themselves. An implementation that refuses a piece, or that answers
 * without naming the file, ends the sending, and nothing is sent on afterwards, so a file that was only partly
 * streamed is never sent as if it were whole.
 *
 * [send] is one call of the action, which is what the tests stand in for.
 */
internal suspend fun streamFile(
    path: Path,
    name: String,
    send: suspend (JsonObject) -> OneBotResult<JsonElement>,
): String? = withContext(Dispatchers.IO) {
    val size = Files.size(path)
    val digest = digest(path)
    val total = ((size + STREAM_CHUNK - 1) / STREAM_CHUNK).toInt()
    val stream = UUID.randomUUID().toString()
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(STREAM_CHUNK)
        var index = 0
        while (index < total) {
            val read = input.readNBytes(buffer, 0, STREAM_CHUNK)
            if (read <= 0) break
            val piece = if (read == buffer.size) buffer else buffer.copyOf(read)
            val call = buildJsonObject {
                put("stream_id", stream)
                put("chunk_data", Base64.getEncoder().encodeToString(piece))
                put("chunk_index", index)
                put("total_chunks", total)
                put("file_size", size)
                put("expected_sha256", digest)
                put("filename", name)
                put("file_retention", STREAM_RETENTION_MS)
            }
            if (send(call) !is OneBotResult.Ok) return@withContext null
            index++
        }
    }
    val completed = send(
        buildJsonObject {
            put("stream_id", stream)
            put("is_complete", true)
            put("file_retention", STREAM_RETENTION_MS)
        },
    )
    (completed as? OneBotResult.Ok)?.data?.reference()
}

/**
 * The name [data] gives the file it was sent, null when it names none.
 *
 * The reference is a field of what the implementation answered, which is where the reference implementation of this
 * protocol reads it. One that answers under a field of its own is read as well, since what the file is called is the
 * implementation's to decide.
 */
private fun JsonElement.reference(): String? {
    val fields = this as? JsonObject ?: return null
    return fields.reference() ?: (fields["data"] as? JsonObject)?.reference()
}

/** The first of the names an implementation may give the file under, which is how the file is referred to after. */
private fun JsonObject.reference(): String? = STREAM_REFERENCE.firstNotNullOfOrNull { name ->
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
}

/** What [path] holds, as the digest the pieces of it are sent with. */
private fun digest(path: Path): String {
    val sha = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(STREAM_CHUNK)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sha.update(buffer, 0, read)
        }
    }
    return sha.digest().joinToString("") { "%02x".format(it) }
}
