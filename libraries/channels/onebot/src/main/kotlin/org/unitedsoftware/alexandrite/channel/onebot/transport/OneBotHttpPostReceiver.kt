package org.unitedsoftware.alexandrite.channel.onebot.transport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.channel.onebot.auth.OneBotAuth
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEventCodec
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Why a report was not accepted, as the status the implementation is answered with. */
public enum class OneBotReportRejection(public val status: Int, public val reason: String) {
    /** The request is not a POST of a JSON body. */
    NOT_A_REPORT(400, "a report is a POST of a JSON body"),

    /** The request names another path than the one this instance accepts reports on. */
    WRONG_PATH(404, "no report is accepted on this path"),

    /** The report is larger than a report may be. */
    TOO_LARGE(413, "the report is too large"),

    /** Every thread of this instance is reading a report and the queue behind them is full. */
    BUSY(503, "the reports of this instance are being read as fast as they arrive"),

    /** The report carries no signature, or one that does not match its body. */
    BAD_SIGNATURE(403, "the signature of the report does not match its body"),

    /** The report names another account than the one this instance serves. */
    WRONG_ACCOUNT(403, "the report names another account"),

    /** The body is no event of the standard. */
    MALFORMED(400, "the report is no event of the standard"),
}

/** What this instance does with one report. */
public sealed interface OneBotReportAnswer {
    /** The report was taken, and nothing more is said. */
    public data object Accepted : OneBotReportAnswer

    /** The report was taken, and the implementation should run [operation] as a quick operation. */
    public data class QuickOperation(public val operation: JsonObject) : OneBotReportAnswer

    /** The report was refused, and the implementation is told why. */
    public data class Rejected(public val rejection: OneBotReportRejection) : OneBotReportAnswer
}

/**
 * The reports of an implementation that sends them over HTTP POST.
 *
 * One report is one POST of an event, signed with the shared secret when the instance has one, and the answer of the
 * receiver is either empty or a quick operation the implementation runs. Alexandrite listens, so this is the side
 * that accepts.
 */
internal class OneBotHttpPostReceiver(
    private val settings: OneBotSettings,
    /** What this instance does with [event], the report it just took. */
    private val answer: (OneBotEvent) -> OneBotReportAnswer = { OneBotReportAnswer.Accepted },
) : AbstractOneBotConnection(settings.eventCapacity) {
    private val accepting = AtomicBoolean()
    private val acceptedReports = java.util.concurrent.atomic.AtomicInteger()
    private var server: ServerSocket? = null
    private var acceptor: Thread? = null

    /**
     * The threads that read reports, bounded so that a client cannot hold one thread per connection it opens.
     *
     * The pool is small and its queue shorter: a listener of one chat is not the busiest thing in the process, and a
     * report arrives in a burst of one. Past that a connection is refused at once as busy, which is what stops a
     * slow sender from occupying the process.
     */
    private val readers: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(
        MAX_CONCURRENT_REPORTS,
        MAX_CONCURRENT_REPORTS,
        0L,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(MAX_QUEUED_REPORTS),
        java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable, "onebot-http-post-report-$reportPort").apply { isDaemon = true }
        },
    )

    /** The port this receiver bound, which names its threads and which a refusal is about. */
    private val reportPort: Int get() = server?.localPort ?: settings.listenPort

    /** How many reports this receiver took, which tells a refused one from an accepted one. */
    internal val reportedCount: Int get() = acceptedReports.get()

    /** Starts listening and returns once the socket is bound. */
    public fun start() {
        check(!accepting.getAndSet(true)) { "The receiver is already open." }
        moveTo(OneBotLinkState.CONNECTING)
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(settings.listenHost, settings.listenPort))
            }
        } catch (e: IOException) {
            accepting.set(false)
            lost(e)
            throw e
        }
        server = socket
        acceptor = thread(name = "onebot-http-post-${socket.localPort}", isDaemon = true) { acceptLoop(socket) }
        moveTo(OneBotLinkState.OPEN)
    }

    /** The port this receiver listens on, which a configured port of 0 leaves to the operating system. */
    public val port: Int get() = server?.localPort ?: settings.listenPort

    override suspend fun send(action: String, params: JsonObject): JsonObject = throw UnsupportedOperationException(
        "An implementation that reports over HTTP POST is called over HTTP; use its endpoint as the API.",
    )

    override fun stop() {
        accepting.set(false)
        runCatching { server?.close() }
        acceptor?.let { runCatching { it.join(CLOSE_TIMEOUT_MILLIS) } }
        // A reader that is mid-report keeps running until its deadline; one that has not started is dropped, so that
        // a stopping receiver reads no more than it already took.
        readers.shutdownNow()
        server = null
        acceptor = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (accepting.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (accepting.get()) lost(e)
                return
            }
            try {
                readers.execute { client.use { exchange(it) } }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // Every reader is busy and the queue is full, so this connection is answered as busy at once instead
                // of being held. A report that is refused this way can be sent again.
                client.use { respond(it, OneBotReportRejection.BUSY) }
            }
        }
    }

    private fun exchange(client: Socket) {
        try {
            // An idle timeout only measures the gap between two reads, so one deadline covers the whole request: a
            // client that sends a byte at a time is still cut off at the deadline.
            val deadline = System.nanoTime() + settings.firstByteTimeoutMillis * 1_000_000
            client.soTimeout = settings.firstByteTimeoutMillis.toInt()
            val input = BufferedInputStream(client.getInputStream())
            // A report larger than this instance reads is refused as such, which is what the implementation is told.
            // Its declared body is read into nothing first, so that the answer is read by a peer that is still
            // sending, before the connection can close under it.
            val request = when (val outcome = read(input, deadline)) {
                is ReadOutcome.TooLarge -> {
                    drain(input, outcome.declaredBytes, deadline)
                    return respond(client, OneBotReportRejection.TOO_LARGE)
                }

                is ReadOutcome.Read -> outcome.request

                ReadOutcome.NotAReport -> return respond(client, OneBotReportRejection.NOT_A_REPORT)
            }
            if (request.method != "POST" || request.path != settings.path) {
                val rejection =
                    if (request.method ==
                        "POST"
                    ) {
                        OneBotReportRejection.WRONG_PATH
                    } else {
                        OneBotReportRejection.NOT_A_REPORT
                    }
                return respond(client, rejection)
            }
            if (request.body.length > MAX_REPORT_BYTES) return respond(client, OneBotReportRejection.TOO_LARGE)
            val secret = settings.secret
            val signature = request.headers[OneBotAuth.SIGNATURE.lowercase()]
            if (secret != null && !OneBotAuth.matchesSignature(secret, request.body, signature)) {
                return respond(client, OneBotReportRejection.BAD_SIGNATURE)
            }
            val event = try {
                OneBotEventCodec.decode(OneBotWire.parseToJsonElement(request.body))
            } catch (e: Exception) {
                return respond(client, OneBotReportRejection.MALFORMED)
            }
            val selfId = settings.selfId
            val reported = request.headers[OneBotAuth.SELF_ID.lowercase()] ?: reportedInBody(request.body)
            // An instance that serves one account takes no report that names none: the account is what binds the
            // report to this instance, so a missing one is as wrong as a different one. This is asked after the body
            // is read, so that a report that is no event at all is told apart from one of another account.
            if (selfId != null && reported != selfId) {
                return respond(client, OneBotReportRejection.WRONG_ACCOUNT)
            }
            // The decision of this instance is taken first: a report it refuses is answered with that refusal and is
            // not reported to anyone, because the implementation has already been told it was not taken.
            when (val taken = answer(event)) {
                is OneBotReportAnswer.Rejected -> return respond(client, taken.rejection)

                is OneBotReportAnswer.QuickOperation -> {
                    report(event)
                    acceptedReports.incrementAndGet()
                    respond(client, taken.operation)
                }

                OneBotReportAnswer.Accepted -> {
                    report(event)
                    acceptedReports.incrementAndGet()
                    respond(client, null)
                }
            }
        } catch (e: IOException) {
            failure = e
        } catch (e: Exception) {
            failure = e
        }
    }

    private fun respond(client: Socket, rejection: OneBotReportRejection) {
        respond(client, null, rejection.status, rejection.reason)
    }

    private fun respond(client: Socket, operation: JsonObject?) {
        respond(client, operation, 200, null)
    }

    private fun respond(client: Socket, operation: JsonObject?, initialStatus: Int, detail: String?) {
        val status = if (operation == null && initialStatus == 200) 204 else initialStatus
        val body = operation?.let(OneBotWire::encodeToString) ?: detail.orEmpty()
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        // A quick operation is an object the implementation reads as JSON; a rejection is a sentence for whoever wrote
        // it.
        val type = if (operation == null) "text/plain; charset=utf-8" else "application/json"
        val out = client.getOutputStream()
        out.write(
            buildString {
                append("HTTP/1.1 $status ${if (status == 204) "No Content" else "OK"}\r\n")
                if (status != 204) append("Content-Type: $type\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII),
        )
        out.write(bytes)
        out.flush()
    }

    /** The `self_id` of the report in [body], null when the body names none. */
    private fun reportedInBody(body: String): String? = try {
        (OneBotWire.parseToJsonElement(body) as? JsonObject)?.let { report ->
            (report["self_id"] as? JsonPrimitive)?.content
        }
    } catch (e: Exception) {
        null
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    /** What reading the head of a connection came to. */
    private sealed interface ReadOutcome {
        /** A request this receiver reads. */
        data class Read(val request: Request) : ReadOutcome

        /** A body whose declared length is over what this instance reads, refused before it is allocated. */
        data class TooLarge(val declaredBytes: Int) : ReadOutcome

        /** Not an HTTP request this receiver reads. */
        data object NotAReport : ReadOutcome
    }

    /**
     * Reads [declaredBytes] of a body that will not be kept, in fixed pieces.
     *
     * Reading nothing would answer a peer that is still sending, and reading it in one piece is what the limit
     * exists to prevent, so it is read in pieces small enough to be forgotten.
     */
    private fun drain(input: BufferedInputStream, declaredBytes: Int, deadline: Long) {
        val piece = ByteArray(DRAIN_PIECE_BYTES)
        var left = declaredBytes.toLong()
        while (left > 0) {
            // An over-limit body is dropped under the same deadline as any other request, so a sender that drips it
            // just faster than the idle timeout cannot hold a reader past the deadline.
            if (System.nanoTime() > deadline) return
            val count = input.read(piece, 0, minOf(piece.size.toLong(), left).toInt())
            if (count < 0) return
            left -= count
        }
    }

    /** The request of [input], or why it is not one this receiver reads. */
    private fun read(input: BufferedInputStream, deadline: Long): ReadOutcome {
        val head = readHead(input, deadline) ?: return ReadOutcome.NotAReport
        val lines = head.split("\r\n")
        val start = lines.firstOrNull()?.split(' ') ?: return ReadOutcome.NotAReport
        if (start.size < 2) return ReadOutcome.NotAReport
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        // The length is checked before it is used to allocate, so that a client cannot ask this listener for an array
        // of the size it names.
        if (length < 0 || length > MAX_REPORT_BYTES) return ReadOutcome.TooLarge(length)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            if (System.nanoTime() > deadline) return ReadOutcome.NotAReport
            val count = input.read(bytes, read, length - read)
            if (count < 0) return ReadOutcome.NotAReport
            read += count
        }
        return ReadOutcome.Read(Request(start[0].uppercase(), start[1], headers, String(bytes, StandardCharsets.UTF_8)))
    }

    /** The request head of [input], which ends at the first empty line, null when it ends first. */
    private fun readHead(input: BufferedInputStream, deadline: Long): String? {
        val text = StringBuilder()
        var previous = -1
        while (text.length <= MAX_HEAD_BYTES) {
            if (System.nanoTime() > deadline) return null
            val byte = input.read()
            if (byte < 0) return null
            text.append(byte.toChar())
            if (previous == '\r'.code && byte == '\n'.code && text.endsWith("\r\n\r\n")) {
                return text.toString().removeSuffix("\r\n")
            }
            previous = byte
        }
        return null
    }

    private companion object {
        internal const val MAX_REPORT_BYTES = 8 * 1024 * 1024

        /** How much of a refused body is read at a time, so that nothing large is ever held. */
        const val DRAIN_PIECE_BYTES = 8 * 1024

        /** How many reports are read at once; past this a connection waits in the queue or is refused as busy. */
        const val MAX_CONCURRENT_REPORTS = 4

        /** How many connections wait for a reader before one is refused as busy. */
        const val MAX_QUEUED_REPORTS = 16

        const val MAX_HEAD_BYTES = 32 * 1024

        const val CLOSE_TIMEOUT_MILLIS = 2_000L
    }
}
