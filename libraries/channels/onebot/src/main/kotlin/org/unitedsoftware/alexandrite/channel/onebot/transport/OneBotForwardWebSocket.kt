package org.unitedsoftware.alexandrite.channel.onebot.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.unitedsoftware.alexandrite.channel.onebot.auth.OneBotAuth
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEventCodec
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The connection of an implementation that serves its API and pushes its events on one WebSocket.
 *
 * The standard answers a call on the same socket it was sent on, under the `echo` of the request, and pushes events
 * on the same socket without an echo, so this class tells the two apart and keeps the calls that are still waiting.
 * The socket is kept up: a connection that drops is made again after the instance's reconnect interval, and a call
 * that waits when the socket drops fails at once.
 */
internal class OneBotForwardWebSocket(settings: OneBotSettings) : AbstractOneBotConnection(settings.eventCapacity) {
    private val settings = settings
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    @Volatile
    private var client: AnsweringClient? = null

    /** Connects and returns once the implementation answered the handshake. */
    public suspend fun start() {
        check(scope == null) { "The connection is already open." }
        moveTo(OneBotLinkState.CONNECTING)
        val opened = CompletableDeferred<Throwable?>()
        val run = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = run
        loop = run.launch { connectLoop(opened) }
        val failure = try {
            withTimeout(settings.connectTimeoutMillis) { opened.await() }
        } catch (e: TimeoutCancellationException) {
            stop()
            throw IOException(
                "The implementation at ${settings.endpoint} did not open within ${settings.connectTimeoutMillis} ms.",
                e,
            )
        }
        if (failure != null) throw failure
    }

    override suspend fun send(action: String, params: JsonObject): JsonObject = send(action, params, null)

    /**
     * Sends one call, waiting at most [waitMillis] for its answer where a caller asks for longer than the instance
     * allows.
     *
     * A call that carries a file takes longer than one that only reads something, and its answer is what says whether
     * it arrived. Null waits as the instance's own timeout says.
     */
    suspend fun send(action: String, params: JsonObject, waitMillis: Long?): JsonObject {
        val socket = client ?: throw IllegalStateException("The connection is not open.")
        val echo = echoes.next()
        val waiting = CompletableDeferred<JsonObject>()
        socket.awaiting[echo] = waiting
        try {
            socket.send(OneBotWire.encodeToString(requestOf(action, params, echo)))
        } catch (e: Exception) {
            socket.awaiting.remove(echo)
            throw e
        }
        return try {
            withTimeout(waitMillis ?: settings.firstByteTimeoutMillis) { waiting.await() }
        } catch (e: TimeoutCancellationException) {
            socket.awaiting.remove(echo)
            throw IOException("The implementation did not answer '$action' in time.", e)
        } catch (e: Exception) {
            socket.awaiting.remove(echo)
            throw e
        }
    }

    override fun stop() {
        // A call waiting for an answer is ended here, before the close, whose handler runs on a thread this is about
        // to cancel: a caller would otherwise stay suspended until its own timeout, after `stop` has returned.
        // The close comes after, so that nothing new is sent in between.
        failPending(IllegalStateException("The connection to ${settings.endpoint} is closing."))
        runCatching { client?.close() }
        val job = loop
        scope?.cancel()
        scope = null
        loop = null
        if (job != null) runBlocking { withTimeoutOrNull(STOP_JOIN_MILLIS) { job.join() } }
    }

    private suspend fun connectLoop(opened: CompletableDeferred<Throwable?>) {
        var failures = 0
        while (scope != null) {
            val ended = CompletableDeferred<Unit>()
            val socket = AnsweringClient(URI(settings.requireEndpoint()), ended)
            client = socket
            val connected = try {
                socket.connectBlocking(settings.connectTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                if (!opened.isCompleted) {
                    opened.complete(e)
                    return
                }
                null
            }
            if (connected == false) {
                if (!opened.isCompleted) {
                    opened.complete(IllegalStateException("The implementation at ${settings.endpoint} did not answer."))
                    return
                }
            } else if (connected == true) {
                failures = 0
                moveTo(OneBotLinkState.OPEN)
                if (!opened.isCompleted) opened.complete(null)
                ended.await()
                failPending(IllegalStateException("The connection to ${settings.endpoint} ended."))
            }
            if (scope == null) return
            failures++
            delay(backOff(failures))
        }
    }

    /** The pause after the [failures]th failed or dropped connection in a row, doubling up to 20 times the interval. */
    internal fun backOff(failures: Int): Long {
        val base = settings.reconnectIntervalMillis
        val grown = base * (1L shl (failures - 1).coerceAtMost(MAX_SHIFT))
        return grown.coerceAtMost(base * MAX_BACKOFF_MULTIPLIER)
    }

    private fun failPending(error: Throwable) {
        val socket = client ?: return
        for (waiting in socket.awaiting.values) waiting.completeExceptionally(error)
        socket.awaiting.clear()
    }

    /** The socket of one connection, which reads its frames on the thread of the library. */
    private inner class AnsweringClient(uri: URI, private val ended: CompletableDeferred<Unit>) :
        WebSocketClient(
            uri,
            buildMap {
                settings.token?.let { put(OneBotAuth.AUTHORIZATION, OneBotAuth.bearer(it)) }
            },
        ) {
        val awaiting = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val stopping = AtomicBoolean()

        override fun onOpen(handshake: ServerHandshake) {
            moveTo(OneBotLinkState.OPEN)
        }

        override fun onMessage(message: String) {
            val element = try {
                OneBotWire.parseToJsonElement(message)
            } catch (e: Exception) {
                failure = e
                return
            }
            val answer = element as? JsonObject ?: return
            val echo = (answer["echo"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (echo != null) {
                val waiting = awaiting.remove(echo)
                if (waiting != null) {
                    if (answer["status"] == null && answer["retcode"] == null) {
                        waiting.completeExceptionally(IllegalArgumentException("The answer names no status: $answer"))
                    } else {
                        waiting.complete(answer)
                    }
                    return
                }
            }
            val event = try {
                OneBotEventCodec.decode(answer)
            } catch (e: Exception) {
                failure = e
                return
            }
            report(event)
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            if (!stopping.get() && scope != null) lost(IllegalStateException("The connection closed: $code $reason"))
            ended.complete(Unit)
        }

        override fun onError(ex: Exception) {
            failure = ex
        }

        override fun close() {
            stopping.set(true)
            super.close()
        }
    }

    private val echoes = Echo()

    private companion object {
        /** How long [stop] waits for the connect loop to end before returning anyway. */
        const val STOP_JOIN_MILLIS = 1_000L

        const val MAX_BACKOFF_MULTIPLIER = 20L
        const val MAX_SHIFT = 10
    }
}
