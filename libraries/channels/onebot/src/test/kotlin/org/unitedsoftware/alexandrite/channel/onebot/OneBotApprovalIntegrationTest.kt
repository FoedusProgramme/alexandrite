package org.unitedsoftware.alexandrite.channel.onebot

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.PluginHarness.Running
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Approvals inside a real runtime.
 *
 * A peer built from the library serves the implementation, and the test drives what an admin would do: the approval is
 * shown as a message, and answering it means replying to that message or writing the id of the request.
 */
class OneBotApprovalIntegrationTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")

    private val directory = key<ChannelDirectory>()
    private val group = ChatAddress(instance, "group:987654")

    @Test
    fun `an approval is answered by replying to the message that carried it`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port) {
                    val asking = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("k3x9")) }
                    val carried = waiting { peer.awaitSent(1) }.last().id
                    peer.report(command(replyTo = carried, text = "/approve"))
                    val answer = assertIs<ApprovalAnswer.Allowed>(asking.await())
                    assertTrue(answer.by.isOperator, "the answer did not name an admin")
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `an approval the platform refuses once is still shown`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port) {
                    peer.refuseNext()
                    val asking = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("retry1")) }
                    val carried = waiting { peer.awaitSent(1) }.last().id
                    peer.report(command(replyTo = carried, text = "/approve"))
                    assertIs<ApprovalAnswer.Allowed>(asking.await())
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `an approval no chat takes is answered with what the platform said`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port) {
                    peer.refuseNext(times = 3)
                    val asking = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("retry2")) }
                    val answer = assertIs<ApprovalAnswer.Unavailable>(asking.await())
                    assertTrue(
                        answer.reason.contains("the platform took no message of it"),
                        "the reason did not name the platform: ${answer.reason}",
                    )
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `two requests waiting in one chat are told apart by the message each came in`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port) {
                    val first = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("aaa111")) }
                    val second = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("bbb222")) }
                    val sent = waiting { peer.awaitSent(2) }
                    val carriedBySecond = sent.last().id
                    peer.report(command(replyTo = carriedBySecond, text = "/approve"))
                    assertIs<ApprovalAnswer.Allowed>(second.await())
                    assertTrue(!first.isCompleted, "answering one request answered the other")
                    peer.report(command(replyTo = sent.first().id, text = "/deny"))
                    assertIs<ApprovalAnswer.Denied>(first.await())
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `an id that names several requests answers none of them`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port) {
                    val first = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("aaa111")) }
                    val second = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("aaa222")) }
                    waiting { peer.awaitSent(2) }
                    peer.report(command(text = "/approve aaa"))
                    val said = waiting { peer.awaitSent(3) }.last().params
                    assertTrue(
                        said.contains("names no one request"),
                        "an ambiguous id was not refused: $said",
                    )
                    assertTrue(!first.isCompleted && !second.isCompleted, "an ambiguous id answered a request")
                    // The whole id answers the one it names.
                    peer.report(command(text = "/approve aaa222"))
                    assertIs<ApprovalAnswer.Allowed>(second.await())
                    peer.report(command(text = "/deny aaa111"))
                    assertIs<ApprovalAnswer.Denied>(first.await())
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `somebody who is no admin of the instance answers nothing`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port) {
                    val asking = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("k3x9")) }
                    val carried = waiting { peer.awaitSent(1) }.last().id
                    peer.report(command(replyTo = carried, text = "/approve", from = "99999999", role = "member"))
                    val said = waiting { peer.awaitSent(2) }.last().params
                    assertTrue(
                        said.contains("Only an admin"),
                        "somebody who is no admin was answered: $said",
                    )
                    assertTrue(!asking.isCompleted, "somebody who is no admin answered a request")
                    peer.report(command(replyTo = carried, text = "/approve"))
                    assertIs<ApprovalAnswer.Allowed>(asking.await())
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `an approval of a member is not sent to a chat that never paired`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port, allowedChats = listOf("group:987654")) {
                    val answer = front().askApproval(requestOfMember("m1x2"))
                    assertIs<ApprovalAnswer.Unavailable>(answer)
                    assertTrue(
                        waiting { peer.awaitQuiet() }.isEmpty(),
                        "an approval reached a chat that never paired",
                    )
                }
            }
        } finally {
            peer.stop()
        }
    }

    @Test
    fun `an answer that is taken is said once`() {
        val peer = ApprovalPeer()
        val port = peer.startAndPort()
        try {
            runBlocking {
                harness(peer, port) {
                    val asking = async(start = CoroutineStart.UNDISPATCHED) { front().askApproval(request("k3x9")) }
                    val carried = waiting { peer.awaitSent(1) }.last().id
                    peer.report(command(replyTo = carried, text = "/approve"))
                    assertIs<ApprovalAnswer.Allowed>(asking.await())
                    // The request, and the line that says how it ended. The answer is not said a second time in the
                    // chat the command was written in, which is what an earlier version of this did.
                    val sent = waiting { peer.awaitQuiet() }
                    assertEquals(2, sent.size, "an answer that was taken was said more than once: $sent")
                    assertTrue(
                        sent.last().params.contains("is allowed by"),
                        "the outcome of the answer was not said: ${sent.last().params}",
                    )
                }
            }
        } finally {
            peer.stop()
        }
    }

    /** Runs [block] with the plugin built over the peer on [port], serving [allowedChats]. */
    private suspend fun harness(
        peer: ApprovalPeer,
        port: Int,
        allowedChats: List<String> = listOf("group:987654", "private:12345678"),
        block: suspend Running.() -> Unit,
    ) {
        PluginHarness.builder(AlexandriteChannelOnebotIndex())
            .config(config(port, allowedChats))
            .turnSubmitter(RecordingTurnSubmitter())
            .build()
            .run {
                assertTrue(waiting { peer.awaitConnection() }, "the channel did not connect to the implementation")
                block()
            }
    }

    /** Waits for [block], which blocks its thread, without holding the one the test runs on. */
    private suspend fun <T> waiting(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** The channel of the instance under test, which is not the recording channel of the harness. */
    private suspend fun Running.front(): Channel =
        get(directory).channel(instance) ?: error("the instance has no channel")

    /** An approval request of [id] that the user of the group asks for. */
    private fun request(id: String): ApprovalRequest = ApprovalRequest.builder(
        id = id,
        turn = testTurn(chat = group) { actor(admin) },
        tool = "fs.write",
        risk = ToolRisk.WORKSPACE_WRITE,
        arguments = """{"path":"notes.md"}""",
        reason = "the chat is in ask mode",
    ).build()

    /** An approval request of [id] that a member of the group asks for, which no operator's chat can answer. */
    private fun requestOfMember(id: String): ApprovalRequest = ApprovalRequest.builder(
        id = id,
        turn = testTurn(chat = group) { actor(member) },
        tool = "fs.write",
        risk = ToolRisk.WORKSPACE_WRITE,
        arguments = """{"path":"notes.md"}""",
        reason = "the chat is in ask mode",
    ).build()

    /** A report of the group that answers the message [replyTo] with [text], sent by [from]. */
    private fun command(replyTo: String? = null, text: String, from: String = USER, role: String = "admin"): String =
        buildString {
            append("""{"time":1515204254,"self_id":10001000,"post_type":"message","message_type":"group",""")
            append(
                """"sub_type":"normal","message_id":${nextReport.incrementAndGet()},"group_id":987654,"user_id":$from,"message":[""",
            )
            replyTo?.let { append("""{"type":"reply","data":{"id":"$it"}},""") }
            append("""{"type":"text","data":{"text":"$text"}}],""")
            append(""""raw_message":"$text",""")
            append(""""sender":{"user_id":$from,"nickname":"someone","role":"$role"}}""")
        }

    private val nextReport = AtomicInteger(5000)

    private val admin: ChatUser =
        ChatUser(UserAddress(instance, USER), "someone", null, isBot = false, isOperator = true)

    private val member: ChatUser =
        ChatUser(UserAddress(instance, MEMBER), "someone", null, isBot = false, isOperator = false)

    private fun config(port: Int, allowedChats: List<String>): String = """
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
              "allowedChats": [${allowedChats.joinToString(",") { "\"$it\"" }}],
              "admins": ["$USER"],
              "approvalMinutes": 1
            }
          }
        }
    """.trimIndent()

    private companion object {
        const val USER = "12345678"

        const val MEMBER = "99999999"
    }

    /** An implementation that serves the API, numbering the messages it is told to send, and reports what the test says. */
    private class ApprovalPeer : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
        private val started = CountDownLatch(1)
        private val connected = CountDownLatch(1)
        private val counter = AtomicInteger(100)
        private val sends = CopyOnWriteArrayList<Sent>()
        private val refusals = AtomicInteger(0)

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
            // An answer carries the message id of what was sent, which is what the test answers a request by.
            val sending = !message.contains("\"get_login_info\"") && !message.contains("\"get_msg\"")
            val id = if (sending) "${counter.incrementAndGet()}" else "${counter.get()}"
            if (sending && refusals.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                // A message the platform refuses is not one it took, so it is not among those a test waits for.
                connection.send("""{"status":"failed","retcode":1,"data":null,"echo":"$echo"}""")
                return
            }
            if (sending) sends += Sent(id, message)
            connection.send("""{"status":"ok","retcode":0,"data":{"message_id":$id},"echo":"$echo"}""")
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

        /** Makes the next [times] messages the peer takes be refused, as a platform that will not take them does. */
        fun refuseNext(times: Int = 1) {
            refusals.set(times)
        }

        fun awaitConnection(): Boolean = connected.await(5, TimeUnit.SECONDS)

        /** Waits until at least [count] messages were sent, and returns everything sent so far. */
        fun awaitSent(count: Int): List<Sent> {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                if (sends.size >= count) return sends.toList()
                Thread.sleep(20)
            }
            error("only ${sends.size} messages were sent, wanted $count: $sends")
        }

        /** Waits a moment for anything else to arrive, and returns everything that was sent. */
        fun awaitQuiet(millis: Long = 600): List<Sent> {
            Thread.sleep(millis)
            return sends.toList()
        }

        class Sent(val id: String, val params: String)
    }
}
