package org.unitedsoftware.alexandrite.channel.onebot

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.PluginHarness.Running
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a message carrying a picture gives the agent.
 *
 * A report names the picture by an address, and the bytes behind that address are what the model is shown. A reader
 * that cannot get them says so rather than handing the turn an empty file.
 */
class OneBotMediaIntegrationTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")

    private val directory = key<ChannelDirectory>()
    private val picture = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun `a picture a report names is read from the address it names`() {
        withPicture { url, peer, submitter ->
            peer.report(pictureMessage(url))
            val message = assertIs<Submission.Message>(waiting { awaitSubmission(submitter) }).message
            val attachment = message.media.single()
            assertEquals("image/*", attachment.mediaType)
            assertContentEquals(picture, waiting { runBlocking { checkNotNull(attachment.content).read(1024) } })
        }
    }

    @Test
    fun `a picture larger than the agent allows is refused`() {
        withPicture { url, peer, submitter ->
            peer.report(pictureMessage(url))
            val message = assertIs<Submission.Message>(waiting { awaitSubmission(submitter) }).message
            val attachment = message.media.single()
            val failure =
                assertFailsWith<Exception> { waiting { runBlocking { checkNotNull(attachment.content).read(4) } } }
            assertTrue(
                failure.message.orEmpty().contains("larger"),
                "a body larger than the agent allows was not refused: ${failure.message}",
            )
        }
    }

    @Test
    fun `a picture in the message that was quoted reaches the agent as well`() {
        withPicture { url, peer, submitter ->
            peer.report(pictureMessage(url))
            awaitSubmissions(submitter, 1)
            peer.report(questionAbout(12))
            val message = assertIs<Submission.Message>(awaitSubmissions(submitter, 2).last()).message
            // The report answers a message holding a picture and says nothing about that picture, so the picture
            // reaches the turn only because what was read of the quoted message is kept beside its text.
            val attachment = message.media.single()
            assertEquals("image/*", attachment.mediaType)
            assertContentEquals(picture, waiting { runBlocking { checkNotNull(attachment.content).read(1024) } })
        }
    }

    /** A group report that answers the message [id] and names the bot. */
    private fun questionAbout(id: Long): String = """
        {
          "time": 1515204255,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "group",
          "sub_type": "normal",
          "message_id": 13,
          "group_id": 987654,
          "user_id": 12345678,
          "message": [
            {"type": "reply", "data": {"id": $id}},
            {"type": "at", "data": {"qq": 10001000}},
            {"type": "text", "data": {"text": "what is this"}}
          ],
          "raw_message": "[CQ:reply,id=$id] what is this",
          "sender": {"user_id": 12345678, "nickname": "someone", "role": "member"}
        }
    """.trimIndent()

    /** The first [count] turns the agent was asked for. */
    private suspend fun awaitSubmissions(submitter: RecordingTurnSubmitter, count: Int): List<Submission> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val taken = submitter.submissions
            if (taken.size >= count) return taken
            Thread.sleep(20)
        }
        error("the agent was asked for ${submitter.submissions.size} turns, not $count")
    }

    /** Serves [picture] over HTTP, runs the plugin against [peer], and calls [block]. */
    private fun withPicture(block: suspend Running.(String, MediaPeer, RecordingTurnSubmitter) -> Unit) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/picture") { exchange ->
            exchange.sendResponseHeaders(200, picture.size.toLong())
            exchange.responseBody.use { it.write(picture) }
        }
        http.start()
        val url = "http://127.0.0.1:${http.address.port}/picture"
        val peer = MediaPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(waiting { peer.awaitConnection() }, "the channel did not connect")
                        block(url, peer, submitter)
                    }
            }
        } finally {
            peer.stop()
            http.stop(0)
        }
    }

    private suspend fun <T> waiting(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun awaitSubmission(submitter: RecordingTurnSubmitter): Submission {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            submitter.submissions.firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        error("the agent was asked for no turn")
    }

    /** A group report that names the bot and carries a picture at [url]. */
    private fun pictureMessage(url: String): String = """
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
            {"type": "at", "data": {"qq": 10001000}},
            {"type": "image", "data": {"url": "$url", "file": "a.jpg"}}
          ],
          "raw_message": "[image]",
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
              "allowedChats": ["group:987654"]
            }
          }
        }
    """.trimIndent()

    /** An implementation that serves the API and reports what the test says. */
    private class MediaPeer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
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
