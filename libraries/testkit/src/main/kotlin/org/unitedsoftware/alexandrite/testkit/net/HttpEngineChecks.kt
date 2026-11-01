package org.unitedsoftware.alexandrite.testkit.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.sdk.net.HttpBodySource
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpRequestBody
import org.unitedsoftware.alexandrite.testkit.HttpEngineCheck
import org.unitedsoftware.alexandrite.testkit.store.expect
import org.unitedsoftware.alexandrite.testkit.store.expectEqual
import org.unitedsoftware.alexandrite.testkit.store.fail
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Waits for [future] on a thread of its own, failing when it takes longer than [timeout]. */
private suspend fun <T> awaited(future: CompletableFuture<T>, timeout: Duration = 10.seconds, what: String): T =
    runInterruptible(Dispatchers.IO) {
        try {
            future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fail("$what did not happen within $timeout.")
        }
    }

/** Runs [block], failing when it takes longer than [timeout]. */
private suspend fun <T> within(timeout: Duration, what: String, block: suspend () -> T): T {
    var result: Result<T>? = null
    withTimeoutOrNull(timeout) { result = runCatching { block() } } ?: fail("$what took longer than $timeout.")
    return result!!.getOrThrow()
}

/** [block]'s exception, failing when it throws none or a cancellation. */
private suspend fun thrown(what: String, block: suspend () -> Unit): Throwable {
    try {
        block()
    } catch (e: CancellationException) {
        fail("$what was cancelled rather than failed: $e", e)
    } catch (e: Throwable) {
        return e
    }
    fail("$what threw nothing.")
}

private val TEXT = "Grüße, engine!".encodeToByteArray()

private const val STREAM_CLOSES = 20

internal val REQUEST_CHECKS: List<HttpEngineCheck> = listOf(
    HttpEngineCheck("a request reaches the server as HTTP/1.1 with its method, target, headers and body") {
        server.handle { it.respond(200) }
        val request = request("POST", "/path/to?q=1&r=%20") {
            header("x-one", "a")
            header("X-Many", "b")
            header("x-many", "c")
            header("content-type", "text/plain; charset=utf-8")
            body(HttpRequestBody.of(TEXT))
        }

        open(request).readAll()

        val received = server.nextRequest()
        val line = "${received.method} ${received.target} ${received.version}"
        expectEqual("POST /path/to?q=1&r=%20 HTTP/1.1", line, "the request line")
        expectEqual(listOf("a"), received.all("x-one"), "the values of x-one")
        val many = received.all("x-many").flatMap { values -> values.split(",").map(String::trim) }
        expectEqual(listOf("b", "c"), many, "the values of x-many")
        expectEqual("text/plain; charset=utf-8", received.header("content-type"), "the content type")
        expectEqual("${TEXT.size}", received.header("content-length"), "the content length")
        expectEqual(false, received.chunked, "whether the body came in chunks")
        expectEqual(TEXT.toList(), received.body.toList(), "the body")
        expectEqual(null, received.header("accept-encoding"), "a content coding the engine asked for")
    },
    HttpEngineCheck("a body read in small parts arrives whole with its length") {
        server.handle { it.respond(200) }
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val opened = mutableListOf<Int>()
        val body = object : HttpRequestBody {
            override val length: Long = bytes.size.toLong()

            override fun open(): HttpBodySource = object : HttpBodySource {
                private var position = 0

                init {
                    opened += 1
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position == bytes.size) return -1
                    val count = minOf(length, 7, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
            }
        }

        open(request("PUT", "/upload") { body(body) }).readAll()

        val received = server.nextRequest()
        expectEqual("${bytes.size}", received.header("content-length"), "the content length")
        expect(received.body.contentEquals(bytes)) { "The body arrived changed: ${received.body.size} bytes." }
        expectEqual(1, opened.size, "the sources the engine opened")
    },
    HttpEngineCheck("a request without a body is sent with none") {
        server.handle { it.respond(200) }
        server.handle { it.respond(200) }

        open(request("GET")).readAll()
        open(request("POST")).readAll()

        val get = server.nextRequest()
        val post = server.nextRequest()
        expectEqual(0, get.body.size, "the bytes of a GET")
        expectEqual(false, get.chunked || post.chunked, "whether a request without a body came in chunks")
        expectEqual(0, post.body.size, "the bytes of a POST without a body")
    },
    HttpEngineCheck("a header value of visible ASCII, spaces and tabs arrives as given") {
        val value = "a" + (' '..'~').joinToString("") + "\tz"
        server.handle { it.respond(200) }

        open(request { header("x-printable", value) }).readAll()

        expectEqual(value, server.nextRequest().header("x-printable"), "the value of x-printable")
    },
    HttpEngineCheck("a URL the engine cannot use is refused with an IllegalArgumentException") {
        val error = thrown("Opening a URL with a space in its host") {
            engine.open(HttpEngineRequest.builder("GET", "http://exa mple.com/").build())
        }

        expect(error is IllegalArgumentException) { "Expected an IllegalArgumentException, was $error." }
    },
)

internal val RESPONSE_CHECKS: List<HttpEngineCheck> = listOf(
    HttpEngineCheck("the status, headers and body come back as the server sent them") {
        server.handle {
            it.respond(
                201,
                listOf("X-Multi" to "a", "x-multi" to "b", "Content-Type" to "text/plain"),
                TEXT,
            )
        }

        val response = open()
        val body = response.readAll()

        expectEqual(201, response.status, "the status")
        expectEqual(listOf("a", "b"), response.headers.all("X-MULTI"), "the values of x-multi")
        expectEqual("text/plain", response.headers["content-type"], "the content type")
        expectEqual(TEXT.toList(), body.toList(), "the body")
    },
    HttpEngineCheck("an error status is a response whose body is read") {
        server.handle { it.respond(404, body = "missing".toByteArray()) }

        val response = open()

        expectEqual(404, response.status, "the status")
        expectEqual("missing", response.readAll().decodeToString(), "the body")
    },
    HttpEngineCheck("a redirect is the response, never followed") {
        server.handle { it.respond(302, listOf("location" to "/elsewhere")) }
        server.handle { it.respond(200, body = "followed".toByteArray()) }

        val response = open(request(path = "/start"))
        response.readAll()

        expectEqual(302, response.status, "the status")
        expectEqual("/elsewhere", response.headers["location"], "the location")
        expectEqual("/start", server.nextRequest().target, "the target of the request")
        delay(200.milliseconds)
        expectEqual(emptyList(), server.pending().map { it.target }, "the requests sent after the redirect")
    },
    HttpEngineCheck("a body in a content coding arrives as sent, undecoded") {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(TEXT) } }.toByteArray()
        server.handle { it.respond(200, listOf("content-encoding" to "gzip"), zipped) }

        val response = open()

        expectEqual(zipped.toList(), response.readAll().toList(), "the body")
        expectEqual("gzip", response.headers["content-encoding"], "the content coding")
    },
    HttpEngineCheck("an empty body ends at once") {
        server.handle { it.respond(204) }

        val response = open()

        expectEqual(204, response.status, "the status")
        expectEqual(0, response.readAll().size, "the bytes of the body")
    },
    HttpEngineCheck("a long body arrives whole and in order") {
        val bytes = ByteArray(3_000_000) { (it * 31 % 253).toByte() }
        server.handle { it.respond(200, body = bytes) }

        val body = open().readAll()

        expect(body.contentEquals(bytes)) { "The body arrived changed: ${body.size} of ${bytes.size} bytes." }
    },
)

internal val STREAM_CHECKS: List<HttpEngineCheck> = listOf(
    HttpEngineCheck("open returns with the headers, and the body is read piece by piece as it is sent") {
        val headed = CompletableFuture<Unit>()
        val firstRead = CompletableFuture<Unit>()
        server.handle {
            it.head(200, listOf("content-type" to "text/event-stream"))
            headed.complete(Unit)
            it.chunk("data: first\n\n".toByteArray())
            firstRead.get(10, TimeUnit.SECONDS)
            it.chunk("data: second\n\n".toByteArray())
            it.end()
        }

        val response = within(10.seconds, "Opening a response whose body is not sent yet") { open() }
        awaited(headed, what = "Sending the head")
        val first = ByteArrayOutputStream()
        while (first.size() < "data: first\n\n".length) {
            val piece = within(10.seconds, "Reading the first event before the second is sent") { response.body.read() }
            first.write(piece ?: break)
        }
        firstRead.complete(Unit)
        val rest = response.readAll()

        expectEqual("data: first\n\n", first.toString(Charsets.UTF_8), "the first piece")
        expectEqual("data: second\n\n", rest.decodeToString(), "the rest")
    },
    HttpEngineCheck("closing a response before its body's end closes the connection") {
        val closed = CompletableFuture<Boolean>()
        server.handle {
            it.head(200)
            it.chunk("a".toByteArray())
            closed.complete(it.awaitClientClose())
        }

        val response = open()
        response.body.read()
        response.close()

        expect(awaited(closed, 15.seconds, "The server's wait")) { "The server still holds the connection." }
    },
    HttpEngineCheck("closing a response from another thread while its body streams ends the read, each time") {
        val piece = ByteArray(512) { 'x'.code.toByte() }
        repeat(STREAM_CLOSES) { round ->
            server.handle {
                it.head(200)
                val until = TimeSource.Monotonic.markNow() + 20.seconds
                while (until.hasNotPassedNow()) it.chunk(piece)
            }

            val response = open()
            coroutineScope {
                val streaming = CompletableDeferred<Unit>()
                val reading = async(Dispatchers.Default) {
                    runCatching {
                        while (response.body.read() != null) streaming.complete(Unit)
                    }
                }
                within(10.seconds, "The first piece of a streaming body") { streaming.await() }
                delay((round % 3).milliseconds)
                withContext(Dispatchers.IO) { response.close() }
                val result = within(10.seconds, "A read after the response was closed") { reading.await() }
                expect(result.isFailure) { "The body closed while it streamed ended as whole." }
            }
        }
        val bytes = ByteArray(1_000_000) { (it * 7 % 251).toByte() }
        server.handle { it.respond(200, body = bytes) }

        expect(open().readAll().contentEquals(bytes)) { "A body read after the closes arrived changed." }
    },
    HttpEngineCheck("closing a response ends a read that waits for the body") {
        val closed = CompletableFuture<Boolean>()
        server.handle {
            it.head(200)
            closed.complete(it.awaitClientClose())
        }

        val response = open()
        coroutineScope {
            val reading = async(Dispatchers.Default) { runCatching { response.body.read() } }
            delay(200.milliseconds)
            response.close()
            val result = within(10.seconds, "A read after the response was closed") { reading.await() }
            expect(result.isFailure || result.getOrNull() == null) { "The read went on with ${result.getOrNull()}." }
        }

        expect(awaited(closed, 15.seconds, "The server's wait")) { "The server still holds the connection." }
    },
    HttpEngineCheck("cancelling an open that waits for the response ends the exchange at once") {
        val closed = CompletableFuture<Boolean>()
        server.handle { closed.complete(it.awaitClientClose()) }

        coroutineScope {
            val opening = launch(Dispatchers.Default) { open() }
            runInterruptible(Dispatchers.IO) { server.nextRequest() }
            within(5.seconds, "Cancelling the open") {
                opening.cancel()
                opening.join()
            }
        }

        expect(awaited(closed, 15.seconds, "The server's wait")) { "The server still holds the connection." }
    },
    HttpEngineCheck("cancelling a read that waits for the body ends the exchange at once") {
        val closed = CompletableFuture<Boolean>()
        server.handle {
            it.head(200)
            closed.complete(it.awaitClientClose())
        }

        val response = open()
        try {
            coroutineScope {
                val reading = launch(Dispatchers.Default) { response.body.read() }
                delay(200.milliseconds)
                within(5.seconds, "Cancelling the read") {
                    reading.cancel()
                    reading.join()
                }
            }
            expect(awaited(closed, 15.seconds, "The server's wait")) { "The server still holds the connection." }
        } finally {
            response.close()
        }
    },
    HttpEngineCheck("a body that breaks off before its end fails the read") {
        server.handle {
            it.head(200)
            it.chunk("a".toByteArray())
            it.drop()
        }

        val response = open()
        val error = thrown("Reading a body cut off") { response.readAll() }

        expect(error !is AssertionError) { "The engine ended the body cut off as whole: $error" }
    },
)

internal val CONNECTION_CHECKS: List<HttpEngineCheck> = listOf(
    HttpEngineCheck("a refused connection fails the open") {
        val port = ServerSocket(0).use { it.localPort }
        val error = thrown("Opening a port nothing listens on") {
            engine.open(HttpEngineRequest.builder("GET", "http://127.0.0.1:$port/").build())
        }

        expect(error !is IllegalArgumentException) { "A refused connection was taken for a bad request: $error" }
    },
    HttpEngineCheck("a connect that does not complete fails within the connect timeout") {
        // A non-routable address, which either drops the connect or fails it at once where there is no route
        val request = HttpEngineRequest.builder("GET", "http://10.255.255.1:81/").connectTimeout(1.seconds).build()
        val started = TimeSource.Monotonic.markNow()

        thrown("Connecting to an address that never answers") {
            within(6.seconds, "Connecting with a connect timeout of 1s") { engine.open(request) }
        }

        expect(started.elapsedNow() < 6.seconds) { "The connect took ${started.elapsedNow()}." }
    },
    HttpEngineCheck("a 408 or a 503 with retry-after 0 is the response, the request sent once") {
        val requests = listOf(request("POST") { body(HttpRequestBody.of(TEXT)) }, request("POST"), request("GET"))
        for (status in listOf(408, 503)) {
            for (sent in requests) {
                server.handle { it.respond(status, listOf("retry-after" to "0")) }

                val response = open(sent)
                response.readAll()

                val what = "${sent.method} ${if (sent.body == null) "without" else "with"} a body answered $status"
                expectEqual(status, response.status, "the status of a $what")
                expectEqual("0", response.headers["retry-after"], "the retry-after of a $what")
                server.nextRequest()
                delay(100.milliseconds)
                expectEqual(emptyList(), server.pending().map { it.method }, "the requests sent again for a $what")
            }
        }
    },
    HttpEngineCheck("a POST whose connection drops after the server read it fails, sent once") {
        server.handle { it.respond(200) }
        server.handle { }
        open().readAll()
        server.nextRequest()

        val error = thrown("Sending a POST that the server reads and drops") {
            open(request("POST") { body(HttpRequestBody.of(TEXT)) }).readAll()
        }

        expect(error !is AssertionError) { "The engine took the dropped POST for an answer: $error" }
        expectEqual("POST", server.nextRequest().method, "the method of the dropped request")
        delay(200.milliseconds)
        expectEqual(emptyList(), server.pending().map { it.method }, "the requests sent again after the drop")
    },
    HttpEngineCheck("a GET on a kept connection that the server closed gets its response") {
        val closed = CompletableFuture<Unit>()
        server.handle {
            it.respond(200, body = "first".toByteArray())
            it.close()
            closed.complete(Unit)
        }
        server.handle { it.respond(200, body = "second".toByteArray()) }
        open().readAll()
        awaited(closed, what = "Closing the kept connection")
        delay(100.milliseconds)

        val body = within(10.seconds, "A GET after the server closed its kept connection") { open().readAll() }

        expectEqual("second", body.decodeToString(), "the body")
    },
    HttpEngineCheck("requests one after another each get their response") {
        repeat(5) { index -> server.handle { it.respond(200, body = "r$index".toByteArray()) } }

        val bodies = List(5) { open().readAll().decodeToString() }

        expectEqual(List(5) { "r$it" }, bodies, "the bodies")
    },
)
