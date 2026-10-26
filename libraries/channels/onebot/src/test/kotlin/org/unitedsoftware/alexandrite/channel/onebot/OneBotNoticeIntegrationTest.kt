package org.unitedsoftware.alexandrite.channel.onebot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.unitedsoftware.alexandrite.sdk.channel.ChannelNotice
import org.unitedsoftware.alexandrite.sdk.channel.ChannelNoticeKind
import org.unitedsoftware.alexandrite.sdk.channel.ChannelPoints
import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A report that is not a message reaching a plugin.
 *
 * Nothing in the runtime knows about a channel notice: it reaches a subscriber because the subscriber says which point
 * it observes, which is what this proves by observing one.
 */
class OneBotNoticeIntegrationTest {
    @Test
    fun `a report that is not a message reaches a plugin that observes it`() {
        val peer = NoticePeer()
        val port = peer.startAndPort()
        val observer = RecordingObserver()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(RecordingTurnSubmitter())
                    .hook(observer)
                    .build()
                    .run {
                        assertTrue(waiting { peer.awaitConnection() }, "the channel did not connect")
                        peer.report(increase())
                        val notice = assertNotNull(
                            waiting { observer.awaitOne() },
                            "the notice reached no subscriber",
                        )
                        assertEquals(ChannelNoticeKind.MEMBER_JOINED, notice.kind)
                        assertEquals("group:111111", notice.chat.chat)
                        assertEquals("2222", notice.target?.address?.user)
                    }
            }
        } finally {
            peer.stop()
        }
    }

    private suspend fun <T> waiting(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun <T : Any> assertNotNull(value: T?, message: String): T = kotlin.test.assertNotNull(value, message)

    /**
     * A group that the config does not declare, so that this also fixes the decision that a notice is published for a
     * chat the instance does not serve: a chat that is not served yet is where somebody asks to join.
     */
    private fun increase(): String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "notice",
          "notice_type": "group_increase",
          "sub_type": "approve",
          "group_id": 111111,
          "user_id": 2222,
          "operator_id": 3333
        }
    """.trimIndent()

    private fun config(port: Int): String = """
        {
          "settings": {"eventQueueCapacity": 64},
          "instances": {
            "main": {
              "transport": "ws",
              "endpoint": "ws://127.0.0.1:$port",
              "connectTimeoutMillis": 5000,
              "firstByteTimeoutMillis": 5000,
              "idleTimeoutMillis": 5000,
              "reconnectIntervalMillis": 200
            }
          }
        }
    """.trimIndent()

    /** A subscriber that keeps every notice it is handed. */
    private class RecordingObserver : ObserverHook<ChannelNotice> {
        override val point: ObserverPoint<ChannelNotice> = ChannelPoints.NOTICE

        private val seen = CopyOnWriteArrayList<ChannelNotice>()

        override suspend fun observe(payload: ChannelNotice) {
            seen += payload
        }

        /** Waits for the first notice, and returns it, null when none came. */
        fun awaitOne(): ChannelNotice? {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                seen.firstOrNull()?.let { return it }
                Thread.sleep(20)
            }
            return null
        }
    }

    /** An implementation that serves the API and reports what the test says. */
    private class NoticePeer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        private val started = CountDownLatch(1)
        private val connected = CountDownLatch(1)

        @Volatile
        private var socket: WebSocket? = null

        override fun onStart() {
            started.countDown()
        }

        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            socket = connection
            connected.countDown()
        }

        override fun onMessage(connection: WebSocket, message: String) {
            val echo = message.substringAfter("\"echo\":\"", "").substringBefore('"')
            if (echo.isEmpty()) return
            connection.send("""{"status":"ok","retcode":0,"data":{"message_id":7},"echo":"$echo"}""")
        }

        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
            if (socket === connection) socket = null
        }

        override fun onError(connection: WebSocket?, ex: Exception) = Unit

        fun startAndPort(): Int {
            start()
            check(started.await(5, TimeUnit.SECONDS)) { "the peer did not start" }
            return port
        }

        fun report(json: String) {
            val connection = socket ?: error("nothing is connected")
            connection.send(json)
        }

        fun awaitConnection(): Boolean = connected.await(5, TimeUnit.SECONDS)
    }
}
