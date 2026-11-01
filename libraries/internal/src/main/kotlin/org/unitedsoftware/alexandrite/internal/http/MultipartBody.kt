package org.unitedsoftware.alexandrite.internal.http

import org.unitedsoftware.alexandrite.internal.hash.hex
import org.unitedsoftware.alexandrite.sdk.net.HttpBodySource
import org.unitedsoftware.alexandrite.sdk.net.HttpRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/** A `multipart/form-data` body of text fields and files, sent with its length and each file streamed. */
public class MultipartBody private constructor(private val parts: List<Part>, private val boundary: String) {
    public val contentType: String = "multipart/form-data; boundary=$boundary"

    /** The body's length in bytes. */
    public val length: Long = parts.sumOf { it.head.size + it.size + CRLF.size } + closing().size

    /** The body as an engine sends it, each file opened when the engine's reading reaches it. */
    internal fun body(): HttpRequestBody = object : HttpRequestBody {
        override val length: Long = this@MultipartBody.length

        override fun open(): HttpBodySource = PiecesSource(
            parts.flatMap { part -> listOf(Piece.of(part.head), Piece(part.size, part.open), Piece.of(CRLF)) } +
                Piece.of(closing()),
        )
    }

    private fun closing(): ByteArray = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    public class Builder internal constructor() {
        private val parts = mutableListOf<Pending>()

        public fun field(name: String, value: String): Builder = apply {
            val encoded = value.toByteArray(Charsets.UTF_8)
            parts += Pending(disposition(name, null), null, encoded.size.toLong()) { ByteArrayInputStream(encoded) }
        }

        /** Adds the file [fileName] of [mediaType] as the field [name], its [size] bytes read from [open] when sent. */
        public fun file(
            name: String,
            fileName: String,
            mediaType: String,
            size: Long,
            open: () -> InputStream,
        ): Builder = apply {
            require(size >= 0) { "A file holds at least 0 bytes, was $size." }
            require(mediaType.isNotBlank() && mediaType.none { it == '\r' || it == '\n' }) {
                "Malformed media type '$mediaType'."
            }
            parts += Pending(disposition(name, fileName), mediaType, size, open)
        }

        public fun build(): MultipartBody {
            val boundary = "alexandrite-" + hex(ByteArray(BOUNDARY_BYTES).also(RANDOM::nextBytes))
            return MultipartBody(parts.map { it.part(boundary) }, boundary)
        }
    }

    private class Pending(
        val disposition: String,
        val mediaType: String?,
        val size: Long,
        val open: () -> InputStream,
    ) {
        fun part(boundary: String): Part {
            val head = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: ").append(disposition).append("\r\n")
                mediaType?.let { append("Content-Type: ").append(it).append("\r\n") }
                append("\r\n")
            }
            return Part(head.toByteArray(Charsets.UTF_8), size, open)
        }
    }

    private class Part(val head: ByteArray, val size: Long, val open: () -> InputStream)

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

/** The first [size] bytes of the stream that [open] opens. */
private class Piece(val size: Long, val open: () -> InputStream) {
    companion object {
        fun of(bytes: ByteArray): Piece = Piece(bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}

/** The bytes of [pieces] in order, each stream opened when the reading reaches it and none for an empty piece. */
private class PiecesSource(private val pieces: List<Piece>) : HttpBodySource {
    private var next = 0
    private var stream: InputStream? = null
    private var left = 0L

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (left == 0L) {
            stream?.close()
            stream = null
            val piece = pieces.getOrNull(next++) ?: return -1
            left = piece.size
            if (left > 0) stream = piece.open()
        }
        val count = stream!!.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (count < 0) throw IOException("A part of the body ended $left bytes short of its size.")
        left -= count
        return count
    }

    override fun close() {
        stream?.close()
        stream = null
        left = 0
        next = pieces.size
    }
}

/** The `Content-Disposition` value of the field [name], a file named [fileName] unless it is null. */
private fun disposition(name: String, fileName: String?): String =
    "form-data; name=\"${quoted(name)}\"" + fileName?.let { "; filename=\"${quoted(it)}\"" }.orEmpty()

/** [text] with the characters that would end a quoted parameter percent-encoded. */
private fun quoted(text: String): String = text.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)

private const val BOUNDARY_BYTES = 16

private val RANDOM = SecureRandom()
