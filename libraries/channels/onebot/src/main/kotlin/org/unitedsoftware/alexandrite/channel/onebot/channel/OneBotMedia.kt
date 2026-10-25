package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.onebot.mapping.MediaReader
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetImage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetRecord
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.sdk.channel.MediaContent
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The bytes a reported segment names, which the agent reads as the input of a turn.
 *
 * A report names an image by an address it can be read from, and sometimes by a name the implementation keeps it
 * under. The address is read directly; a name is turned into an address by asking the implementation for it.
 *
 * What cannot be read is left out of the turn with a note. A read stops at the size the agent allows, and a body
 * larger than that is not read further.
 */
@ChannelInstanceScoped
internal class OneBotMedia(private val link: OneBotLink) : MediaReader {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun content(segment: OneBotSegment): MediaContent? = when (segment) {
        is OneBotSegment.Image -> content(segment.url, segment.file, "get_image")
        is OneBotSegment.Record -> content(segment.url, segment.file, "get_record")
        is OneBotSegment.Video -> content(segment.url, segment.file, null)
        else -> null
    }

    /** The bytes of [url], or of the [file] the implementation keeps under [action] when no [url] is reported. */
    private fun content(url: String?, file: String?, action: String?): MediaContent? {
        if (url.isNullOrEmpty() && (file.isNullOrEmpty() || action == null)) return null
        return MediaContent { maxBytes -> read(url, file, action, maxBytes) }
    }

    private suspend fun read(url: String?, file: String?, action: String?, maxBytes: Long): ByteArray {
        val address = url?.takeIf { it.isHttp() } ?: file?.takeIf { it.isHttp() } ?: ask(action, file)
        return fetch(address, maxBytes)
    }

    /** The address the implementation keeps [file] at, which may be one this host cannot read. */
    private suspend fun ask(action: String?, file: String?): String {
        require(!file.isNullOrEmpty() && action != null) { "The report names no address and no file to ask about." }
        val params = when (action) {
            "get_image" -> GetImage(file).toJson()
            else -> GetRecord(file, RECORD_FORMAT).toJson()
        }
        val result = link.call(action, params)
        val data = (result as? OneBotResult.Ok)?.data
            ?: throw IllegalStateException("The implementation did not report where '$file' is kept.")
        val kept = data.jsonObject["file"]?.jsonPrimitive?.content
        return kept?.takeIf { it.isHttp() }
            ?: throw IllegalStateException("The implementation keeps '$file' where this host cannot read it.")
    }

    /** At most [maxBytes] of what [address] answers. */
    private suspend fun fetch(address: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(address))
            .timeout(Duration.ofSeconds(READ_SECONDS))
            .GET()
            .build()
        val answer = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Cannot read $address", e)
        }
        if (answer.statusCode() !in 200..299) {
            throw IllegalStateException("Reading $address answered ${answer.statusCode()}.")
        }
        val size = answer.headers().firstValueAsLong("content-length").orElse(-1)
        if (size > maxBytes) throw IllegalStateException("$address is larger than $maxBytes bytes.")
        answer.body().use { body -> body.readAtMost(maxBytes) }
    }

    /** [maxBytes] of [this], and no more: one byte past it says the body is longer than that. */
    private fun java.io.InputStream.readAtMost(maxBytes: Long): ByteArray {
        val kept = java.io.ByteArrayOutputStream()
        val piece = ByteArray(PIECE_BYTES)
        while (true) {
            val read = read(piece)
            if (read < 0) break
            if (kept.size() + read > maxBytes) throw IllegalStateException("The body is larger than $maxBytes bytes.")
            kept.write(piece, 0, read)
        }
        return kept.toByteArray()
    }

    private companion object {
        const val CONNECT_SECONDS = 10L

        const val READ_SECONDS = 30L

        const val PIECE_BYTES = 16 * 1024

        /** What a voice message is converted to when its bytes are read, which every implementation takes. */
        const val RECORD_FORMAT = "mp3"
    }
}

/** Whether [this] is an address this host can read. */
private fun String.isHttp(): Boolean = startsWith("http://") || startsWith("https://")
