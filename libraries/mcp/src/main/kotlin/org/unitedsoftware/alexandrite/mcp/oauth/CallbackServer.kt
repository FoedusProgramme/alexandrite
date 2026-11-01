package org.unitedsoftware.alexandrite.mcp.oauth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.internal.portable.JdkFeatures
import org.unitedsoftware.alexandrite.internal.portable.JdkOnly
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What the browser is shown at the end of a login. */
internal class CallbackPage(val status: Int, val text: String)

/**
 * A listener on 127.0.0.1:[port] that hands the query of each GET of [callbackPath] to [complete] and serves
 * [document], when given, at [documentPath]. It needs [JdkFeatures.httpServer].
 */
@JdkOnly
internal class CallbackServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
) : AutoCloseable {
    val port: Int get() = server.address.port

    /** Stops listening at once and ends the exchanges in flight within a second. */
    override fun close() {
        Thread({
            server.stop(STOP_GRACE)
            executor.shutdownNow()
        }, "mcp-oauth-callback-stop").apply { isDaemon = true }.start()
    }

    @JdkOnly
    companion object {
        /** A listener on [port], any free one when 0, null when it cannot listen there. */
        fun open(
            port: Int,
            callbackPath: String,
            complete: suspend (String) -> CallbackPage,
            documentPath: String? = null,
            document: (() -> String)? = null,
        ): CallbackServer? {
            val server = try {
                HttpServer.create(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 0)
            } catch (e: IOException) {
                return null
            }
            val executor = Executors.newCachedThreadPool { Thread(it, "mcp-oauth-callback").apply { isDaemon = true } }
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    val path = exchange.requestURI.rawPath
                    val page = when {
                        exchange.requestMethod != "GET" -> CallbackPage(METHOD_NOT_ALLOWED, "Only GET is served here.")
                        path == callbackPath -> runBlocking { complete(exchange.requestURI.rawQuery.orEmpty()) }
                        path == documentPath && document != null -> return@createContext json(exchange, document())
                        else -> CallbackPage(NOT_FOUND, "Nothing is served here.")
                    }
                    send(exchange, page)
                } catch (e: IOException) {
                    // The browser went away.
                } finally {
                    exchange.close()
                }
            }
            server.start()
            return CallbackServer(server, executor)
        }

        private fun send(exchange: HttpExchange, page: CallbackPage) {
            val bytes = page.text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
            exchange.sendResponseHeaders(page.status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        private fun json(exchange: HttpExchange, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", OAuthHttp.JSON)
            exchange.sendResponseHeaders(OK, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        const val LOOPBACK: String = "127.0.0.1"
        const val OK: Int = 200
        const val BAD_REQUEST: Int = 400
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val STOP_GRACE = 1
    }
}
