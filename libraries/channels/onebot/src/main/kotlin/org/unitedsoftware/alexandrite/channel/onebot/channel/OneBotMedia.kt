package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.onebot.mapping.MediaReader
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetImage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetRecord
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.http.common.HttpCall
import org.unitedsoftware.alexandrite.http.common.HttpException
import org.unitedsoftware.alexandrite.http.common.HttpResponseStream
import org.unitedsoftware.alexandrite.http.common.HttpTimeouts
import org.unitedsoftware.alexandrite.http.common.HttpTransport
import org.unitedsoftware.alexandrite.http.common.MAX_REDIRECTS
import org.unitedsoftware.alexandrite.http.common.redirectTarget
import org.unitedsoftware.alexandrite.sdk.channel.MediaContent
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlin.time.Duration.Companion.seconds

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
internal class OneBotMedia(private val link: OneBotLink, engine: HttpEngine) :
    MediaReader,
    AutoCloseable {
    private val transport = HttpTransport(HttpTimeouts(CONNECT, READ, READ), engine)

    override fun content(segment: OneBotSegment): MediaContent? = when (segment) {
        is OneBotSegment.Image -> content(segment.url, segment.file, "get_image")
        is OneBotSegment.Record -> content(segment.url, segment.file, "get_record")
        is OneBotSegment.Video -> content(segment.url, segment.file, null)
        else -> null
    }

    override fun close() = transport.close()

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

    /** At most [maxBytes] of what [address] answers, following its redirects except from https to http. */
    private suspend fun fetch(address: String, maxBytes: Long): ByteArray {
        var target = address
        repeat(MAX_REDIRECTS + 1) {
            val call = try {
                HttpCall("GET", URI(target))
            } catch (e: Exception) {
                throw IllegalStateException("Cannot read $address", e)
            }
            try {
                return transport.open(call) { stream -> stream.readAtMost(address, maxBytes) }
            } catch (e: HttpException) {
                val status = e.status
                if (status == null || e.afterResponse) throw IllegalStateException("Cannot read $address", e)
                target = e.headers?.get("location")?.takeIf { status in 300..399 }?.let { redirectTarget(target, it) }
                    ?: throw IllegalStateException("Reading $address answered $status.")
            }
        }
        throw IllegalStateException("$address redirects more than $MAX_REDIRECTS times.")
    }

    /** [maxBytes] of the body, and no more: one byte past it says the body is longer than that. */
    private suspend fun HttpResponseStream.readAtMost(address: String, maxBytes: Long): ByteArray {
        val size = headers["content-length"]?.toLongOrNull() ?: -1
        if (size > maxBytes) throw IllegalStateException("$address is larger than $maxBytes bytes.")
        val kept = ByteArrayOutputStream()
        while (true) {
            val piece = read() ?: break
            if (kept.size() + piece.size > maxBytes) {
                throw IllegalStateException("The body is larger than $maxBytes bytes.")
            }
            kept.write(piece)
        }
        return kept.toByteArray()
    }

    private companion object {
        val CONNECT = 10.seconds

        val READ = 30.seconds

        /** What a voice message is converted to when its bytes are read, which every implementation takes. */
        const val RECORD_FORMAT = "mp3"
    }
}

/** Whether [this] is an address this host can read. */
private fun String.isHttp(): Boolean = startsWith("http://") || startsWith("https://")
