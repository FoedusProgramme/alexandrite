package org.unitedsoftware.alexandrite.internal.sandbox

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.portable.Threads
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** Resolves a host name to its addresses. */
public fun interface HostResolver {
    /** Throws an [IOException] when it cannot. */
    public fun resolve(host: String): List<InetAddress>
}

public val SYSTEM_RESOLVER: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() }

/**
 * An HTTP proxy outside every sandbox that serves `CONNECT` tunnels and plain HTTP requests: each call gets a listener
 * of its own on the loopback, which admits only its hosts.
 */
public class NetworkProxy(private val resolver: HostResolver) {
    /** Listens for a call that may reach [allowed]. */
    public fun open(allowed: List<HostPattern>): ProxyLease = ProxyLease(allowed, resolver)
}

/** The listener of one call, until [close]. */
public class ProxyLease internal constructor(
    private val allowed: List<HostPattern>,
    private val resolver: HostResolver,
) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val refusals = CopyOnWriteArrayList<String>()

    @Volatile
    private var closed = false

    public val port: Int = server.localPort

    /** The hosts it refused, each once, in order. */
    public val refused: List<String> get() = refusals.distinct()

    private val acceptor = Threads.start("sandbox proxy $port") {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            Threads.start("sandbox proxy $port connection") { serve(client) }
        }
    }

    /** Stops listening, which holds once it returns. */
    override fun close() {
        closed = true
        server.close()
        synchronized(sockets) { sockets.toList() }.forEach { it.closeQuietly() }
        // The port keeps accepting until the thread blocked in accept has left it.
        acceptor.join(CLOSE_WAIT_MILLIS)
    }

    private fun serve(client: Socket) {
        tracked(client)
        var upstream: Socket? = null
        try {
            client.soTimeout = HEAD_TIMEOUT_MILLIS
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return reply(client, 400, "The request has no complete head.")
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size != 3) return reply(client, 400, "The request line is malformed.")
            val (method, target, version) = parts
            val request = if (method.equals("CONNECT", ignoreCase = true)) {
                authority(target)?.let { Target(it.first, it.second, null) }
            } else {
                absolute(target)
            } ?: return reply(client, 400, "The proxy takes CONNECT host:port, or an absolute http:// URL.")
            val addresses = when (val decision = decide(request.host, request.port)) {
                is Decision.Refused -> {
                    val named = named(request.host, request.port)
                    refusals += named
                    logger.info("The sandbox proxy refused {}: {}", named, decision.why)
                    return reply(client, 403, "Alexandrite's sandbox proxy refused $named: ${decision.why}.")
                }

                is Decision.Allowed -> decision.addresses
            }
            val connected = connect(addresses, request.port)
                ?: return reply(client, 502, "The proxy could not connect to ${named(request.host, request.port)}.")
            upstream = connected
            tracked(connected)
            client.soTimeout = 0
            if (request.path == null) {
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            } else {
                val forwarded = buildString {
                    append("$method ${request.path} $version\r\n")
                    for (line in lines.drop(1)) {
                        if (line.isEmpty() || line.substringBefore(':').trim().lowercase() in DROPPED) continue
                        append(line).append("\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                connected.getOutputStream().apply {
                    write(forwarded.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }
            Threads.start("sandbox proxy $port upload") { pump(input, connected) }
            pump(connected.getInputStream(), client)
        } catch (e: IOException) {
            logger.debug("A sandbox proxy connection ended: {}", e.toString())
        } finally {
            for (socket in listOfNotNull(client, upstream)) {
                socket.closeQuietly()
                sockets -= socket
            }
        }
    }

    /** Whether [host] at [port] is admitted, and which of its addresses then. */
    private fun decide(host: String, port: Int): Decision {
        val pattern = allowed.firstOrNull { it.matches(host, port) } ?: return Decision.Refused(NOT_ALLOWED)
        val written = literal(host)
        val addresses = if (written != null) {
            listOf(written)
        } else {
            try {
                resolver.resolve(normalized(host))
            } catch (e: IOException) {
                return Decision.Refused("its name does not resolve")
            }
        }
        val reachable = if (pattern.local) addresses else addresses.filterNot(::isLocalAddress)
        if (reachable.isEmpty()) return Decision.Refused(LOCAL_ONLY)
        return Decision.Allowed(reachable)
    }

    private fun connect(addresses: List<InetAddress>, port: Int): Socket? {
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                return socket
            } catch (e: IOException) {
                socket.closeQuietly()
            }
        }
        return null
    }

    private fun tracked(socket: Socket) {
        sockets += socket
        if (closed) socket.closeQuietly()
    }

    private sealed interface Decision {
        class Allowed(val addresses: List<InetAddress>) : Decision

        class Refused(val why: String) : Decision
    }

    /** Where a request goes: [path] is null for a tunnel. */
    private class Target(val host: String, val port: Int, val path: String?)

    private fun absolute(target: String): Target? {
        val uri = try {
            URI(target)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        return Target(host, if (uri.port == -1) HTTP_PORT else uri.port, path)
    }

    private companion object {
        const val BACKLOG = 50
        const val CLOSE_WAIT_MILLIS = 5_000L
        const val HEAD_TIMEOUT_MILLIS = 30_000
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val MAX_HEAD_BYTES = 64 * 1024
        const val PUMP_BUFFER = 16 * 1024
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        const val NOT_ALLOWED = "it is not among the hosts the call may reach"
        const val LOCAL_ONLY = "it resolves only to local or private addresses"

        /** The headers that stay between the program and the proxy. */
        val DROPPED = setOf("proxy-connection", "proxy-authorization", "connection", "keep-alive")

        /** [host] at [port] as the call would ask for it, without a port the scheme implies. */
        fun named(host: String, port: Int): String {
            val bare = normalized(host)
            val shown = if (':' in bare) "[$bare]" else bare
            return if (port == HTTPS_PORT || port == HTTP_PORT) shown else "$shown:$port"
        }

        /** `host:port` of a CONNECT request. */
        fun authority(target: String): Pair<String, Int>? {
            val colon = target.lastIndexOf(':')
            if (colon <= 0) return null
            val port = target.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return target.substring(0, colon) to port
        }

        /** The request's head up to its empty line, null when the stream ends or it grows too long first. */
        fun readHead(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            while (bytes.size() < MAX_HEAD_BYTES) {
                val next = input.read()
                if (next < 0) return null
                bytes.write(next)
                matched = if (next == END[matched].code) {
                    matched + 1
                } else if (next == '\r'.code) {
                    1
                } else {
                    0
                }
                if (matched == END.length) return String(bytes.toByteArray(), Charsets.ISO_8859_1).removeSuffix(END)
            }
            return null
        }

        const val END = "\r\n\r\n"

        fun reply(client: Socket, status: Int, text: String) {
            val reason = when (status) {
                400 -> "Bad Request"
                403 -> "Forbidden"
                else -> "Bad Gateway"
            }
            val body = "$text\n".toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            try {
                client.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(body)
                    flush()
                }
            } catch (e: IOException) {
                logger.debug("A sandbox proxy reply was not delivered: {}", e.toString())
            }
        }

        /** Copies [from] to [to] until it ends, then ends [to]'s output. */
        fun pump(from: InputStream, to: Socket) {
            try {
                val output = to.getOutputStream()
                val buffer = ByteArray(PUMP_BUFFER)
                while (true) {
                    val count = from.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } catch (e: IOException) {
                logger.debug("A sandbox proxy stream ended: {}", e.toString())
            } finally {
                try {
                    to.shutdownOutput()
                } catch (e: IOException) {
                    logger.debug("A sandbox proxy stream was closed already: {}", e.toString())
                }
            }
        }
    }
}

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (e: IOException) {
        logger.debug("A sandbox proxy socket did not close cleanly: {}", e.toString())
    }
}

private val logger = LoggerFactory.getLogger(NetworkProxy::class.java)
