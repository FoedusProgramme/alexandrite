package org.unitedsoftware.alexandrite.channel.qqbot.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A clock a test moves by hand. */
internal class MutableClock(var at: Instant) : Clock() {
    override fun instant(): Instant = at

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/** One request a fake platform read. */
internal class PlatformRequest(
    val method: String,
    val path: String,
    val query: String,
    val body: String,
    val headers: Map<String, String>,
)

/** What a fake platform answers to one path. */
internal class PlatformAnswer(val status: Int = 200, val body: String = "{}")

/** An HTTP server that stands for the open platform's API and keeps what it read. */
internal class FakeApiServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = CopyOnWriteArrayList<Pair<String, (PlatformRequest) -> PlatformAnswer>>()
    private val requests = CopyOnWriteArrayList<PlatformRequest>()

    val base: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> answer(exchange) }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    /** Answers calls to [path] with [answer], which the last route added for a path wins. */
    fun on(path: String, answer: (PlatformRequest) -> PlatformAnswer) {
        routes += path to answer
    }

    /** Answers every call to [path] with [body] as JSON. */
    fun on(path: String, body: String, status: Int = 200) = on(path) { PlatformAnswer(status, body) }

    /** Every request read so far, oldest first. */
    fun requests(): List<PlatformRequest> = requests.toList()

    /** The request calls of [path] read so far. */
    fun calls(path: String): List<PlatformRequest> = requests.filter { it.path == path }

    /** The next request that [predicate] takes, waiting for it at most [timeoutMillis]. */
    fun awaitRequest(path: String, timeoutMillis: Long = 5_000): PlatformRequest? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            calls(path).lastOrNull()?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    private fun answer(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        val request = PlatformRequest(
            exchange.requestMethod,
            exchange.requestURI.rawPath,
            exchange.requestURI.rawQuery ?: "",
            body,
            exchange.requestHeaders.entries.associate { it.key to it.value.first() },
        )
        requests += request
        val answer = routes.lastOrNull { it.first == request.path }?.second?.invoke(request)
            ?: PlatformAnswer(404, """{"err_code":404,"message":"no route"}""")
        val bytes = answer.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(answer.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)
}

/** A gateway that stands for the platform's WebSocket: it says hello, takes an identify and pushes what it is told. */
internal class FakeGatewayServer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
    private val started = CountDownLatch(1)
    private val connected = CountDownLatch(1)
    private val received = LinkedBlockingQueue<String>()
    private val clients = CopyOnWriteArrayList<WebSocket>()

    /** The address the channel connects to. */
    val url: String get() = "ws://127.0.0.1:$port"

    /** Starts the server and returns once it listens. */
    fun startAndAwait(): FakeGatewayServer {
        start()
        check(started.await(5, TimeUnit.SECONDS)) { "the fake gateway did not start" }
        return this
    }

    /** Whether a client connected within [timeoutMillis]. */
    fun awaitConnection(timeoutMillis: Long = 5_000): Boolean = connected.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** The next frame the client sent that [predicate] takes, waiting for it at most [timeoutMillis]. */
    fun awaitFrame(predicate: (String) -> Boolean = { true }, timeoutMillis: Long = 5_000): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val kept = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            val frame = received.poll(20, TimeUnit.MILLISECONDS) ?: continue
            kept += frame
            if (predicate(frame)) return frame
        }
        return null
    }

    /** Pushes [frame] to every connected client. */
    fun push(frame: String) {
        clients.forEach { it.send(frame) }
    }

    /** Closes the most recently opened connection with [code] and [reason], as the platform itself would. */
    fun closeConnection(code: Int, reason: String = "") {
        clients.lastOrNull()?.close(code, reason)
    }

    override fun onStart() {
        started.countDown()
    }

    override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
        clients += connection
        connected.countDown()
        connection.send("""{"op":10,"d":{"heartbeat_interval":60000}}""")
    }

    override fun onMessage(connection: WebSocket, message: String) {
        received += message
        if (message.contains("\"op\":2")) {
            connection.send(READY)
        }
    }

    override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

    override fun onError(connection: WebSocket?, ex: Exception) = Unit

    private companion object {
        const val READY = """{"op":0,"s":1,"t":"READY","d":{"version":1,"session_id":"s1",""" +
            """"user":{"id":"bot1","username":"Bot","bot":true},"shard":[0,0]}}"""
    }
}
