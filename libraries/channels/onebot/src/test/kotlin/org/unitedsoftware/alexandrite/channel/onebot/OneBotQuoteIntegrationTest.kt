package org.unitedsoftware.alexandrite.channel.onebot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.Quote
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.PluginHarness.Running
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a message that quotes another one carries.
 *
 * A report names the message it answers and says nothing about it, so the channel asks the implementation for it. What
 * a card says has to survive that ask, or the agent is told that something was quoted and not what.
 */
class OneBotQuoteIntegrationTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")

    private val directory = key<ChannelDirectory>()

    @Test
    fun `a message that quotes a card carries what the card says`() {
        val peer = QuotePeer(card)
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                harness(peer, port, submitter) {
                    peer.report(quoting("77"))
                    val message = assertIs<Submission.Message>(waiting { awaitSubmission(submitter) }).message
                    val quote = assertIs<Quote>(message.quote)
                    assertEquals("someone", quote.senderName)
                    assertEquals("[music: Supermarket - 许嵩] https://y.qq.com/x", quote.text)
                    assertEquals("77", quote.target?.id)
                    assertEquals(false, quote.senderIsBot)
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a message that quotes one the implementation cannot report carries its id and no text`() {
        val peer = QuotePeer(card, answers = false)
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                harness(peer, port, submitter) {
                    peer.report(quoting("77"))
                    val message = assertIs<Submission.Message>(waiting { awaitSubmission(submitter) }).message
                    val quote = assertIs<Quote>(message.quote)
                    assertEquals("", quote.text)
                    assertNull(quote.senderName)
                    assertEquals("77", quote.target?.id)
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a message that quotes one the channel read asks the implementation nothing`() {
        // The peer refuses to report a message at all, so a quote that carries anything can only have been kept.
        val peer = QuotePeer(card, answers = false)
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                harness(peer, port, submitter) {
                    peer.report(cardAsMessage)
                    waiting { peer.awaitSilence() }
                    peer.report(quoting("77"))
                    val asked = assertIs<Submission.Message>(waiting { awaitSubmission(submitter) }).message
                    val quote = assertIs<Quote>(asked.quote)
                    assertEquals("[music: Supermarket - 许嵩] https://y.qq.com/x", quote.text)
                    assertEquals("someone", quote.senderName)
                    assertEquals(0, peer.reads.get(), "the implementation was asked about a message already read")
                }
            }
        } finally {
            peer.stop()
        }
    }

    private suspend fun harness(
        peer: QuotePeer,
        port: Int,
        submitter: RecordingTurnSubmitter,
        block: suspend Running.() -> Unit,
    ) {
        PluginHarness.builder(AlexandriteChannelOnebotIndex())
            .config(config(port))
            .turnSubmitter(submitter)
            .build()
            .run {
                assertTrue(waiting { peer.awaitConnection() }, "the channel did not connect to the implementation")
                block()
            }
    }

    /** Waits for the agent to be asked for a turn, and returns what it was asked. */
    private fun awaitSubmission(submitter: RecordingTurnSubmitter): Submission {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            submitter.submissions.firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("the agent was asked for no turn")
    }

    /** Waits for a block that blocks its thread, without holding the one the test runs on. */
    private suspend fun <T> waiting(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** A group report that answers the message [replyTo] and names the bot. */
    private fun quoting(replyTo: String): String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "group",
          "sub_type": "normal",
          "message_id": 12,
          "group_id": 987654,
          "user_id": 12345678,
          "message": [
            {"type": "reply", "data": {"id": "$replyTo"}},
            {"type": "at", "data": {"qq": 10001000}},
            {"type": "text", "data": {"text": "这是什么"}}
          ],
          "raw_message": "这是什么",
          "sender": {"user_id": 12345678, "nickname": "someone", "role": "member"}
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
              "reconnectIntervalMillis": 200,
          "allowedChats": ["group:987654", "private:12345678"]
            }
          }
        }
    """.trimIndent()

    /** The card, as a message the implementation reports. */
    private val cardAsMessage: String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "group",
          "sub_type": "normal",
          "message_id": 77,
          "group_id": 987654,
          "user_id": 2222,
          "message": [{"type": "music", "data": {"type": "qq", "id": "1", "title": "Supermarket", "content": "许嵩", "url": "https://y.qq.com/x"}}],
          "raw_message": "[音乐]",
          "sender": {"user_id": 2222, "nickname": "someone", "role": "member"}
        }
    """.trimIndent()

    /** The message a card is reported as. */
    private val card: String = """
        {
          "message_id": 77,
          "sender": {"user_id": 2222, "nickname": "someone"},
          "message": [
            {"type": "music", "data": {
              "type": "qq", "id": "1", "title": "Supermarket",
              "content": "许嵩", "url": "https://y.qq.com/x"
            }}
          ]
        }
    """.trimIndent()

    /** An implementation that answers `get_msg` with [card], or refuses to answer for it. */
    private class QuotePeer(private val card: String, private val answers: Boolean = true) :
        WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        private val started = CountDownLatch(1)
        private val connected = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()

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
            val reading = message.contains("\"get_msg\"")
            if (reading) reads.incrementAndGet()
            val answer = when {
                reading && !answers -> """{"status":"failed","retcode":100,"data":null,"echo":"$echo"}"""
                reading -> """{"status":"ok","retcode":0,"data":$card,"echo":"$echo"}"""
                else -> """{"status":"ok","retcode":0,"data":{"message_id":7},"echo":"$echo"}"""
            }
            connection.send(answer)
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

        /** Waits long enough for the channel to have read what was reported, and returns. */
        fun awaitSilence(millis: Long = 400) = Thread.sleep(millis)
    }
}
