package org.unitedsoftware.alexandrite.channel.onebot.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.unitedsoftware.alexandrite.channel.onebot.auth.OneBotAuth
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEventCodec
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * The connection of an implementation that dials Alexandrite to call its API and push its events.
 *
 * Alexandrite listens, so this is the side that accepts. One connection serves both the calls and the events, as the
 * standard's universal client does, and its `X-Client-Role`, `X-Self-ID` and token are checked before it is taken.
 */
internal class OneBotReverseWebSocket(private val settings: OneBotSettings) :
    AbstractOneBotConnection(settings.eventCapacity) {
    private val awaiting = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val connected = CompletableDeferred<Unit>()
    private val bound = CountDownLatch(1)

    @Volatile
    private var socket: WebSocket? = null

    private val server = object : WebSocketServer(InetSocketAddress(settings.listenHost, settings.listenPort)) {
        override fun onStart() {
            bound.countDown()
        }

        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            when (val refusal = refusalOf(handshake)) {
                null -> {
                    // A peer that replaces the one being served takes over the calls of the connection it replaces,
                    // and the frames of the peer that left are no longer read. Calls sent on that peer would
                    // therefore wait for an answer that can never be read, so they are ended before it replaces it.
                    socket?.let { left ->
                        socket = null
                        failWaiting(IllegalStateException("A newer peer took over the connection."))
                        left.close(REPLACED_CODE, "a newer peer took over the connection")
                    }
                    socket = connection
                    connected.complete(Unit)
                }

                else -> {
                    failure = IllegalStateException(refusal)
                    connected.completeExceptionally(IllegalStateException(refusal))
                    connection.close(REFUSAL_CODE, refusal)
                }
            }
        }

        /**
         * Why this connection is refused, null when its token and its account are the ones this instance serves.
         *
         * The headers are read by walking the handshake: asking the library for one it does not hold answers Java's
         * `null` into a Kotlin non-null type, which throws inside the library's selector thread and takes the whole
         * connection with it.
         */
        private fun refusalOf(handshake: ClientHandshake): String? {
            val headers = HEADERS.filter { handshake.hasFieldValue(it) }
                .associate { it.lowercase() to handshake.getFieldValue(it) }
            val token = settings.token
            val authorization = headers[OneBotAuth.AUTHORIZATION.lowercase()]
            val offered = OneBotAuth.tokenOf(authorization)
            if (token != null && !OneBotAuth.matchesToken(token, offered)) {
                return "The connection carries another token than this instance."
            }
            val selfId = settings.selfId
            val reported = headers[OneBotAuth.SELF_ID.lowercase()]
            // An instance that serves one account takes no connection that names none: the account is what binds the
            // connection to this instance, so a missing one is as wrong as a different one.
            if (selfId != null && reported != selfId) {
                return "The connection names another account than this instance."
            }
            return refusedClientRole(headers[OneBotAuth.CLIENT_ROLE.lowercase()])
        }
        override fun onMessage(connection: WebSocket, message: String) {
            // A connection this instance refused is closed as it is opened, and one that an implementation replaced
            // is no longer the peer being served. Frames are read from the active peer alone, so that neither can
            // report an event or answer a call of the connection that is.
            if (socket !== connection) return
            val element = try {
                OneBotWire.parseToJsonElement(message)
            } catch (e: Exception) {
                failure = e
                return
            }
            val answer = element as? JsonObject ?: return
            val echo = (answer["echo"] as? JsonPrimitive)?.content
            if (echo == null) {
                val event = try {
                    OneBotEventCodec.decode(answer)
                } catch (e: Exception) {
                    failure = e
                    return
                }
                report(event)
                return
            }
            val waiting = awaiting.remove(echo) ?: return
            if (answer["status"] == null && answer["retcode"] == null) {
                waiting.completeExceptionally(IllegalArgumentException("The answer names no status: $answer"))
            } else {
                waiting.complete(answer)
            }
        }

        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
            // A refused connection and one that a newer peer replaced close too, and neither of them owes an answer
            // to a call of the connection being served. Their close says nothing about that connection, so nothing
            // waiting on it is ended here.
            if (socket !== connection) return
            socket = null
            if (state.value.isOpen) lost(IllegalStateException("The connection closed: $code $reason"))
            // A call that was waiting for this connection is not answered by a connection that is gone, so it is told
            // so now rather than when its own timeout runs out, which would report a slow implementation instead of a
            // lost one.
            failWaiting(IllegalStateException("The connection closed before it answered: $code $reason"))
        }

        override fun onError(connection: WebSocket?, ex: Exception) {
            failure = ex
        }
    }

    /** Starts listening and returns once the socket is bound, whether an implementation dialed yet or not. */
    suspend fun start() {
        moveTo(OneBotLinkState.CONNECTING)
        server.start()
        if (!bound.await(settings.connectTimeoutMillis, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("The listener at ${settings.listenHost}:${server.port} did not start.")
        }
        // The library counts the server started from its own thread before that thread takes connections, so a
        // client that dials the moment `start` returns can be answered by nobody. Let the selector take over.
        delay(START_SETTLE_MILLIS.milliseconds)
        moveTo(OneBotLinkState.OPEN)
    }

    /** Waits for the implementation to dial in, and returns whether one did. */
    suspend fun awaitPeer(): Boolean = try {
        withTimeout(settings.connectTimeoutMillis) { connected.await() }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Ends every call that waits for an answer, with [reason].
     *
     * A call that outlives the connection that would answer it has nothing left to wait for, so it is told why rather
     * than being left until its own timeout says the implementation was slow.
     */
    private fun failWaiting(reason: Exception) {
        awaiting.keys.toList().forEach { echo -> awaiting.remove(echo)?.completeExceptionally(reason) }
    }

    /** The port this connection listens on, which a configured port of 0 leaves to the operating system. */
    val port: Int get() = server.port

    override suspend fun send(action: String, params: JsonObject): JsonObject = send(action, params, null)

    /**
     * Sends one call, waiting at most [waitMillis] for its answer where a caller asks for longer than the instance
     * allows.
     *
     * A call that carries a file takes longer than one that only reads something, and its answer is what says whether
     * it arrived. Null waits as the instance's own timeout says.
     */
    suspend fun send(action: String, params: JsonObject, waitMillis: Long?): JsonObject {
        val connection = socket ?: throw IllegalStateException("No implementation is connected.")
        val echo = echoes.next()
        val waiting = CompletableDeferred<JsonObject>()
        awaiting[echo] = waiting
        try {
            connection.send(OneBotWire.encodeToString(requestOf(action, params, echo)))
        } catch (e: Exception) {
            awaiting.remove(echo)
            throw e
        }
        return try {
            withTimeout(waitMillis ?: settings.firstByteTimeoutMillis) { waiting.await() }
        } catch (e: TimeoutCancellationException) {
            awaiting.remove(echo)
            throw IOException("The implementation did not answer '$action' in time.", e)
        } catch (e: Exception) {
            awaiting.remove(echo)
            throw e
        }
    }

    override fun stop() {
        runCatching { server.stop(CLOSE_TIMEOUT_MILLIS.toInt()) }
        for (waiting in awaiting.values) {
            waiting.completeExceptionally(IllegalStateException("The connection is closing."))
        }
        awaiting.clear()
    }

    private val echoes = Echo()

    internal companion object {
        const val CLOSE_TIMEOUT_MILLIS = 1_000L

        /** The headers this listener reads, which the library answers only for the ones it holds. */
        val HEADERS = listOf(OneBotAuth.AUTHORIZATION, OneBotAuth.SELF_ID, OneBotAuth.CLIENT_ROLE)

        /** The `X-Client-Role` values one connection of this transport can carry the calls and the events both under. */
        val SERVED_ROLES = listOf("Universal", "Service")

        /** How long the listener gives the library to take over the socket it bound. */
        const val START_SETTLE_MILLIS = 150L

        /** The code a connection is closed under when this instance does not serve it. */
        const val REFUSAL_CODE = 1008

        /** The code a connection is closed under when a newer peer takes over the instance. */
        const val REPLACED_CODE = 1000
    }
}

/**
 * Why the role of a connection is refused, null when an instance can serve it.
 *
 * One connection of this transport carries the calls and the events both, which is the standard's universal
 * client, so that is the role an instance serves. A connection of `Event` carries no answers to its calls and one
 * of `API` sends none of its own, and taking either would leave the instance talking to itself. An implementation
 * that names no role keeps working, because older ones report none.
 */
internal fun refusedClientRole(role: String?): String? {
    val served = listOf("Universal", "Service")
    if (role == null || role in served) return null
    return "The connection is a $role client, but this instance serves ${served.joinToString()}; " +
        "configure a ws_reverse instance per client role instead."
}
