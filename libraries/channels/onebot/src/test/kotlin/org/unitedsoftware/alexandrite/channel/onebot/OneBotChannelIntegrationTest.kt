package org.unitedsoftware.alexandrite.channel.onebot

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.PluginHarness.Running
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The OneBot channel inside a real runtime.
 *
 * A harness builds the plugin set and the container, a peer built from the library serves the implementation, and the
 * test drives what a user would do: the implementation reports a message, the agent is asked for a turn, and the
 * answer the agent writes goes back out through the actions of the standard.
 */
class OneBotChannelIntegrationTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")

    private val directory = key<ChannelDirectory>()

    @Test
    fun `a reported message reaches the agent and the answer goes back to the implementation`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(privateMessage())
                        val submission = awaitSubmission(submitter)
                        val message = assertIs<Submission.Message>(submission)
                        assertEquals("private:12345678", message.message.chat.chat)
                        assertEquals("hello there", message.message.text)

                        val channel = org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory::class.java
                        val delivery = sendTo(message.message.chat)
                        assertEquals("7", assertIs<Delivery.Delivered>(delivery).messages.single().id)
                        val call = peer.awaitCall("send_private_msg")
                        assertTrue(call.contains("hello back"), "the implementation was not sent the reply: $call")
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a report the implementation sends twice asks for one turn`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(privateMessage())
                        awaitSubmission(submitter)
                        peer.report(privateMessage())
                        Thread.sleep(300)
                        assertEquals(1, submitter.submissions.size, "a repeated report became a second turn")
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message that names nobody reaches no agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "hello there"))
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "the agent was asked for a turn of a message that names nobody",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message that only names the bot reaches no agent`() {
        // The Telegram channel ignores a message that is only a mention of the bot, and this channel now does too:
        // naming the bot with nothing to answer is not a message the agent is asked a turn of.
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "", at = "10001000"))
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "the agent was asked for a turn of a message that only names the bot",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message that is only a slash and words reaches no agent`() {
        // The other channel hears a command only when the platform marks one, so a message whose slash does not start
        // a command name is a message like any other: in a group it reaches the bot only when it is addressed to it.
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "/随便说点什么"))
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "the agent was asked for a turn of a slash message that names no command",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message that names the bot reaches the agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "hello there", at = "10001000"))
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter))
                        assertEquals("hello there", message.message.text)
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a command in a group reaches the agent as a command without naming the bot`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(groupMessage(text = "/model deepseek/one"))
                        val command = assertIs<Submission.Command>(awaitSubmission(submitter))
                        assertEquals("model", command.invocation.name)
                        assertEquals("deepseek/one", command.invocation.arguments)
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a group message answering a message the bot sent reaches the agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        val group = ChatAddress(instance, "group:987654")
                        assertIs<Delivery.Delivered>(sendTo(group))
                        peer.report(groupMessage(text = "hello there", reply = "8", messageId = 12))
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "the agent was asked for a turn of a message answering somebody else",
                        )
                        peer.report(groupMessage(text = "hello there", reply = "7", messageId = 13))
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter))
                        assertEquals("hello there", message.message.text)
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a chat this instance does not serve is offered its pairing code and reaches no agent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(unservedGroupMessage())
                        val said = peer.awaitCall("send_group_msg")
                        assertTrue(said.contains("not paired"), "the chat was not offered a code: $said")
                        assertTrue(
                            awaitNoSubmission(submitter),
                            "a message of a chat this instance does not serve reached the agent",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    /** A group report of a chat that the config of the test does not declare. */
    private fun unservedGroupMessage(): String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "group",
          "sub_type": "normal",
          "message_id": 12,
          "group_id": 111111,
          "user_id": 12345678,
          "message": [
            {"type": "at", "data": {"qq": 10001000}},
            {"type": "text", "data": {"text": "hello there"}}
          ],
          "raw_message": "hello there",
          "sender": {"user_id": 12345678, "nickname": "someone", "role": "member"}
        }
    """.trimIndent()

    private suspend fun org.unitedsoftware.alexandrite.testkit.PluginHarness.Running.awaitSubmission(
        submitter: RecordingTurnSubmitter,
    ): Submission {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            submitter.submissions.firstOrNull()?.let { return it }
            kotlinx.coroutines.delay(20)
        }
        error("the agent was asked for no turn")
    }

    @Test
    fun `a turn that works says so as it starts and as it ends, and not on every step`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(privateMessage())
                        val chat = assertIs<Submission.Message>(awaitSubmission(submitter)).message.chat
                        val sink = front().openReply(ReplyRequest(testTurn(chat = chat), null))

                        sink.preview(0, "Let me check.")
                        assertEquals(0, peer.callsOf("send_private_msg"), "a preview is no message of this platform")

                        sink.startSegment(1)
                        assertEquals(0, peer.callsOf("send_private_msg"), "a segment that ended is no message either")

                        sink.event(
                            TurnEvent.ToolCallStarted(ToolCallId("c1"), "fs.read", ToolActivity.READ, "notes.md"),
                        )
                        val told = peer.awaitCall("send_private_msg")
                        assertTrue(told.contains("Reading 1 file"), "the chat was not told of the work: $told")

                        sink.event(
                            TurnEvent.ToolCallStarted(ToolCallId("c2"), "fs.search", ToolActivity.SEARCH, "milk"),
                        )
                        sink.event(TurnEvent.ToolCallUpdated(ToolCallId("c2"), ToolCallStatus.COMPLETED))
                        sink.preview(1, "It ran.")
                        assertEquals(1, peer.callsOf("send_private_msg"), "the words of a response are not a preview")

                        sink.complete(OutboundMessage.builder("done", MessageKind.REPLY).build())
                        val last = peer.awaitCall("send_private_msg")
                        assertTrue(last.contains("done"), "the answer did not go out: $last")
                        assertEquals(
                            3,
                            peer.callsOf("send_private_msg"),
                            "the chat is told as the work starts, told what ended before the answer, then answered",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    /** The channel of the instance under test. */
    private suspend fun Running.front(): Channel =
        get(directory).channel(instance) ?: error("the instance has no channel")

    @Test
    fun `only a configured admin is an operator of the instance`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port, admins = setOf("12345678")))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection())
                        val chat = ChatAddress(instance, "private:12345678")
                        assertTrue(front().isOperator(chat, UserAddress(instance, "12345678")))
                        assertFalse(front().isOperator(chat, UserAddress(instance, "99999999")))
                        val other = ChannelInstanceId(ChannelType("onebot"), "other")
                        assertFalse(front().isOperator(chat, UserAddress(other, "12345678")))
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `what the channel sends and drops is logged`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            val lines = runBlocking {
                logged {
                    PluginHarness.builder(AlexandriteChannelOnebotIndex())
                        .config(config(port))
                        .turnSubmitter(submitter)
                        .build()
                        .run {
                            assertTrue(peer.awaitConnection())
                            peer.report(privateMessage())
                            val chat = assertIs<Submission.Message>(awaitSubmission(submitter)).message.chat
                            val sink = front().openReply(ReplyRequest(testTurn(chat = chat), null))
                            sink.preview(0, "Let me check.")
                            sink.startSegment(1)
                            sink.complete(OutboundMessage.builder("done", MessageKind.REPLY).build())
                        }
                }
            }

            assertTrue(lines.any { it.contains("Sent ") })
            assertTrue(lines.any { it.contains("segment 0") })
            assertTrue(lines.any { it.contains("becomes a turn") })
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `two replies of one conversation tell a chat of its first call once`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(privateMessage())
                        val chat = assertIs<Submission.Message>(awaitSubmission(submitter)).message.chat
                        val turn = testTurn(chat = chat)
                        val first = front().openReply(ReplyRequest(turn, null))
                        val second = front().openReply(ReplyRequest(turn, null))

                        first.event(
                            TurnEvent.ToolCallStarted(ToolCallId("c1"), "fs.read", ToolActivity.READ, "notes.md"),
                        )
                        val told = peer.awaitCall("send_private_msg")
                        assertTrue(told.contains("Reading 1 file"), "the first call of the conversation: $told")

                        second.event(
                            TurnEvent.ToolCallStarted(ToolCallId("c2"), "fs.list", ToolActivity.LIST, "src"),
                        )
                        assertEquals(
                            1,
                            peer.callsOf("send_private_msg"),
                            "a chat hears the first call of its conversation once, however many replies it has",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a message that carries a file goes as the file alone`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        val bytes = byteArrayOf(1, 2, 3)
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelOnebotIndex())
                    .config(config(port))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                        peer.report(privateMessage())
                        val chat = assertIs<Submission.Message>(awaitSubmission(submitter)).message.chat
                        val file = OutboundFile.builder("notes.txt", "text/plain", bytes).build()
                        val message =
                            OutboundMessage.builder("here it is", MessageKind.REPLY).files(listOf(file)).build()

                        front().send(chat, message)

                        val call = peer.awaitCall("upload_private_file")
                        assertTrue(
                            call.contains("base64://" + java.util.Base64.getEncoder().encodeToString(bytes)),
                            "the bytes go to the implementation: $call",
                        )
                        assertTrue(call.contains("notes.txt"), "the file keeps its name: $call")
                        assertEquals(
                            0,
                            peer.callsOf("send_private_msg"),
                            "this platform shows the file's own name, so no text is sent beside it",
                        )
                    }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `a message the platform refuses is logged as one that was not sent`() {
        val peer = ImplementationPeer()
        val port = peer.startAndPort()
        val submitter = RecordingTurnSubmitter()
        try {
            val lines = runBlocking {
                logged {
                    PluginHarness.builder(AlexandriteChannelOnebotIndex())
                        .config(config(port))
                        .turnSubmitter(submitter)
                        .build()
                        .run {
                            assertTrue(peer.awaitConnection(), "the channel did not connect to the implementation")
                            peer.report(privateMessage())
                            val chat = assertIs<Submission.Message>(awaitSubmission(submitter)).message.chat
                            peer.failNext("send_private_msg")
                            val delivery = front().send(
                                chat,
                                OutboundMessage.builder("hi", MessageKind.REPLY).build(),
                            )
                            assertIs<Delivery.NotDelivered>(delivery, "the platform refused the message")
                        }
                }
            }
            assertTrue(lines.any { it.contains("Could not send") }, "the refusal was not logged: $lines")
            assertTrue(lines.none { it.contains("Sent REPLY") }, "a refused message was logged as sent: $lines")
        } finally {
            peer.stop()
        }
    }

    private suspend fun Running.sendTo(chat: ChatAddress): Delivery {
        val front = get(directory).channel(instance) ?: error("the instance has no channel")
        val message = OutboundMessage.builder("hello back", MessageKind.REPLY).build()
        return front.send(chat, message)
    }

    private fun config(port: Int, admins: Set<String> = emptySet()): String = """
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
          "allowedChats": ["group:987654", "private:12345678"],
          "admins": [${admins.joinToString(",") { "\"$it\"" }}]
            }
          }
        }
    """.trimIndent()

    private fun privateMessage(): String = """
        {
          "time": 1515204254,
          "self_id": 10001000,
          "post_type": "message",
          "message_type": "private",
          "sub_type": "friend",
          "message_id": 12,
          "user_id": 12345678,
          "message": "hello there",
          "raw_message": "hello there",
          "sender": {"user_id": 12345678, "nickname": "someone"}
        }
    """.trimIndent()

    /** A group report of [text], whose message names [at] or answers [reply] when they are given. */
    private fun groupMessage(text: String, at: String? = null, reply: String? = null, messageId: Int = 12): String =
        buildString {
            append("""{"time":1515204254,"self_id":10001000,"post_type":"message","message_type":"group",""")
            append(""""sub_type":"normal","message_id":$messageId,"group_id":987654,"user_id":12345678,"message":[""")
            at?.let { append("""{"type":"at","data":{"qq":$it}},""") }
            reply?.let { append("""{"type":"reply","data":{"id":$it}},""") }
            append("""{"type":"text","data":{"text":"$text"}}],""")
            append(""""raw_message":"$text",""")
            append(""""sender":{"user_id":12345678,"nickname":"someone","role":"member"}}""")
        }

    /** Whether the agent was asked for no turn within the time one would arrive in. */
    private suspend fun awaitNoSubmission(submitter: RecordingTurnSubmitter): Boolean {
        val deadline = System.nanoTime() + 500_000_000L
        while (System.nanoTime() < deadline) {
            if (submitter.submissions.isNotEmpty()) return false
            kotlinx.coroutines.delay(20)
        }
        return true
    }

    /** An implementation that serves the API and reports what the test tells it to. */
    private class ImplementationPeer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        private val started = CountDownLatch(1)
        private val connected = CountDownLatch(1)
        private val calls = CopyOnWriteArrayList<String>()
        private val callWaiters = CopyOnWriteArrayList<Pair<String, CountDownLatch>>()

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
            calls += message
            val echo = message.substringAfter("\"echo\":\"", "").substringBefore('"')
            if (echo.isEmpty()) return
            val refused = failing.firstOrNull { message.contains("\"$it\"") }?.also { failing.remove(it) }
            if (refused != null) {
                connection.send("""{"status":"failed","retcode":1,"data":null,"echo":"$echo"}""")
                return
            }
            connection.send(
                """{"status":"ok","retcode":0,"data":{"message_id":7},"echo":"$echo"}""",
            )
        }

        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
            if (socket === connection) socket = null
        }

        override fun onError(connection: WebSocket?, ex: Exception) = Unit

        /** Starts listening and returns the port this peer took. */
        fun startAndPort(): Int {
            start()
            check(started.await(5, TimeUnit.SECONDS)) { "the peer did not start" }
            return port
        }

        /** Reports [json] as an event of the implementation. */
        fun report(json: String) {
            val connection = socket ?: error("nothing is connected")
            connection.send(json)
        }

        /** The actions whose next call the peer answers with a failure, which a test sets to make one fail. */
        private val failing = CopyOnWriteArrayList<String>()

        /** Answers the next call of [action] with a failure, once. */
        fun failNext(action: String) {
            failing += action
        }

        /** Waits for the channel to connect and returns whether it did. */
        fun awaitConnection(): Boolean = connected.await(5, TimeUnit.SECONDS)

        /** How many calls of [action] the implementation was sent. */
        fun callsOf(action: String): Int = calls.count { it.contains("\"$action\"") }

        /** Waits for a call of [action] and returns it, whether it arrived before or after this call. */
        fun awaitCall(action: String): String {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                calls.lastOrNull { it.contains("\"$action\"") }?.let { return it }
                Thread.sleep(20)
            }
            error("no call of $action arrived, saw $calls")
        }
    }
}
