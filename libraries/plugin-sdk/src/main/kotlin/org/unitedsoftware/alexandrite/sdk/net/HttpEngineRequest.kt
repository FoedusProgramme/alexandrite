package org.unitedsoftware.alexandrite.sdk.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A request for an [HttpEngine], whose string form shows the method and the origin alone. */
public class HttpEngineRequest private constructor(
    /** An HTTP token, sent as given. */
    public val method: String,
    /** An absolute `http` or `https` URL. */
    public val url: String,
    /** None of [RESERVED_HEADERS], each sent with its name as given and its values free of control characters. */
    public val headers: HttpHeaders,
    /** Null for no body. */
    public val body: HttpRequestBody?,
    public val connectTimeout: Duration,
) {
    init {
        require(TOKEN.matches(method)) { "An HTTP method is a token, was '$method'." }
        require(SCHEME.containsMatchIn(url)) { "An engine sends only http and https URLs." }
        for (name in headers.names) {
            require(TOKEN.matches(name)) { "A header name is a token, was '$name'." }
            require(name.lowercase() !in RESERVED_HEADERS) { "The engine writes the header '$name' itself." }
            require(headers.all(name).all { value -> value.all { it == '\t' || (it >= ' ' && it != '\u007f') } }) {
                "The value of the header '$name' holds a control character."
            }
        }
        require(connectTimeout.isPositive() && connectTimeout.isFinite()) {
            "A connect timeout is positive and finite, was $connectTimeout."
        }
    }

    public fun toBuilder(): Builder = Builder(method, url).headers(headers).body(body).connectTimeout(connectTimeout)

    override fun toString(): String = "HttpEngineRequest($method ${origin(url)})"

    public class Builder internal constructor(private var method: String, private var url: String) {
        private val headers = mutableListOf<Pair<String, String>>()
        private var body: HttpRequestBody? = null
        private var connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT

        public fun method(method: String): Builder = apply { this.method = method }

        public fun url(url: String): Builder = apply { this.url = url }

        /** Adds [value] to the values of the header [name]. */
        public fun header(name: String, value: String): Builder = apply { headers += name to value }

        /** Replaces every header with [headers]. */
        public fun headers(headers: HttpHeaders): Builder = apply {
            this.headers.clear()
            headers.toMap().forEach { (name, values) -> values.forEach { this.headers += name to it } }
        }

        public fun body(body: HttpRequestBody?): Builder = apply { this.body = body }

        public fun connectTimeout(connectTimeout: Duration): Builder = apply { this.connectTimeout = connectTimeout }

        /** Throws an [IllegalArgumentException] when the request breaks a rule of [HttpEngineRequest]. */
        public fun build(): HttpEngineRequest =
            HttpEngineRequest(method, url, HttpHeaders.of(*headers.toTypedArray()), body, connectTimeout)
    }

    public companion object {
        public val DEFAULT_CONNECT_TIMEOUT: Duration = 10.seconds

        /** The headers an engine writes itself. */
        public val RESERVED_HEADERS: Set<String> =
            setOf("connection", "content-length", "expect", "host", "keep-alive", "te", "transfer-encoding", "upgrade")

        public fun builder(method: String, url: String): Builder = Builder(method, url)
    }
}

public inline fun HttpEngineRequest.rebuild(block: HttpEngineRequest.Builder.() -> Unit): HttpEngineRequest =
    toBuilder().apply(block).build()

/** The body of a request, of [length] bytes, which an engine reads from a source it opens as it sends the request. */
public interface HttpRequestBody {
    public val length: Long

    /** A new source of the body from its first byte, which the engine closes. */
    public fun open(): HttpBodySource

    public companion object {
        /** A body of [bytes], which it keeps without copying them. */
        public fun of(bytes: ByteArray): HttpRequestBody = BytesBody(bytes)

        /** A body of [text] in UTF-8. */
        public fun of(text: String): HttpRequestBody = BytesBody(text.encodeToByteArray())
    }
}

/** Bytes an engine reads in parts on a thread of its own. */
public interface HttpBodySource : AutoCloseable {
    /** Reads at most [length] bytes into [buffer] at [offset], blocking, and answers how many, -1 at the end. */
    public fun read(buffer: ByteArray, offset: Int, length: Int): Int

    override fun close() {}
}

private class BytesBody(private val bytes: ByteArray) : HttpRequestBody {
    override val length: Long get() = bytes.size.toLong()

    override fun open(): HttpBodySource = object : HttpBodySource {
        private var position = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}

/** The scheme, host and port of [url], without user info. */
private fun origin(url: String): String {
    val scheme = url.substringBefore("://", "")
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return "$scheme://${authority.substringAfterLast('@')}"
}

private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
