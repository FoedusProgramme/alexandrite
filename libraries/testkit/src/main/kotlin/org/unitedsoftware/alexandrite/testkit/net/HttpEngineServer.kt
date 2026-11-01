package org.unitedsoftware.alexandrite.testkit.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An HTTP/1.1 server on the loopback interface for the engine contract, which answers each request with the next
 * queued handler and keeps a connection for the next request when the handler answered whole.
 */
internal class HttpEngineServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val handlers = LinkedBlockingQueue<(ServerExchange) -> Unit>()
    private val received = LinkedBlockingQueue<ServerRequest>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    /** `http://127.0.0.1:<port>`, without a trailing slash. */
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    init {
        daemon("http-engine-server-${server.localPort}") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                sockets += socket
                daemon("http-engine-server-connection") { serve(socket) }
            }
        }
    }

    /** Answers the next request that no earlier handler took with [handler], run on the connection's thread. */
    fun handle(handler: (ServerExchange) -> Unit) {
        handlers += handler
    }

    /** The next request the server read, waiting for it up to [timeout]. */
    fun nextRequest(timeout: Duration = 10.seconds): ServerRequest =
        received.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("No request reached the server within $timeout.")

    /** The requests read and not yet taken by [nextRequest]. */
    fun pending(): List<ServerRequest> = received.toList()

    override fun close() {
        server.close()
        sockets.forEach { it.close() }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = BufferedInputStream(socket.getInputStream())
                while (true) {
                    val request = readRequest(input) ?: return
                    received += request
                    val exchange = ServerExchange(socket, input)
                    (handlers.poll() ?: { it.respond(500, body = "no handler".toByteArray()) })(exchange)
                    if (!exchange.whole) return
                }
            }
        } catch (e: IOException) {
            // A client that closes its connection ends the exchange
        } finally {
            sockets -= socket
        }
    }

    private fun readRequest(input: InputStream): ServerRequest? {
        val line = readLine(input) ?: return null
        val headers = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.map { field ->
            field.substringBefore(':').trim().lowercase() to field.substringAfter(':').trim()
        }.toList()
        val chunked = headers.any { (name, value) -> name == "transfer-encoding" && "chunked" in value.lowercase() }
        val body = if (chunked) {
            readChunked(input)
        } else {
            val length = headers.firstOrNull { it.first == "content-length" }?.second?.toLong() ?: 0
            input.readNBytes(length.toInt())
        }
        val parts = line.split(' ')
        return ServerRequest(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, headers, body, chunked)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toInt(16) ?: throw IOException("Cut off.")
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) continue
                return body.toByteArray()
            }
            body.write(input.readNBytes(size))
            readLine(input)
        }
    }
}

/** A request as the server read it, its header names in lower case. */
internal class ServerRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    /** Whether the body came in chunks. */
    val chunked: Boolean,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first == name }?.second

    fun all(name: String): List<String> = headers.filter { it.first == name }.map { it.second }
}

/** The answer to one request. */
internal class ServerExchange(private val socket: Socket, private val input: InputStream) {
    private val output: OutputStream = socket.getOutputStream()

    /** Whether the response went out whole, so the connection may take another request. */
    var whole: Boolean = false
        private set

    fun respond(status: Int, headers: List<Pair<String, String>> = emptyList(), body: ByteArray = ByteArray(0)) {
        write(statusLines(status, headers + ("content-length" to "${body.size}")))
        output.write(body)
        output.flush()
        whole = true
    }

    /** Sends the status and [headers] of a chunked body, whose pieces [chunk] sends. */
    fun head(status: Int, headers: List<Pair<String, String>> = emptyList()) {
        write(statusLines(status, headers + ("transfer-encoding" to "chunked")))
    }

    fun chunk(bytes: ByteArray) {
        write("${bytes.size.toString(16)}\r\n".toByteArray() + bytes + "\r\n".toByteArray())
    }

    fun end() {
        write("0\r\n\r\n".toByteArray())
        whole = true
    }

    /** Closes the connection's output without ending the body. */
    fun drop() {
        socket.shutdownOutput()
    }

    fun close() {
        socket.close()
    }

    /** Whether the client closed the connection within [timeout], reading and dropping what it still sends. */
    fun awaitClientClose(timeout: Duration = 10.seconds): Boolean {
        socket.soTimeout = timeout.inWholeMilliseconds.toInt()
        return try {
            while (input.read() != -1) continue
            true
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: SocketException) {
            true
        }
    }

    private fun statusLines(status: Int, headers: List<Pair<String, String>>): ByteArray = buildString {
        append("HTTP/1.1 $status Status\r\n")
        headers.forEach { (name, value) -> append("$name: $value\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    private fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }
}

private fun readLine(input: InputStream): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        when (val byte = input.read()) {
            -1 -> return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1)
            '\n'.code -> return line.toString(Charsets.ISO_8859_1).removeSuffix("\r")
            else -> line.write(byte)
        }
    }
}

private fun daemon(name: String, task: () -> Unit) {
    Thread(task, name).apply { isDaemon = true }.start()
}
