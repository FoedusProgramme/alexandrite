package org.unitedsoftware.alexandrite.http.common

import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** An HTTP/1.1 server on the loopback interface that answers each connection with the next queued handler. */
internal class TestServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val handlers = LinkedBlockingQueue<(Exchange) -> Unit>()
    val requests = LinkedBlockingQueue<TestRequest>()
    val failures = LinkedBlockingQueue<Throwable>()

    val uri: URI = URI("http://127.0.0.1:${server.localPort}")

    init {
        Thread.ofVirtual().start {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                Thread.ofVirtual().start { serve(socket) }
            }
        }
    }

    fun handle(handler: (Exchange) -> Unit) {
        handlers += handler
    }

    fun nextRequest(): TestRequest = requests.poll(5, TimeUnit.SECONDS) ?: error("No request reached the server.")

    override fun close() {
        server.close()
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                val request = readRequest(input) ?: return
                requests += request
                val handler = handlers.poll() ?: { it.status(500).end() }
                handler(Exchange(socket, input))
            } catch (e: Exception) {
                failures += e
            }
        }
    }

    private fun readRequest(input: InputStream): TestRequest? {
        val head = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
        val requestLine = head.firstOrNull() ?: return null
        val headers = head.drop(1).associate { line ->
            line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val bytes = input.readNBytes(length)
        val (method, path) = requestLine.split(' ')
        return TestRequest(method, path, headers, bytes)
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            when (val byte = input.read()) {
                -1 -> return if (line.size() == 0) null else line.toString(Charsets.UTF_8)
                '\n'.code -> return line.toString(Charsets.UTF_8).removeSuffix("\r")
                else -> line.write(byte)
            }
        }
    }
}

internal class TestRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val bytes: ByteArray,
) {
    val body: String get() = bytes.toString(Charsets.UTF_8)
}

/** One response, written as a chunked body. */
internal class Exchange(private val socket: Socket, private val input: InputStream) {
    private val output = socket.getOutputStream()
    private var status = 200
    private val headers = mutableListOf("content-type" to "text/event-stream")
    private var headed = false

    fun status(status: Int): Exchange = apply { this.status = status }

    /** Sets the header [name] to [value], or leaves it out when [value] is null. */
    fun header(name: String, value: String?): Exchange = apply {
        headers.removeAll { it.first == name }
        value?.let { headers += name to it }
    }

    fun chunk(text: String): Exchange = apply {
        head()
        val bytes = text.toByteArray()
        output.write("${bytes.size.toString(16)}\r\n".toByteArray())
        output.write(bytes)
        output.write("\r\n".toByteArray())
        output.flush()
    }

    fun pause(duration: Duration): Exchange = apply {
        head()
        Thread.sleep(duration.inWholeMilliseconds)
    }

    fun end() {
        head()
        output.write("0\r\n\r\n".toByteArray())
        output.flush()
    }

    /** Closes the connection without ending the body. */
    fun drop() {
        head()
        socket.shutdownOutput()
    }

    /** Whether the client closed the connection within [timeout]. */
    fun awaitClientClose(timeout: Duration = 5.seconds): Boolean {
        socket.soTimeout = timeout.inWholeMilliseconds.toInt()
        return try {
            input.read() == -1
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: SocketException) {
            true
        }
    }

    private fun head() {
        if (headed) return
        headed = true
        val reason = if (status == 200) "OK" else "Status"
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("transfer-encoding: chunked\r\nconnection: close\r\n\r\n")
        }
        output.write(text.toByteArray())
        output.flush()
    }
}

/** The engine of every test's transport, which outlives them. */
internal val ENGINE = JdkHttpEngine()

internal val SHORT = HttpTimeouts(connect = 2.seconds, firstByte = 2.seconds, idle = 2.seconds)

internal val QUICK = HttpTimeouts(connect = 2.seconds, firstByte = 300.milliseconds, idle = 300.milliseconds)
