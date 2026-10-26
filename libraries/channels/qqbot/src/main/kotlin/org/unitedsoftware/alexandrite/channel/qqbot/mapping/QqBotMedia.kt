package org.unitedsoftware.alexandrite.channel.qqbot.mapping

import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.sdk.channel.MediaContent
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.io.ByteArrayOutputStream
import java.net.URI

/** Reads the bytes of a media file a message of this platform carries, from the address it names. */
@ChannelInstanceScoped
internal class QqBotMedia(private val http: QqBotHttp) {
    /** What reads the bytes at [url], at most as many as a caller allows. */
    fun content(url: String): MediaContent {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
            return MediaContent { throw IllegalArgumentException("'$url' is no address a file is read from.") }
        }
        return MediaContent { maxBytes -> read(uri, maxBytes) }
    }

    private suspend fun read(uri: URI, maxBytes: Long): ByteArray = http.open(HttpCall("GET", uri)) { response ->
        val out = ByteArrayOutputStream()
        while (true) {
            val piece = response.read() ?: break
            if (out.size().toLong() + piece.size > maxBytes) {
                throw IllegalArgumentException("The file at $uri holds more than the $maxBytes bytes allowed here.")
            }
            out.write(piece)
        }
        out.toByteArray()
    }
}
