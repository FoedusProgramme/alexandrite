package org.unitedsoftware.alexandrite.channel.qqbot

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDecision
import org.unitedsoftware.alexandrite.channel.qqbot.channel.QQ_BOT_MAX_FILE_BYTES
import org.unitedsoftware.alexandrite.channel.qqbot.pairing.QqBotPairingDesk
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeGatewayServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The QQ bot channel inside a real runtime.
 *
 * A harness builds the plugin set and the container, a fake platform serves the token and the gateway, and the test
 * drives what the platform would do: it pushes a message of a single chat, the agent is asked for a turn, and the
 * answer of that turn goes back to the fake platform as a reply to the message.
 */
class QqBotChannelIntegrationTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")

    private val directory = key<ChannelDirectory>()

    @Test
    fun `a message the platform pushes reaches the agent and its answer goes back as a reply`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(EVENT)
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message
                        assertEquals("private:u1", message.chat.chat)
                        assertEquals("hello", message.text)

                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val sink = channel.openReply(ReplyRequest(testTurn(chat = message.chat), message.ref))
                        val answer = OutboundMessage.builder("hello back", MessageKind.REPLY).build()
                        assertIs<Delivery.Delivered>(sink.complete(answer))

                        val sent = api.calls(SEND_DIRECT).first { "\"msg_type\":0" in it.body }
                        assertTrue("\"content\":\"hello back\"" in sent.body, sent.body)
                        assertTrue("\"msg_id\":\"msg1\"" in sent.body, sent.body)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `only a configured admin is an operator of the instance`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        val platform = FakeGatewayServer().startAndAwait()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, admins = setOf("u1")))
                    .turnSubmitter(RecordingTurnSubmitter())
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val chat = ChatAddress(instance, "private:u1")
                        assertTrue(channel.isOperator(chat, UserAddress(instance, "u1")))
                        assertFalse(channel.isOperator(chat, UserAddress(instance, "u2")))
                        val other = ChannelInstanceId(ChannelType("qqbot"), "other")
                        assertFalse(channel.isOperator(chat, UserAddress(other, "u1")))
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `whoami tells the person the address the instance knows them by`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"who1"}""")
        val platform = FakeGatewayServer().startAndAwait()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(RecordingTurnSubmitter())
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(WHOAMI_EVENT)
                        val answer = assertNotNull(api.awaitRequest(SEND_DIRECT))
                        assertTrue("private:u1" in answer.body, answer.body)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a full-mode group message that names the bot reaches the agent`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(ME_PATH, """{"id":"bot1","username":"YosBot"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(FULL_MODE_EVENT)
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message

                        assertEquals("group:g1", message.chat.chat)
                        assertEquals("hello", message.text)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a failed bot-state lookup is tried again`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(ME_PATH, """{"id":"global-bot-id"}""")
        val calls = AtomicInteger()
        api.on(BOT_STATE_PATH) {
            if (calls.incrementAndGet() == 1) {
                PlatformAnswer(500, """{"err_code":500,"message":"temporary"}""")
            } else {
                PlatformAnswer(200, """{"member_openid":"member-bot-id"}""")
            }
        }
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(FULL_MODE_MENTION_EVENT)
                        delay(500)
                        assertTrue(submitter.submissions.isEmpty(), "the first lookup should not reach the agent")
                        platform.push(
                            FULL_MODE_MENTION_EVENT
                                .replace("msg-full-mention", "msg-full-mention-2")
                                .replace("\"s\":4", "\"s\":5"),
                        )
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message
                        assertEquals("hello", message.text)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a full-mode group message names the bot by its member openid when the mention list does not`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(ME_PATH, """{"id":"global-bot-id"}""")
        api.on(BOT_STATE_PATH, """{"member_openid":"member-bot-id"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(FULL_MODE_MENTION_EVENT)
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message

                        assertEquals("group:g1", message.chat.chat)
                        assertEquals("hello", message.text)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Test
    fun `a reply in a single chat is streamed into one message and ended there`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(STREAM_DIRECT, """{"id":"stream1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(EVENT)
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message

                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        assertTrue(channel.capabilities(message.chat).streaming, "a single chat streams")
                        assertTrue(channel.capabilities(message.chat).files, "a message may carry files")
                        assertEquals(QQ_BOT_MAX_FILE_BYTES, channel.capabilities(message.chat).maxFileBytes)
                        val sink = channel.openReply(ReplyRequest(testTurn(chat = message.chat), message.ref))
                        sink.event(
                            TurnEvent.ToolCallStarted(
                                ToolCallId("call1"),
                                "web.search",
                                ToolActivity.SEARCH,
                                "搜索资料",
                            ),
                        )
                        sink.preview(0, "hel")
                        sink.preview(0, "hello")
                        val answer = OutboundMessage.builder("hello there", MessageKind.REPLY)
                            .markup(Markup.MARKDOWN)
                            .build()
                        assertIs<Delivery.Delivered>(sink.complete(answer))

                        val pieces = api.calls(STREAM_DIRECT)
                        assertEquals(5, pieces.size)
                        assertTrue("\"content_raw\":\"⏳ web.search — 搜索资料\"" in pieces[0].body, pieces[0].body)
                        assertTrue("\"input_state\":1" in pieces[0].body, pieces[0].body)
                        assertTrue("hel" in pieces[1].body, pieces[1].body)
                        assertTrue("\"stream_msg_id\":\"stream1\"" in pieces[2].body, pieces[2].body)
                        assertTrue(" there" in pieces[3].body, pieces[3].body)
                        assertTrue("\"input_state\":10" in pieces[4].body, pieces[4].body)
                        assertTrue(
                            api.calls(SEND_DIRECT).none { "\"msg_type\":6" !in it.body },
                            "the answer was sent as an ordinary message too",
                        )
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a reply that starts a new segment keeps one streaming message`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(STREAM_DIRECT, """{"id":"stream1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(RecordingTurnSubmitter())
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(EVENT)
                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val chat = ChatAddress(instance, "private:u1")
                        val sink = channel.openReply(
                            ReplyRequest(testTurn(chat = chat), ChannelMessageRef(chat, "msg1")),
                        )
                        sink.preview(0, "first")
                        sink.startSegment(1)
                        sink.preview(1, "second")
                        val answer = OutboundMessage.builder("answer", MessageKind.REPLY).build()
                        assertIs<Delivery.Delivered>(sink.complete(answer))

                        val pieces = api.calls(STREAM_DIRECT)
                        assertEquals(4, pieces.size)
                        assertTrue("\"stream_msg_id\":\"stream1\"" in pieces[1].body, pieces[1].body)
                        assertTrue("\"stream_msg_id\":\"stream1\"" in pieces[2].body, pieces[2].body)
                        assertTrue("\"input_state\":10" in pieces[3].body, pieces[3].body)
                        assertTrue(api.calls(SEND_DIRECT).none { "\"msg_type\":6" !in it.body })
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a streamed answer with a file sends the file and closes the stream`() = runBlocking<Unit> {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(STREAM_DIRECT, """{"id":"stream1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        api.on("/v2/users/u1/files", """{"file_info":"FI","ttl":300}""")
        api.on(SEND_DIRECT, """{"id":"media1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            PluginHarness.builder(AlexandriteChannelQqbotIndex())
                .config(config(api.base, platform.url))
                .turnSubmitter(submitter)
                .build()
                .run {
                    assertTrue(platform.awaitConnection(), "the gateway was not connected")
                    val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                    val chat = ChatAddress(instance, "private:u1")
                    val sink = channel.openReply(ReplyRequest(testTurn(chat = chat), ChannelMessageRef(chat, "msg1")))
                    sink.preview(0, "draft")
                    val file = OutboundFile.builder("a.png", "image/png", "hello".toByteArray()).build()
                    val delivery = sink.complete(
                        OutboundMessage.builder("a caption", MessageKind.REPLY).files(listOf(file)).build(),
                    )

                    assertIs<Delivery.Delivered>(delivery)
                    assertTrue(api.calls(STREAM_DIRECT).any { "\"input_state\":10" in it.body })
                    assertEquals(1, api.calls("/v2/users/u1/files").size)
                    assertTrue(api.calls(SEND_DIRECT).any { "\"msg_type\":7" in it.body })
                }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Test
    fun `an approval is asked with buttons and answered by a press`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        val sendReached = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        api.on(SEND_DIRECT) {
            sendReached.countDown()
            check(releaseSend.await(5, TimeUnit.SECONDS)) { "the test did not release the approval send" }
            PlatformAnswer(200, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        }
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, admins = setOf("u1")))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val chat = ChatAddress(instance, "private:u1")
                        val request = ApprovalRequest.builder(
                            "req1",
                            testTurn(chat = chat),
                            "command.exec",
                            ToolRisk.EXEC,
                            "{}",
                            "it runs a command",
                        ).alwaysRules(
                            listOf(
                                RuleSubject("command.exec", listOf("a"), "host a.example"),
                                RuleSubject("command.exec", listOf("b"), "host b.example"),
                            ),
                        ).build()
                        val waiting = async(start = CoroutineStart.UNDISPATCHED) { channel.askApproval(request) }
                        assertTrue(sendReached.await(5, TimeUnit.SECONDS), "the approval message was not sent")
                        val shown = assertNotNull(api.awaitRequest(SEND_DIRECT))
                        assertTrue("\"msg_type\":2" in shown.body, shown.body)
                        assertTrue("\"markdown\"" in shown.body, shown.body)
                        assertTrue("\"keyboard\"" in shown.body, shown.body)
                        assertTrue("\"data\":\"/approve req1\"" in shown.body, shown.body)
                        assertTrue("\"data\":\"/deny req1\"" in shown.body, shown.body)
                        assertTrue("\"data\":\"/approve req1 always\"" in shown.body, shown.body)
                        assertTrue("host a.example" in shown.body, shown.body)
                        assertTrue("host b.example" in shown.body, shown.body)
                        assertTrue("\"enter\":true" in shown.body, shown.body)

                        platform.push(APPROVAL_PRESS)
                        // Let the inbox handle the press while sendApproval is still waiting for its answer. The
                        // recipient has to be known before this point; otherwise the press is silently dropped.
                        Thread.sleep(100)
                        releaseSend.countDown()
                        val answer = withTimeout(10.seconds) { waiting.await() }
                        assertIs<ApprovalAnswer.Allowed>(answer)
                    }
            }
        } finally {
            releaseSend.countDown()
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `an approval can be denied by a press`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, admins = setOf("u1")))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val chat = ChatAddress(instance, "private:u1")
                        val request = ApprovalRequest.builder(
                            "req1",
                            testTurn(chat = chat),
                            "command.exec",
                            ToolRisk.EXEC,
                            "{}",
                            "it runs a command",
                        ).alwaysRules(
                            listOf(
                                RuleSubject("command.exec", listOf("a"), "host a.example"),
                                RuleSubject("command.exec", listOf("b"), "host b.example"),
                            ),
                        ).build()
                        val waiting = async(start = CoroutineStart.UNDISPATCHED) { channel.askApproval(request) }
                        assertNotNull(api.awaitRequest(SEND_DIRECT))
                        platform.push(APPROVAL_DENY)
                        assertIs<ApprovalAnswer.Denied>(withTimeout(10.seconds) { waiting.await() })
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a command reaches the agent as a command rather than as a message`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(COMMAND_EVENT)
                        val command = assertIs<Submission.Command>(awaitSubmission(submitter))
                        assertEquals("model", command.invocation.name)
                        assertEquals("deepseek/deepseek-flash", command.invocation.arguments)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    private suspend fun awaitSubmission(submitter: RecordingTurnSubmitter): Submission {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            submitter.submissions.firstOrNull()?.let { return it }
            delay(20)
        }
        error("the agent was asked for no turn")
    }

    @Test
    fun `a detached background run keeps writing to the reply's stream until it closes`() = runBlocking<Unit> {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(STREAM_DIRECT, """{"id":"stream1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            PluginHarness.builder(AlexandriteChannelQqbotIndex())
                .config(config(api.base, platform.url))
                .turnSubmitter(submitter)
                .build()
                .run {
                    assertTrue(platform.awaitConnection(), "the gateway was not connected")
                    platform.push(EVENT)
                    val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message
                    val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                    val sink = channel.openReply(ReplyRequest(testTurn(chat = message.chat), message.ref))
                    val call = ToolCallId("delegate1")
                    sink.event(TurnEvent.ToolCallStarted(call, "agent.delegate", ToolActivity.DELEGATE, "general"))
                    val activity = assertNotNull(
                        sink.detachActivity(DelegatedRun(call, RunId("r1"), AgentId("general"))),
                    )
                    activity.event(
                        TurnEvent.ToolCallStarted(
                            ToolCallId("child1"),
                            "fs.read",
                            ToolActivity.READ,
                            "a file",
                            RunId("r1"),
                            AgentId("general"),
                        ),
                    )
                    assertIs<Delivery.Delivered>(
                        sink.complete(OutboundMessage.builder("done", MessageKind.REPLY).build()),
                    )
                    assertTrue(
                        api.calls(STREAM_DIRECT).none { "\"input_state\":10" in it.body },
                        "the stream closed while a background run still wrote to it",
                    )
                    activity.close(ToolCallStatus.COMPLETED)
                    val last = api.calls(STREAM_DIRECT).last()
                    assertTrue("\"input_state\":10" in last.body, last.body)
                }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `an unpaired chat is offered a code and reaches the agent only once approved`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, allowedChats = emptyList()))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(EVENT)
                        assertTrue(awaitNoSubmission(submitter), "an unpaired chat reached the agent")
                        assertNotNull(api.awaitRequest(SEND_DIRECT), "the chat was not offered a pairing code")

                        val desk = get(key<QqBotPairingDesk>())
                        val request = desk.pending().single()
                        assertEquals(ChatAddress(instance, "private:u1"), request.chat)
                        assertIs<PairingDecision.Approved>(desk.approve(request.code, null))

                        platform.push(EVENT.replace("msg1", "msg1b").replace("\"s\":2", "\"s\":3"))
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message
                        assertEquals("hello", message.text)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `an unpaired chat's command is ignored and offered a code, like any other message`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, allowedChats = emptyList()))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(COMMAND_EVENT)
                        assertTrue(awaitNoSubmission(submitter), "an unpaired chat's command reached the agent")
                        val offer = assertNotNull(api.awaitRequest(SEND_DIRECT), "the chat was not offered a code")
                        assertTrue(CODE.containsMatchIn(offer.body), offer.body)
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `an unpaired chat's approval press is ignored rather than answered`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, allowedChats = emptyList()))
                    .turnSubmitter(RecordingTurnSubmitter())
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(APPROVAL_PRESS)
                        val offer = assertNotNull(api.awaitRequest(SEND_DIRECT), "the chat was not offered a code")
                        assertTrue(CODE.containsMatchIn(offer.body), offer.body)
                        delay(300)
                        assertEquals(
                            1,
                            api.calls(SEND_DIRECT).size,
                            "the press was answered although the chat is not paired",
                        )
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a member other than an admin is refused a link code`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(RecordingTurnSubmitter())
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(LINK_ISSUE_EVENT)
                        val said = assertNotNull(api.awaitRequest(SEND_DIRECT))
                        assertFalse(CODE.containsMatchIn(said.body), "a non-admin was given a link code: ${said.body}")
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @Test
    fun `a wrong code is rejected rather than linking anybody`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_GROUP, """{"id":"sent2","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, admins = setOf("u1")))
                    .turnSubmitter(RecordingTurnSubmitter())
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val group = ChatAddress(instance, "group:g1")
                        val member = UserAddress(instance, "member1")

                        platform.push(LINK_REDEEM_EVENT.replace("CODE", "0000-0000"))
                        val said = assertNotNull(api.awaitRequest(SEND_GROUP))
                        assertTrue("unknown" in said.body || "valid" in said.body, said.body)
                        assertFalse(channel.isOperator(group, member), "a wrong code must not link anybody")
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Test
    fun `a group's own owner role grants no operator rights, only admins and links do`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(ME_PATH, """{"id":"bot1","username":"YosBot"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(OWNER_ROLE_EVENT)
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message
                        assertFalse(message.sender.isOperator, "the group's own owner role grants nothing by itself")

                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val group = ChatAddress(instance, "group:g1")
                        assertFalse(channel.isOperator(group, UserAddress(instance, "u1")))
                        assertFalse(channel.isOperator(group, message.sender.address))
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Test
    fun `an admin's own single chat agrees with the channel on who operates it`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, admins = setOf("u1")))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        platform.push(EVENT)
                        val message = assertIs<Submission.Message>(awaitSubmission(submitter)).message
                        assertTrue(message.sender.isOperator)

                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        assertTrue(channel.isOperator(message.chat, message.sender.address))
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    @OptIn(InternalAlexandriteApi::class)
    @Test
    fun `an admin links their group-scoped id to themselves, there alone, and may later unlink it`() {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        api.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        api.on(SEND_GROUP, """{"id":"sent2","timestamp":"2026-07-21T10:00:00+08:00"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val submitter = RecordingTurnSubmitter()
        try {
            runBlocking {
                PluginHarness.builder(AlexandriteChannelQqbotIndex())
                    .config(config(api.base, platform.url, admins = setOf("u1")))
                    .turnSubmitter(submitter)
                    .build()
                    .run {
                        assertTrue(platform.awaitConnection(), "the gateway was not connected")
                        val channel = get(directory).channel(instance) ?: error("the instance has no channel")
                        val group = ChatAddress(instance, "group:g1")
                        val member = UserAddress(instance, "member1")

                        platform.push(LINK_ISSUE_EVENT)
                        val issued = assertNotNull(api.awaitRequest(SEND_DIRECT, timeoutMillis = 2_000))
                        val code = assertNotNull(CODE.find(issued.body), "no code in: ${issued.body}").value

                        assertFalse(channel.isOperator(group, member), "no code was spent for this member yet")
                        platform.push(LINK_REDEEM_EVENT.replace("CODE", code))
                        assertNotNull(api.awaitRequest(SEND_GROUP, timeoutMillis = 2_000))
                        assertTrue(channel.isOperator(group, member), "the alias now operates this instance in g1")
                        assertFalse(
                            channel.isOperator(ChatAddress(instance, "group:g2"), member),
                            "the alias operates the instance in g1 alone",
                        )

                        platform.push(LINKS_EVENT)
                        val listed = awaitRequestMatching(api, SEND_DIRECT) { "group:g1" in it.body }
                        assertTrue("member1" in listed.body, listed.body)

                        val before = api.calls(SEND_DIRECT).size
                        platform.push(UNLINK_EVENT)
                        awaitMoreRequests(api, SEND_DIRECT, before)
                        assertFalse(channel.isOperator(group, member), "the link was revoked")
                    }
            }
        } finally {
            platform.stop()
            api.close()
        }
    }

    /** The next call of [path] that [predicate] takes, waiting for it at most 2 seconds. */
    private suspend fun awaitRequestMatching(
        api: FakeApiServer,
        path: String,
        predicate: (org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformRequest) -> Boolean,
    ): org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformRequest {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            api.calls(path).lastOrNull { predicate(it) }?.let { return it }
            delay(20)
        }
        error("no call of $path matched within the wait")
    }

    /** Waits at most 2 seconds for [path] to have more calls than [before]. */
    private suspend fun awaitMoreRequests(api: FakeApiServer, path: String, before: Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (api.calls(path).size > before) return
            delay(20)
        }
        error("no new call of $path arrived within the wait")
    }

    /** Whether the agent was asked for no turn within the time one would arrive in. */
    private suspend fun awaitNoSubmission(submitter: RecordingTurnSubmitter): Boolean {
        val deadline = System.nanoTime() + 500_000_000L
        while (System.nanoTime() < deadline) {
            if (submitter.submissions.isNotEmpty()) return false
            delay(20)
        }
        return true
    }

    private fun config(
        apiBase: String,
        gatewayUrl: String,
        admins: Set<String> = emptySet(),
        allowedChats: List<String> = listOf("private:u1", "group:g1"),
    ): String = """
        {
          "instances": {
            "main": {
              "appId": "10000",
              "appSecret": "secret",
              "apiBase": "$apiBase",
              "gatewayUrl": "$gatewayUrl",
              "admins": [${admins.joinToString(",") { "\"$it\"" }}],
              "allowedChats": [${allowedChats.joinToString(",") { "\"$it\"" }}],
              "connectTimeoutMillis": 5000,
              "firstByteTimeoutMillis": 5000,
              "idleTimeoutMillis": 5000,
              "reconnectIntervalMillis": 200
            }
          }
        }
    """.trimIndent()

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"

        const val ME_PATH = "/users/@me"

        const val BOT_STATE_PATH = "/v2/groups/g1/bot_state"

        const val SEND_DIRECT = "/v2/users/u1/messages"

        const val SEND_GROUP = "/v2/groups/g1/messages"

        const val STREAM_DIRECT = "/v2/users/u1/stream_messages"

        /** The code an open platform pairing or link message carries, two groups of four characters. */
        val CODE = Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")

        const val APPROVAL_PRESS = """{"id":"event-approve","op":0,"s":2,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg-approve","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"/approve req1","message_type":0,"timestamp":"2026-07-21T10:00:00+08:00"}}"""

        const val APPROVAL_DENY = """{"id":"event-deny","op":0,"s":2,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg-deny","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"/deny req1","message_type":0,"timestamp":"2026-07-21T10:00:00+08:00"}}"""

        const val COMMAND_EVENT = """{"id":"event-command","op":0,"s":3,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg-command","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"/model deepseek/deepseek-flash","message_type":0,"timestamp":"2026-07-21T10:00:01+08:00"}}"""

        const val EVENT = """{"id":"event1","op":0,"s":2,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg1","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"hello","message_type":0,"timestamp":"2026-07-21T10:00:00+08:00"}}"""

        const val WHOAMI_EVENT = """{"id":"event-who","op":0,"s":2,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg-who","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"/whoami","message_type":0,"timestamp":"2026-07-21T10:00:02+08:00"}}"""

        const val FULL_MODE_EVENT = """{"id":"event-full","op":0,"s":3,"t":"GROUP_MESSAGE_CREATE","d":""" +
            """{"id":"msg-full","author":{"id":"u1","member_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"<@bot1> hello","group_openid":"g1","message_type":0,""" +
            """"mentions":[{"id":"bot1","member_openid":"bot1","bot":true}],""" +
            """"timestamp":"2026-07-21T10:00:02+08:00"}}"""

        const val FULL_MODE_MENTION_EVENT = """{"id":"event-full-mention","op":0,"s":4,""" +
            """"t":"GROUP_MESSAGE_CREATE","d":{"id":"msg-full-mention",""" +
            """"author":{"id":"u1","member_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"<@member-bot-id> hello","group_openid":"g1","message_type":0,""" +
            """"timestamp":"2026-07-21T10:00:03+08:00"}}"""

        /** A full-mode group message whose author's platform role is the group's owner, who is not an admin. */
        const val OWNER_ROLE_EVENT = """{"id":"event-owner","op":0,"s":5,"t":"GROUP_MESSAGE_CREATE","d":""" +
            """{"id":"msg-owner","author":{"id":"u1","member_openid":"u1","member_role":"owner","username":"Ada",""" +
            """"bot":false},"content":"<@bot1> hello","group_openid":"g1","message_type":0,""" +
            """"mentions":[{"id":"bot1","member_openid":"bot1","bot":true}],""" +
            """"timestamp":"2026-07-21T10:00:04+08:00"}}"""

        /** The admin u1, in their own single chat, asks for a link code. */
        const val LINK_ISSUE_EVENT = """{"id":"event-link-issue","op":0,"s":6,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg-link-issue","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"/link","message_type":0,"timestamp":"2026-07-21T10:00:05+08:00"}}"""

        /** Member1 of group g1, who is no admin, spends a code; `CODE` is replaced with the one the admin got. */
        const val LINK_REDEEM_EVENT = """{"id":"event-link-redeem","op":0,"s":7,"t":"GROUP_AT_MESSAGE_CREATE",""" +
            """"d":{"id":"msg-link-redeem","author":{"id":"m1","member_openid":"member1","username":"小明",""" +
            """"bot":false},"content":"/link CODE","group_openid":"g1","message_type":0,""" +
            """"timestamp":"2026-07-21T10:00:06+08:00"}}"""

        /** The admin u1 lists their own links. */
        const val LINKS_EVENT = """{"id":"event-links","op":0,"s":8,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg-links","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"/links","message_type":0,"timestamp":"2026-07-21T10:00:07+08:00"}}"""

        /** The admin u1 revokes their link of group g1. */
        const val UNLINK_EVENT = """{"id":"event-unlink","op":0,"s":9,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg-unlink","author":{"id":"u1","user_openid":"u1","username":"Ada","bot":false},""" +
            """"content":"/unlink group:g1","message_type":0,"timestamp":"2026-07-21T10:00:08+08:00"}}"""
    }
}
