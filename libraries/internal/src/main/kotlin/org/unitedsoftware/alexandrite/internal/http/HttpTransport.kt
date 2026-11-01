package org.unitedsoftware.alexandrite.internal.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineResponse
import org.unitedsoftware.alexandrite.sdk.net.HttpHeaders
import org.unitedsoftware.alexandrite.sdk.net.HttpRequestBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The timeouts of an [HttpTransport]. */
public class HttpTimeouts(
    public val connect: Duration,
    /** From sending a request until the first byte of its response body. */
    public val firstByte: Duration,
    /** The longest pause between two pieces of a response body. */
    public val idle: Duration,
) {
    init {
        for ((name, value) in listOf("connect" to connect, "first byte" to firstByte, "idle" to idle)) {
            require(value.isPositive() && value.isFinite()) { "The $name timeout is positive and finite, was $value." }
        }
    }
}

/** One HTTP request, printed without its headers and body. */
public class HttpCall(
    public val method: String,
    public val uri: URI,
    public val headers: Map<String, String> = emptyMap(),
    public val body: String? = null,
    /** Values masked in every message about the call. */
    public val secrets: Collection<String> = emptyList(),
    /** The first-byte timeout of this call, null for the transport's. */
    public val firstByte: Duration? = null,
    /** A body sent in place of [body], with its own content type. */
    public val multipart: MultipartBody? = null,
) {
    private val masked = secrets.filter { it.length >= MIN_SECRET }.distinct().sortedByDescending { it.length }

    /** The UTF-8 bytes of the longest secret that [mask] masks. */
    internal val longestSecret: Int = masked.maxOfOrNull { it.toByteArray(Charsets.UTF_8).size } ?: 0

    init {
        require(uri.scheme == "http" || uri.scheme == "https") { "No HTTP URI: ${mask(printable(uri))}." }
        require(firstByte == null || (firstByte.isPositive() && firstByte.isFinite())) {
            "The first byte timeout is positive and finite, was $firstByte."
        }
        require(body == null || multipart == null) { "A call has one body." }
    }

    /** [text] with [secrets] masked. */
    public fun mask(text: String): String = mask(text, text.length)

    /** The first [length] characters of [text] with [secrets] masked, one that starts before the cut masked whole. */
    internal fun mask(text: String, length: Int): String = buildString {
        var index = 0
        while (index < length) {
            val secret = masked.firstOrNull { text.startsWith(it, index) }
            if (secret == null) {
                append(text[index])
                index++
            } else {
                append(MASK)
                index += secret.length
            }
        }
    }

    override fun toString(): String = "$method ${mask(printable(uri))}"

    private companion object {
        const val MASK = "***"
        const val MIN_SECRET = 4
    }
}

/** Whether [value] holds neither a control character other than tab nor one beyond Latin-1. */
public fun isHeaderValue(value: String): Boolean = value.all { it == '\t' || (!it.isISOControl() && it <= '\u00ff') }

/** [uri] without user info, query and fragment. */
public fun printable(uri: URI): String = buildString {
    append(uri.scheme).append("://").append(uri.host ?: "")
    if (uri.port >= 0) append(':').append(uri.port)
    append(uri.rawPath ?: "")
}

/** Sends HTTP/1.1 requests through [engine] with [timeouts], and ends an exchange once its call is cancelled. */
public class HttpTransport(
    private val timeouts: HttpTimeouts,
    private val engine: HttpEngine,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val exchanges = ConcurrentHashMap.newKeySet<Exchange>()

    @Volatile
    private var closed = false

    /** The body of [call]'s response as text, its first [limit] bytes at most. */
    public suspend fun send(call: HttpCall, limit: Int = DEFAULT_LIMIT): String = open(call) { it.text(limit) }

    /**
     * Runs [block] on the response to [call] once it has a 2xx status, and ends the exchange when [block] returns.
     *
     * Throws an [HttpException] when the call fails or the response has another status, and when its body fails while
     * [block] reads it.
     */
    public suspend fun <T> open(call: HttpCall, block: suspend (HttpResponseStream) -> T): T {
        val started = TimeSource.Monotonic.markNow()
        call.headers.entries.firstOrNull { !isHeaderValue(it.value) }?.let { (name, _) ->
            throw HttpException(
                HttpFailureKind.PROTOCOL,
                "$call cannot be sent: the value of the header '$name' holds a control character or one beyond " +
                    "Latin-1.",
            )
        }
        val request = try {
            request(call)
        } catch (e: IllegalArgumentException) {
            throw refused(call)
        }
        val firstByte = call.firstByte ?: timeouts.firstByte
        val exchange = Exchange()
        exchanges += exchange
        try {
            if (closed) throw closedFailure(call)
            val response = response(call, request, exchange, firstByte)
            val stream = HttpResponseStream(call, response, started + firstByte, timeouts.idle)
            if (response.status !in 200..299) throw stream.failure(clock)
            return block(stream)
        } finally {
            exchanges -= exchange
            exchange.end()
        }
    }

    /** Ends its calls in flight and refuses later ones. */
    override fun close() {
        closed = true
        exchanges.forEach { it.end() }
    }

    /** The response to [request], which comes within [firstByte] unless [close] ends [exchange] first. */
    private suspend fun response(
        call: HttpCall,
        request: HttpEngineRequest,
        exchange: Exchange,
        firstByte: Duration,
    ): HttpEngineResponse = supervisorScope {
        // Undispatched, so that the request is on its way when the caller first suspends; held, so that the
        // exchange's end closes a response that comes after the timeout
        val opening = async(start = CoroutineStart.UNDISPATCHED) {
            engine.open(request).also { exchange.response = it }
        }
        exchange.opening = opening
        if (closed) opening.cancel()
        val response = try {
            withTimeoutOrNull(firstByte) { opening.await() }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive || !opening.isCancelled) throw e
            throw closedFailure(call)
        } catch (e: IllegalArgumentException) {
            throw refused(call)
        } catch (e: Exception) {
            throw HttpException(HttpFailureKind.CONNECTION, call.mask("$call failed: $e"), cause = e)
        }
        if (response == null) {
            opening.cancel()
            throw HttpException(HttpFailureKind.TIMEOUT, "$call got no response within $firstByte.")
        }
        if (closed) throw closedFailure(call)
        response
    }

    private fun request(call: HttpCall): HttpEngineRequest {
        val builder = HttpEngineRequest.builder(call.method, call.uri.toString()).connectTimeout(timeouts.connect)
        call.headers.forEach { (name, value) -> builder.header(name, value) }
        val multipart = call.multipart
        if (multipart != null && call.headers.keys.none { it.equals(CONTENT_TYPE, ignoreCase = true) }) {
            builder.header(CONTENT_TYPE, multipart.contentType)
        }
        builder.body(multipart?.body() ?: call.body?.let { HttpRequestBody.of(it) })
        return builder.build()
    }

    private fun refused(call: HttpCall): HttpException =
        HttpException(HttpFailureKind.PROTOCOL, "$call cannot be sent: its method, URI or a header is invalid.")

    private fun closedFailure(call: HttpCall): HttpException =
        HttpException(HttpFailureKind.CONNECTION, "$call failed: the transport is closed.")

    /** One call's exchange, which its call or [close] ends. */
    private class Exchange {
        @Volatile
        var opening: Deferred<HttpEngineResponse>? = null

        @Volatile
        var response: HttpEngineResponse? = null

        fun end() {
            opening?.cancel()
            response?.close()
        }
    }

    public companion object {
        /** The bytes [send] reads at most by default. */
        public const val DEFAULT_LIMIT: Int = 32 * 1024 * 1024

        /** The bytes of an error response's body that an [HttpException] keeps. */
        public const val ERROR_BODY_LIMIT: Int = 64 * 1024

        private const val CONTENT_TYPE = "content-type"
    }
}

/** The response to an [HttpCall], whose body is read once. */
public class HttpResponseStream internal constructor(
    private val call: HttpCall,
    private val response: HttpEngineResponse,
    private val firstByteDeadline: TimeSource.Monotonic.ValueTimeMark,
    private val idle: Duration,
) {
    private var read = false

    public val status: Int = response.status

    public val headers: HttpHeaders = response.headers

    /** The media type of the body in lower case without its parameters, null when the response names none. */
    public val mediaType: String? = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }

    /** The next piece of the body, null at its end. */
    public suspend fun read(): ByteArray? {
        val wait = if (read) idle else -firstByteDeadline.elapsedNow()
        val piece = withTimeoutOrNull(wait.coerceAtLeast(Duration.ZERO)) { Piece(receive()) }
            ?: throw HttpException(
                HttpFailureKind.TIMEOUT,
                if (read) "$call got no data for $idle." else "$call got no data in time.",
                status = status,
                afterResponse = read,
            )
        read = true
        return piece.bytes ?: return null
    }

    /** The server-sent events of the body, the last one dispatched also when the body ends without a blank line. */
    public fun events(): Flow<SseEvent> = flow {
        val parser = SseParser()
        while (true) {
            val bytes = read() ?: break
            for (event in parsed { parser.feed(bytes) }) emit(event)
        }
        parsed { parser.finish() }?.let { emit(it) }
    }

    /**
     * Reads what is left of the body for at most [grace] and drops it, so that a server sees its answer taken whole
     * rather than cut off: a proxy counts a stream closed before its end as a failed request.
     */
    public suspend fun drain(grace: Duration = DRAIN_GRACE) {
        try {
            withTimeoutOrNull(grace) {
                while (true) read() ?: break
            }
        } catch (e: HttpException) {
            // A body that breaks off after the answer took nothing from it
            return
        }
    }

    /** The body as text, its first [limit] bytes at most. */
    public suspend fun text(limit: Int): String = bytes(limit).toByteArray().toString(Charsets.UTF_8)

    /** The failure that this response's status means. */
    internal suspend fun failure(clock: Clock): HttpException {
        val text = try {
            errorBody()
        } catch (e: HttpException) {
            null
        }
        return HttpException(
            HttpFailureKind.of(status),
            "$call got HTTP $status" + (text?.let { ": ${it.take(MESSAGE_BODY)}" } ?: "."),
            status = status,
            retryAfter = retryAfter(headers, clock.instant()),
            requestId = requestId(headers),
            body = text,
            headers = headers,
        )
    }

    private suspend fun bytes(limit: Int): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        while (out.size() < limit) {
            val bytes = read() ?: break
            out.write(bytes, 0, minOf(bytes.size, limit - out.size()))
        }
        return out
    }

    /** The body's first [HttpTransport.ERROR_BODY_LIMIT] bytes, masked before the cut, null when blank. */
    private suspend fun errorBody(): String? {
        val text = bytes(HttpTransport.ERROR_BODY_LIMIT + call.longestSecret).toByteArray().toString(Charsets.UTF_8)
        if (text.isBlank()) return null
        return call.mask(text, prefixWithin(text, HttpTransport.ERROR_BODY_LIMIT))
    }

    private inline fun <T> parsed(parse: () -> T): T = try {
        parse()
    } catch (e: HttpException) {
        throw HttpException(e.kind, "$call broke off: ${e.message}", status = status, afterResponse = true)
    }

    private suspend fun receive(): ByteArray? = try {
        response.body.read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw HttpException(
            HttpFailureKind.CONNECTION,
            call.mask("$call broke off: $e"),
            status = status,
            afterResponse = true,
            cause = e,
        )
    }

    private class Piece(val bytes: ByteArray?)

    private companion object {
        /** The characters of an error body that a message quotes. */
        const val MESSAGE_BODY = 500
    }
}

/** The length of the longest prefix of [text] that takes at most [bytes] bytes in UTF-8. */
private fun prefixWithin(text: String, bytes: Int): Int {
    var used = 0
    var index = 0
    while (index < text.length) {
        val point = text.codePointAt(index)
        used += when {
            point < 0x80 -> 1
            point < 0x800 -> 2
            point < 0x10000 -> 3
            else -> 4
        }
        if (used > bytes) break
        index += Character.charCount(point)
    }
    return index
}

/** How long a finished answer's stream is read on for its end. */
private val DRAIN_GRACE: Duration = 5.seconds
