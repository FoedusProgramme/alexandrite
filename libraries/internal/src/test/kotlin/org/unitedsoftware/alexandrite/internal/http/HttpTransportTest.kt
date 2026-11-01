package org.unitedsoftware.alexandrite.internal.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.unitedsoftware.alexandrite.sdk.net.HttpHeaders
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpTransportTest {
    private val server = TestServer()
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val transport = HttpTransport(SHORT, ENGINE, Clock.fixed(now, ZoneOffset.UTC))

    @AfterTest
    fun close() {
        transport.close()
        server.close()
    }

    private fun post(path: String = "/chat", key: String = "sk-test-secret") = HttpCall(
        "POST",
        server.uri.resolve(path),
        mapOf("authorization" to "Bearer $key", "content-type" to "application/json"),
        """{"stream":true}""",
        listOf(key),
    )

    private fun bot(firstByte: Duration? = null) =
        HttpCall("POST", server.uri.resolve("/bot$TOKEN/getMe"), secrets = listOf(TOKEN), firstByte = firstByte)

    private fun assertMasked(error: Throwable) {
        for (cause in generateSequence(error) { it.cause }) assertFalse(TOKEN in cause.toString(), cause.toString())
        assertFalse(TOKEN in (error as? HttpException)?.body.orEmpty(), error.toString())
    }

    @Test
    fun `events stream in order, the last one also without a blank line`() = runBlocking {
        server.handle { it.chunk("data: a\n\n").chunk(": keep-alive\n\ndata: b").chunk("\n\nevent: x\ndata: c").end() }

        val events = transport.open(post()) { response -> response.events().toList() }

        assertEquals(listOf("a", "b", "c"), events.map { it.data })
        assertEquals("x", events.last().type)
        val request = server.nextRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat", request.path)
        assertEquals("Bearer sk-test-secret", request.headers["authorization"])
        assertEquals("""{"stream":true}""", request.body)
    }

    @Test
    fun `a status error carries its kind, retry delay, request id, headers and a masked body`() = runBlocking {
        server.handle {
            it.status(429).header("retry-after", "7").header("x-request-id", "req-1")
                .chunk("""{"error":{"message":"slow down, key sk-test-secret"}}""").end()
        }

        val error = assertFailsWith<HttpException> { transport.open(post()) { } }

        assertEquals(HttpFailureKind.RATE_LIMITED, error.kind)
        assertEquals(429, error.status)
        assertEquals(7.seconds, error.retryAfter)
        assertEquals("req-1", error.requestId)
        assertEquals("7", error.headers?.get("retry-after"))
        assertFalse(error.afterResponse)
        assertFalse("sk-test-secret" in error.message.orEmpty(), error.message)
        assertFalse("sk-test-secret" in error.body.orEmpty(), error.body)
        assertTrue("slow down" in error.body.orEmpty())
    }

    @Test
    fun `a retry date counts from now`() = runBlocking {
        server.handle { it.status(503).header("retry-after", "Thu, 08 Oct 2026 12:00:30 GMT").end() }

        val error = assertFailsWith<HttpException> { transport.open(post()) { } }

        assertEquals(HttpFailureKind.OVERLOADED, error.kind)
        assertEquals(30.seconds, error.retryAfter)
        assertEquals(null, error.body)
    }

    @Test
    fun `cancelling the reader closes the connection at once`() = runBlocking {
        val closed = CompletableDeferred<Boolean>()
        server.handle { exchange ->
            exchange.chunk("data: a\n\n")
            closed.complete(exchange.awaitClientClose(3.seconds))
        }
        val first = CompletableDeferred<Unit>()
        val reader = launch(Dispatchers.Default) {
            transport.open(post()) { response -> response.events().collect { first.complete(Unit) } }
        }
        first.await()

        reader.cancelAndJoin()

        assertTrue(withContext(Dispatchers.IO) { closed.await() }, "The server still holds the connection.")
    }

    @Test
    fun `closing a transport ends its calls in flight and leaves the engine to the others`() = runBlocking {
        val closed = CompletableDeferred<Boolean>()
        server.handle { closed.complete(it.awaitClientClose(3.seconds)) }
        server.handle { it.chunk("ok").end() }
        val own = HttpTransport(SHORT, ENGINE)
        val call = async(Dispatchers.Default) { runCatching { own.send(post()) } }
        withContext(Dispatchers.IO) { server.nextRequest() }

        own.close()

        val error = call.await().exceptionOrNull()
        assertEquals(HttpFailureKind.CONNECTION, (error as? HttpException)?.kind, "$error")
        assertTrue(withContext(Dispatchers.IO) { closed.await() }, "The server still holds the connection.")
        assertEquals("ok", transport.send(HttpCall("GET", server.uri)))
    }

    @Test
    fun `a body that pauses too long times out after the response`() = runBlocking {
        val quick = HttpTransport(QUICK, ENGINE)
        server.handle { it.chunk("data: a\n\n").pause(1.seconds).end() }
        val seen = mutableListOf<String>()

        val error = quick.use {
            assertFailsWith<HttpException> {
                it.open(post()) { response -> response.events().collect { event -> seen += event.data } }
            }
        }

        assertEquals(listOf("a"), seen)
        assertEquals(HttpFailureKind.TIMEOUT, error.kind)
        assertTrue(error.afterResponse)
    }

    @Test
    fun `a body cut off is a connection failure after the response`() = runBlocking {
        server.handle { it.chunk("data: a\n\n").drop() }
        val seen = mutableListOf<String>()

        val error = assertFailsWith<HttpException> {
            transport.open(post()) { response -> response.events().collect { event -> seen += event.data } }
        }

        assertEquals(listOf("a"), seen)
        assertEquals(HttpFailureKind.CONNECTION, error.kind)
        assertTrue(error.afterResponse)
        assertEquals(200, error.status)
    }

    @Test
    fun `a call prints neither credentials nor query`() {
        val call = HttpCall("GET", URI("https://user:pw@example.com:8443/v1/models?key=secret#x"))

        assertEquals("GET https://example.com:8443/v1/models", call.toString())
    }

    @Test
    fun `a call prints the secrets of its path masked`() {
        assertEquals("POST ${server.uri}/bot***/getMe", bot().toString())
        assertEquals("POST ${server.uri}/bot$TOKEN/getMe", HttpCall("POST", bot().uri).toString())
    }

    @Test
    fun `a secret in the path appears in no failure of a status`() = runBlocking {
        server.handle { it.status(401).chunk("""{"description":"Unauthorized: /bot$TOKEN/getMe"}""").end() }

        val error = assertFailsWith<HttpException> { transport.send(bot()) }

        assertEquals(HttpFailureKind.AUTHENTICATION, error.kind)
        assertTrue("Unauthorized" in error.body.orEmpty(), error.body)
        assertMasked(error)
    }

    @Test
    fun `a secret in the path appears in no failure of a status whose body cannot be read`() = runBlocking {
        server.handle { it.status(500).chunk("partial").drop() }

        val error = assertFailsWith<HttpException> { transport.send(bot()) }

        assertEquals(500, error.status)
        assertMasked(error)
    }

    @Test
    fun `a secret in the path appears in no timeout`() = runBlocking {
        val quick = HttpTransport(QUICK, ENGINE)
        server.handle { Thread.sleep(1_000) }
        server.handle { it.pause(1.seconds).end() }
        server.handle { it.chunk("data: a\n\n").pause(1.seconds).end() }

        val errors = quick.use { client ->
            List(3) { assertFailsWith<HttpException> { client.open(bot()) { response -> response.events().toList() } } }
        }

        assertEquals(List(3) { HttpFailureKind.TIMEOUT }, errors.map { it.kind })
        assertEquals(listOf(false, false, true), errors.map { it.afterResponse })
        errors.forEach(::assertMasked)
    }

    @Test
    fun `a refused or broken connection fails retryably and shows no secret of the path`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        server.handle { it.chunk("data: a\n\n").drop() }

        val refused = assertFailsWith<HttpException> {
            transport.send(HttpCall("POST", URI("http://127.0.0.1:$port/bot$TOKEN/getMe"), secrets = listOf(TOKEN)))
        }
        val broken = assertFailsWith<HttpException> { transport.open(bot()) { it.events().toList() } }

        assertEquals(HttpFailureKind.CONNECTION to true, refused.kind to refused.kind.retryable)
        assertEquals(listOf(false, true), listOf(refused, broken).map { it.afterResponse })
        assertMasked(refused)
        assertMasked(broken)
    }

    @Test
    fun `a secret in the path appears in no failure of a closed transport or of a URI that is no HTTP URI`() =
        runBlocking {
            transport.close()

            val closed = assertFailsWith<HttpException> { transport.send(bot()) }
            val notHttp = assertFailsWith<IllegalArgumentException> {
                HttpCall("GET", URI("ftp://example.com/bot$TOKEN/getMe"), secrets = listOf(TOKEN))
            }

            assertEquals(HttpFailureKind.CONNECTION, closed.kind)
            assertMasked(closed)
            assertMasked(notHttp)
        }

    @Test
    fun `a header value with a control character fails the call as an HTTP failure naming only the header`() =
        runBlocking {
            val calls = listOf(
                HttpCall("GET", server.uri, mapOf("authorization" to "Bearer $TOKEN\r")),
                HttpCall("GET", server.uri, mapOf("x-key" to "$TOKEN\u200b")),
            )

            val errors = calls.map { call -> assertFailsWith<HttpException> { transport.send(call) } }

            assertEquals(List(2) { HttpFailureKind.PROTOCOL }, errors.map { it.kind })
            assertTrue("'authorization'" in errors[0].message.orEmpty(), errors[0].message)
            assertTrue("'x-key'" in errors[1].message.orEmpty(), errors[1].message)
            errors.forEach(::assertMasked)
            assertTrue(server.requests.isEmpty())
            assertFalse(isHeaderValue("a\u0085b") || isHeaderValue("\u0100"))
            assertTrue(isHeaderValue("Bearer \tk\u00e9y"))
        }

    @Test
    fun `a call masks its secrets longest first and leaves those shorter than four characters`() {
        val call = HttpCall("GET", server.uri, secrets = listOf("abc", "token", "wxyz", "token-long", ""))

        assertEquals("abc *** *** *** end", call.mask("abc token-long wxyz token end"))
    }

    @Test
    fun `send keeps the bytes up to its limit, also inside a piece`() = runBlocking {
        repeat(2) { server.handle { it.chunk("abcdef").chunk("ghij").end() } }

        val texts = listOf(4, 8).map { limit -> transport.send(HttpCall("GET", server.uri), limit) }

        assertEquals(listOf("abcd", "abcdefgh"), texts)
    }

    @Test
    fun `an error keeps its body up to the limit and quotes its start, both masked`() = runBlocking {
        val key = "sk-test-secret"
        server.handle { it.status(500).chunk("y".repeat(490) + key + "x".repeat(HttpTransport.ERROR_BODY_LIMIT)).end() }

        val error = assertFailsWith<HttpException> { transport.send(post(key = key)) }

        val body = error.body.orEmpty()
        assertEquals(HttpTransport.ERROR_BODY_LIMIT - key.length + 3, body.length)
        assertTrue(body.startsWith("y".repeat(490) + "***x"), body.take(600))
        assertEquals("${post(key = key)} got HTTP 500: ${"y".repeat(490)}***${"x".repeat(7)}", error.message)
    }

    @Test
    fun `a secret that the error body's limit cuts is masked whole`() = runBlocking {
        val key = "sk-test-secret"
        val start = "é".repeat(4) + "y".repeat(HttpTransport.ERROR_BODY_LIMIT - 13)
        server.handle { it.status(500).chunk(start + key + "x".repeat(100)).end() }

        val error = assertFailsWith<HttpException> { transport.send(post(key = key)) }

        assertEquals(start + "***", error.body)
    }

    @Test
    fun `a media type is read in lower case without its parameters, and a blank one is none`() = runBlocking {
        val types = listOf("Text/Event-Stream; charset=utf-8", " Application/JSON ", ";charset=utf-8", "", null)
        types.forEach { type -> server.handle { it.header("content-type", type).end() } }

        val read = types.map { transport.open(HttpCall("GET", server.uri)) { it.mediaType } }

        assertEquals(listOf("text/event-stream", "application/json", null, null, null), read)
    }

    @Test
    fun `an event longer than the parser's limit breaks the stream off`() = runBlocking {
        val piece = "x".repeat(1024 * 1024)
        server.handle { exchange ->
            exchange.chunk("data: ")
            repeat(SseParser.DEFAULT_LIMIT / piece.length + 1) { exchange.chunk(piece) }
            exchange.end()
        }

        val error = assertFailsWith<HttpException> { transport.open(bot()) { it.events().toList() } }

        assertEquals(HttpFailureKind.PROTOCOL, error.kind)
        assertEquals(200 to true, error.status to error.afterResponse)
        assertTrue("longer than" in error.message.orEmpty(), error.message)
        assertMasked(error)
    }

    @Test
    fun `a request the engine refuses fails as an HTTP failure that quotes neither the request nor its cause`() =
        runBlocking {
            val calls = listOf(
                HttpCall("GET", URI("http:///bot$TOKEN/getMe"), secrets = listOf(TOKEN)),
                HttpCall("GET", URI("http://user:$TOKEN@/getMe?key=$TOKEN")),
                HttpCall("GET", server.uri, mapOf("host" to TOKEN)),
            )

            for (error in calls.map { call -> assertFailsWith<HttpException> { transport.send(call) } }) {
                assertEquals(HttpFailureKind.PROTOCOL, error.kind)
                assertEquals(null, error.cause)
                assertMasked(error)
            }
        }

    @Test
    fun `a call's first-byte timeout replaces the transport's`() = runBlocking {
        server.handle { Thread.sleep(1_000) }
        server.handle { exchange ->
            Thread.sleep(600)
            exchange.chunk("data: ok\n\n").end()
        }

        val error = assertFailsWith<HttpException> { transport.send(bot(firstByte = 300.milliseconds)) }
        val text = HttpTransport(QUICK, ENGINE).use { it.send(bot(firstByte = 2.seconds)) }

        assertEquals(HttpFailureKind.TIMEOUT, error.kind)
        assertTrue("within 300ms" in error.message.orEmpty(), error.message)
        assertEquals("data: ok\n\n", text)
    }

    @Test
    fun `a call's first-byte timeout also bounds the first piece of its body`() = runBlocking {
        val quick = HttpTransport(QUICK, ENGINE)
        repeat(2) { server.handle { it.pause(600.milliseconds).chunk("data: late\n\n").end() } }

        val (late, error) = quick.use { client ->
            client.open(bot(firstByte = 2.seconds)) { it.events().toList() } to
                assertFailsWith<HttpException> { client.open(bot()) { it.events().toList() } }
        }

        assertEquals(listOf("late"), late.map { it.data })
        assertEquals(HttpFailureKind.TIMEOUT, error.kind)
        assertFalse(error.afterResponse)
    }

    @Test
    fun `a call that read its whole body leaves its connection to the next call`() {
        val ports = CopyOnWriteArrayList<Int>()
        val keepAlive = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        keepAlive.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            ports += exchange.remoteAddress.port
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }
        keepAlive.start()
        try {
            val call = HttpCall("POST", URI("http://127.0.0.1:${keepAlive.address.port}/send"), body = "{}")
            // Unconfined resumes the reader inside the client's end of the body, before the client ends the exchange.
            runBlocking {
                withContext(Dispatchers.Unconfined) { repeat(3) { assertEquals("ok", transport.send(call)) } }
            }

            assertEquals(1, ports.distinct().size, "The calls came over the connections of the ports $ports.")
        } finally {
            keepAlive.stop(0)
        }
    }

    @Test
    fun `statuses map to kinds`() {
        val kinds = listOf(400, 401, 402, 403, 404, 408, 409, 413, 422, 429, 500, 502, 503, 504, 529, 302)
            .associateWith { HttpFailureKind.of(it) }

        assertEquals(
            mapOf(
                400 to HttpFailureKind.INVALID_REQUEST,
                401 to HttpFailureKind.AUTHENTICATION,
                402 to HttpFailureKind.QUOTA_EXHAUSTED,
                403 to HttpFailureKind.PERMISSION_DENIED,
                404 to HttpFailureKind.NOT_FOUND,
                408 to HttpFailureKind.TIMEOUT,
                409 to HttpFailureKind.SERVER_ERROR,
                413 to HttpFailureKind.REQUEST_TOO_LARGE,
                422 to HttpFailureKind.INVALID_REQUEST,
                429 to HttpFailureKind.RATE_LIMITED,
                500 to HttpFailureKind.SERVER_ERROR,
                502 to HttpFailureKind.SERVER_ERROR,
                503 to HttpFailureKind.OVERLOADED,
                504 to HttpFailureKind.TIMEOUT,
                529 to HttpFailureKind.OVERLOADED,
                302 to HttpFailureKind.PROTOCOL,
            ),
            kinds,
        )
    }

    @Test
    fun `retry delays come from milliseconds, seconds or a date`() {
        fun headers(vararg pairs: Pair<String, String>) = HttpHeaders.of(*pairs)

        assertEquals(1.5.seconds, retryAfter(headers("retry-after-ms" to "1500", "retry-after" to "9"), now))
        assertEquals(2.seconds, retryAfter(headers("retry-after" to "2"), now))
        assertEquals(0.seconds, retryAfter(headers("retry-after" to "Thu, 08 Oct 2026 11:00:00 GMT"), now))
        assertEquals(null, retryAfter(headers("retry-after" to "soon"), now))
        assertEquals(null, retryAfter(headers(), now))
        assertEquals("abc", requestId(headers("request-id" to "abc")))
    }

    private companion object {
        const val TOKEN = "123456:AAH-path-token_x"
    }
}
