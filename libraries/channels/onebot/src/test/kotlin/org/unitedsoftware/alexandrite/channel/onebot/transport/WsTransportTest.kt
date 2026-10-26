package org.unitedsoftware.alexandrite.channel.onebot.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.unitedsoftware.alexandrite.channel.onebot.auth.OneBotAuth
import org.unitedsoftware.alexandrite.channel.onebot.auth.Value
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.sdk.config.Secret
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two Websocket transports, each against a peer built from the other side of the library. */
class WsTransportTest {
    private val token = Value.of(Secret("s3cr3t-token"))

    @Test
    fun `a forward connection reads the answer to its call and the event beside it`() {
        val peer = ImplementationServer()
        val port = peer.startAndPort()
        val connection = OneBotForwardWebSocket(settings("ws://127.0.0.1:" + port))
        try {
            runBlocking {
                connection.start()
                val answer = connection.send("get_login_info", JsonObject(emptyMap()))
                assertEquals(
                    "10001000",
                    answer["data"]?.let {
                        it as? JsonObject
                    }?.get("user_id")?.jsonPrimitive?.content,
                    "answer was " + answer,
                )
                assertTrue(peer.called().contains("get_login_info"), "the peer was not called")
                val event = withTimeout(5_000) { connection.events.first() }
                assertIs<OneBotEvent.Meta.Lifecycle>(event)
            }
        } finally {
            connection.close()
            peer.stop()
        }
    }

    @Test
    fun `a forward connection reconnects after the implementation drops it`() {
        val peer = ImplementationServer()
        val port = peer.startAndPort()
        val connection = OneBotForwardWebSocket(settings("ws://127.0.0.1:" + port))
        try {
            runBlocking {
                connection.start()
                assertTrue(connection.state.value.isOpen, "the connection did not open")
                peer.dropConnection()
                withTimeout(5_000) {
                    while (connection.state.value.isOpen) kotlinx.coroutines.delay(10)
                }
                withTimeout(5_000) {
                    while (!connection.state.value.isOpen) kotlinx.coroutines.delay(10)
                }
            }
        } finally {
            connection.close()
            peer.stop()
        }
    }

    @Test
    fun `a forward connection's backoff grows and caps as failures repeat`() {
        val connection = OneBotForwardWebSocket(settings("ws://127.0.0.1:1"))
        assertEquals(100L, connection.backOff(1))
        assertEquals(200L, connection.backOff(2))
        assertEquals(400L, connection.backOff(3))
        assertEquals(2_000L, connection.backOff(20))
    }

    @Test
    fun `a forward connection brings the token of its instance`() {
        val peer = ImplementationServer()
        val port = peer.startAndPort()
        val connection = OneBotForwardWebSocket(settings("ws://127.0.0.1:" + port))
        try {
            runBlocking {
                connection.start()
                assertEquals("Bearer s3cr3t-token", peer.offered)
            }
        } finally {
            connection.close()
            peer.stop()
        }
    }

    @Test
    fun `a reverse connection takes the implementation that dials it`() {
        val connection = OneBotReverseWebSocket(settings(null, port = 0))
        try {
            runBlocking {
                connection.start()
                val peer = DialingImplementation(URI("ws://127.0.0.1:" + connection.port))
                assertTrue(peer.connectBlocking(5, TimeUnit.SECONDS), "the implementation did not connect")
                assertTrue(connection.awaitPeer(), "not taken; state=" + connection.state.value)
                val answer = connection.send("get_version_info", JsonObject(emptyMap()))
                assertEquals(
                    "v11",
                    answer["data"]?.let { it as? JsonObject }?.get("protocol_version")?.jsonPrimitive?.content,
                    "answer was " + answer,
                )
                peer.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a reverse connection ends a waiting call when the implementation leaves`() {
        val connection = OneBotReverseWebSocket(settings(null, port = 0))
        try {
            runBlocking {
                connection.start()
                val peer = SilentImplementation(URI("ws://127.0.0.1:" + connection.port))
                assertTrue(peer.connectBlocking(5, TimeUnit.SECONDS), "the implementation did not connect")
                assertTrue(connection.awaitPeer(), "not taken; state=" + connection.state.value)

                // The call goes out and is never answered, and then the implementation leaves. Its caller has to hear
                // about that rather than wait for a timeout that would blame a slow implementation for a lost one.
                val call = async { runCatching { connection.send("get_version_info", JsonObject(emptyMap())) } }
                peer.awaitCall()
                peer.close()
                val ended = withTimeout(2_000) { call.await() }
                assertTrue(ended.isFailure, "the call was answered by a connection that had left")
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a reverse listener serves the roles that carry calls and events both`() {
        assertNull(refusedClientRole(null), "an implementation that names no role must keep working")
        assertNull(refusedClientRole("Universal"), "universal carries the calls and the events both")
        assertNull(refusedClientRole("Service"), "service does too")
        assertNotNull(refusedClientRole("API"), "an API client sends no events this instance could take")
        assertNotNull(refusedClientRole("Event"), "an Event client answers no call this instance would make")
    }

    @Test
    fun `a reverse call that times out is a failure rather than a cancellation`() {
        val connection = OneBotReverseWebSocket(settings(null, port = 0))
        try {
            runBlocking {
                connection.start()
                val peer = SilentImplementation(URI("ws://127.0.0.1:" + connection.port))
                assertTrue(peer.connectBlocking(5, TimeUnit.SECONDS), "the implementation did not connect")
                assertTrue(connection.awaitPeer(), "not taken; state=" + connection.state.value)

                val failure = assertFailsWith<IOException> {
                    connection.send("get_version_info", JsonObject(emptyMap()), waitMillis = 50)
                }
                peer.close()
            }
        } finally {
            connection.close()
        }
    }

    private fun settings(endpoint: String?, port: Int = 0): OneBotSettings = OneBotSettings(
        endpoint = endpoint,
        listenHost = "127.0.0.1",
        listenPort = port,
        path = "/",
        token = token,
        secret = null,
        selfId = null,
        connectTimeoutMillis = 5_000,
        firstByteTimeoutMillis = 5_000,
        idleTimeoutMillis = 5_000,
        reconnectIntervalMillis = 100,
        eventCapacity = 16,
    )

    /** An implementation that serves the API and pushes one event, as the standard's server does. */
    private class ImplementationServer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        private val ready = CountDownLatch(1)
        private val calls = CountDownLatch(1)
        private val received = CopyOnWriteArrayList<String>()

        @Volatile
        var offered: String? = null
            private set

        @Volatile
        private var connected: WebSocket? = null

        override fun onStart() {
            ready.countDown()
        }

        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            offered = handshake.getFieldValue(OneBotAuth.AUTHORIZATION)
            connected = connection
        }

        /** Closes the connection a client has open, so that it sees the implementation drop it. */
        fun dropConnection() {
            connected?.close()
        }

        override fun onMessage(connection: WebSocket, message: String) {
            received += message
            calls.countDown()
            val action = message.substringAfter("\"action\":\"", "").substringBefore('"')
            val echo = message.substringAfter("\"echo\":\"", "").substringBefore('"')
            val data = if (action == "get_login_info") "{\"user_id\":10001000}" else "{\"protocol_version\":\"v11\"}"
            connection.send("{\"status\":\"ok\",\"retcode\":0,\"data\":" + data + ",\"echo\":\"" + echo + "\"}")
            if (action == "get_login_info") connection.send(LIFECYCLE)
        }

        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

        override fun onError(connection: WebSocket?, ex: Exception) = Unit

        /** Starts listening and returns the port this peer took. */
        fun startAndPort(): Int {
            start()
            check(ready.await(5, TimeUnit.SECONDS)) { "the peer did not start" }
            return port
        }

        /** Waits for the first call and returns it. */
        fun called(): String {
            check(calls.await(5, TimeUnit.SECONDS)) { "the peer was called by nobody" }
            return received.first()
        }
    }

    /** An implementation that dials Alexandrite, as the standard's universal client does. */
    private class DialingImplementation(uri: URI) :
        WebSocketClient(
            uri,
            mapOf(
                OneBotAuth.AUTHORIZATION to OneBotAuth.bearer(Value.secretOf("s3cr3t-token")),
                OneBotAuth.SELF_ID to "10001000",
                OneBotAuth.CLIENT_ROLE to "Universal",
            ),
        ) {
        @Volatile
        var offered: String? = null
            private set

        override fun onOpen(handshake: ServerHandshake) {
            offered = handshake.getFieldValue(OneBotAuth.AUTHORIZATION)
        }

        override fun onMessage(message: String) {
            val echo = message.substringAfter("\"echo\":\"", "").substringBefore('"')
            if (echo.isEmpty()) return
            send("{\"status\":\"ok\",\"retcode\":0,\"data\":{\"protocol_version\":\"v11\"},\"echo\":\"" + echo + "\"}")
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) = Unit

        override fun onError(ex: Exception) = Unit
    }

    /** An implementation that dials in, takes a call and never answers it, so that a caller is left waiting. */
    private class SilentImplementation(uri: URI) :
        WebSocketClient(
            uri,
            mapOf(
                OneBotAuth.AUTHORIZATION to OneBotAuth.bearer(Value.secretOf("s3cr3t-token")),
                OneBotAuth.SELF_ID to "10001000",
                OneBotAuth.CLIENT_ROLE to "Universal",
            ),
        ) {
        private val called = CountDownLatch(1)

        override fun onOpen(handshake: ServerHandshake) = Unit

        override fun onMessage(message: String) {
            if (message.contains("\"echo\"")) called.countDown()
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) = Unit

        override fun onError(ex: Exception) = Unit

        /** Waits until a call has arrived, and returns whether one did. */
        fun awaitCall(): Boolean = called.await(5, TimeUnit.SECONDS)
    }

    private companion object {
        const val LIFECYCLE =
            "{\"time\":1,\"self_id\":10001000,\"post_type\":\"meta_event\"," +
                "\"meta_event_type\":\"lifecycle\",\"sub_type\":\"connect\"}"

        const val EVENT =
            "{\"time\":1,\"self_id\":10001000,\"post_type\":\"meta_event\"," +
                "\"meta_event_type\":\"heartbeat\",\"interval\":15000}"
    }
}
