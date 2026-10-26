package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QqBotSenderTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")
    private val group = ChatAddress(instance, "group:g1")
    private val direct = ChatAddress(instance, "private:u1")
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `a reply names the message it answers and counts its answers`() = runBlocking {
        val platform = Platform()
        try {
            val message = OutboundMessage.builder("hi", MessageKind.REPLY)
                .replyTo(ChannelMessageRef(group, "m1"))
                .build()
            assertIs<Delivery.Delivered>(platform.sender.send(group, message))
            assertIs<Delivery.Delivered>(platform.sender.send(group, message))

            val calls = platform.server.calls(SEND_GROUP)
            assertEquals(2, calls.size)
            assertTrue("\"msg_id\":\"m1\"" in calls[0].body, calls[0].body)
            assertTrue("\"msg_seq\":1" in calls[0].body, calls[0].body)
            assertTrue("\"msg_seq\":2" in calls[1].body, calls[1].body)
            assertEquals(
                "QQBot access",
                calls[0].headers.entries.firstOrNull {
                    it.key.equals("Authorization", true)
                }?.value,
            )
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a reply carries the reference the platform needs for a quote bar`() = runBlocking {
        val platform = Platform()
        try {
            platform.index.record(group, "REF1", "m1")
            val message = OutboundMessage.builder("hi", MessageKind.REPLY)
                .replyTo(ChannelMessageRef(group, "m1"))
                .build()
            assertIs<Delivery.Delivered>(platform.sender.send(group, message))

            val body = platform.server.calls(SEND_GROUP).single().body
            assertTrue("\"message_reference\":{\"message_id\":\"REF1\"}" in body, body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a message with a file sends its caption and the file as separate messages`() = runBlocking {
        val platform = Platform()
        platform.server.on(UPLOAD_GROUP, """{"file_info":"FI","ttl":300}""")
        platform.server.on(SEND_GROUP) { request ->
            PlatformAnswer(200, if ("\"msg_type\":7" in request.body) """{"id":"media1"}""" else """{"id":"text1"}""")
        }
        try {
            val file = OutboundFile.builder("a.png", "image/png", "hello".toByteArray()).build()
            val message = OutboundMessage.builder("a caption", MessageKind.REPLY).files(listOf(file)).build()

            val delivery = assertIs<Delivery.Delivered>(platform.sender.send(group, message))

            assertEquals(listOf("text1", "media1"), delivery.messages.map { it.id })
            val upload = platform.server.calls(UPLOAD_GROUP).single()
            assertTrue("\"file_type\":1" in upload.body, upload.body)
            assertTrue("\"file_data\":\"aGVsbG8=\"" in upload.body, upload.body)
            val messages = platform.server.calls(SEND_GROUP)
            assertEquals(
                2,
                messages.size,
                "the platform has no caption on a media message: ${messages.map {
                    it.body
                }}",
            )
            assertTrue("\"content\":\"a caption\"" in messages[0].body, messages[0].body)
            assertTrue("\"msg_type\":7" in messages[1].body, messages[1].body)
            assertTrue("\"file_info\":\"FI\"" in messages[1].body, messages[1].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a message with a file and no caption sends the file alone`() = runBlocking {
        val platform = Platform()
        platform.server.on(UPLOAD_GROUP, """{"file_info":"FI","ttl":300}""")
        platform.server.on(SEND_GROUP, """{"id":"media1"}""")
        try {
            val file = OutboundFile.builder("a.png", "image/png", "hello".toByteArray()).build()
            val message = OutboundMessage.builder("", MessageKind.REPLY).files(listOf(file)).build()

            val delivery = assertIs<Delivery.Delivered>(platform.sender.send(group, message))

            assertEquals(listOf("media1"), delivery.messages.map { it.id })
            val messages = platform.server.calls(SEND_GROUP)
            assertEquals(1, messages.size, "a blank caption sends no text message: ${messages.map { it.body }}")
            assertTrue("\"msg_type\":7" in messages[0].body, messages[0].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a message written as markdown goes as a markdown message`() = runBlocking {
        val platform = Platform()
        try {
            val message = OutboundMessage.builder("## hi", MessageKind.REPLY).markup(Markup.MARKDOWN).build()
            assertIs<Delivery.Delivered>(platform.sender.send(direct, message))

            val body = platform.server.calls(SEND_DIRECT).single().body
            assertTrue("\"msg_type\":2" in body, body)
            assertTrue("\"markdown\"" in body, body)
            assertFalse("\"msg_id\"" in body, "a message that answers nothing is an active one: $body")
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a reply the platform no longer takes is sent as an active message`() = runBlocking {
        val platform = Platform()
        platform.server.on(SEND_DIRECT) {
            if (it.body.contains("msg_id")) {
                PlatformAnswer(200, """{"err_code":40034005,"message":"回复消息msg_id已过期"}""")
            } else {
                PlatformAnswer(200, """{"id":"sent2","timestamp":"2026-07-21T10:00:00+08:00"}""")
            }
        }
        try {
            val message = OutboundMessage.builder("late", MessageKind.REPLY)
                .replyTo(ChannelMessageRef(direct, "m2"))
                .build()
            val delivery = platform.sender.send(direct, message)

            assertEquals("sent2", assertIs<Delivery.Delivered>(delivery).messages.single().id)
            val calls = platform.server.calls(SEND_DIRECT)
            assertEquals(2, calls.size)
            assertFalse("\"msg_id\"" in calls[1].body, calls[1].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a message the platform finds too long comes back as one a caller may shorten`() = runBlocking {
        val platform = Platform()
        platform.server.on(SEND_DIRECT, """{"err_code":40054007,"message":"消息长度超限"}""")
        try {
            val message = OutboundMessage.builder("long", MessageKind.REPLY).build()
            val delivery = platform.sender.send(direct, message)

            val failure = assertIs<Delivery.NotDelivered>(delivery)
            assertEquals(DeliveryFailure.TOO_LONG, failure.kind)
            assertTrue("消息长度超限" in failure.detail.orEmpty(), failure.detail)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a chat this channel serves none of is refused before any call`() = runBlocking {
        val platform = Platform()
        try {
            val message = OutboundMessage.builder("hi", MessageKind.REPLY).build()
            val delivery = platform.sender.send(ChatAddress(instance, "topic:1"), message)

            assertIs<Delivery.NotDelivered>(delivery)
            assertTrue(platform.server.requests().isEmpty(), "a chat of another channel reached the platform")
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a message the platform takes no markdown of is sent as plain text`() = runBlocking {
        val platform = Platform()
        platform.server.on(SEND_DIRECT) {
            if ("\"msg_type\":2" in it.body) {
                PlatformAnswer(200, """{"err_code":40034127,"message":"无markdown模板权限"}""")
            } else {
                PlatformAnswer(200, """{"id":"plain1","timestamp":"2026-07-21T10:00:00+08:00"}""")
            }
        }
        try {
            val message = OutboundMessage.builder("## hi", MessageKind.REPLY).markup(Markup.MARKDOWN).build()
            val delivery = platform.sender.send(direct, message)

            assertEquals("plain1", assertIs<Delivery.Delivered>(delivery).messages.single().id)
            val calls = platform.server.calls(SEND_DIRECT)
            assertEquals(2, calls.size)
            assertTrue("\"msg_type\":0" in calls[1].body, calls[1].body)
            assertTrue("\"content\":\"## hi\"" in calls[1].body, calls[1].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a single chat is streamed in one message the pieces replace`() = runBlocking {
        val platform = Platform()
        platform.server.on(STREAM_DIRECT, """{"id":"stream1","timestamp":"2026-07-21T10:00:00+08:00"}""")
        try {
            val first = platform.sender.stream(direct, "he", "m1", 1, null, 0, last = false)
            assertEquals("stream1", platform.sender.sentId(first))
            val second = platform.sender.stream(direct, "hello", "m1", 1, "stream1", 1, last = false)
            assertEquals("stream1", platform.sender.sentId(second))

            val calls = platform.server.calls(STREAM_DIRECT)
            assertEquals(2, calls.size)
            assertTrue("\"input_state\":1" in calls[0].body, calls[0].body)
            assertTrue("\"index\":0" in calls[0].body, calls[0].body)
            assertTrue("\"content_type\":\"markdown\"" in calls[0].body, calls[0].body)
            assertTrue("\"content_raw\":\"he\"" in calls[0].body, calls[0].body)
            assertTrue("\"msg_id\":\"m1\"" in calls[0].body, calls[0].body)
            assertTrue("\"stream_msg_id\"" !in calls[0].body, calls[0].body)
            assertTrue("\"stream_msg_id\":\"stream1\"" in calls[1].body, calls[1].body)
            assertTrue("\"index\":1" in calls[1].body, calls[1].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a group has no streaming endpoint so no piece is sent`() = runBlocking {
        val platform = Platform()
        try {
            val answer = platform.sender.stream(group, "he", "m1", 1, null, 0, last = false)

            assertIs<org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer.Failed>(answer)
            assertTrue(platform.server.calls(STREAM_GROUP).isEmpty(), "a group piece reached the platform")
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a single chat is told the bot is writing`() = runBlocking {
        val platform = Platform()
        platform.server.on(SEND_DIRECT) { PlatformAnswer(200, """{"id":"typing1"}""") }
        try {
            assertIs<Delivery.Delivered>(platform.sender.sendTyping(direct))
            val body = platform.server.calls(SEND_DIRECT).single().body
            assertTrue("\"msg_type\":6" in body, body)
            assertTrue("\"input_type\":1" in body, body)
            assertTrue("\"input_second\":60" in body, body)

            assertIs<Delivery.NotDelivered>(platform.sender.sendTyping(group))
            assertTrue(platform.server.calls(SEND_GROUP).isEmpty(), "a group was told of an input state")
        } finally {
            platform.close()
        }
    }

    /** A sender that talks to a fake platform, which answers the token and every message with a success. */
    private inner class Platform : AutoCloseable {
        val server = FakeApiServer()
        private val http: QqBotHttp
        val sender: QqBotSender
        val index = QqBotMessageIndex()

        init {
            server.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
            server.on(SEND_GROUP, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
            server.on(SEND_DIRECT, """{"id":"sent1","timestamp":"2026-07-21T10:00:00+08:00"}""")
            val config = testConfig(server.base)
            http = QqBotHttp(config)
            val tokens = QqBotTokens(config, http, clock)
            val api = QqBotApi(http, tokens)
            sender = QqBotSender(api, QqBotUploads(api, config), QqBotSentMessages(), index)
        }

        override fun close() {
            http.close()
            server.close()
        }
    }

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"
        const val SEND_GROUP = "/v2/groups/g1/messages"
        const val UPLOAD_GROUP = "/v2/groups/g1/files"
        const val SEND_DIRECT = "/v2/users/u1/messages"
        const val STREAM_DIRECT = "/v2/users/u1/stream_messages"
        const val STREAM_GROUP = "/v2/groups/g1/stream_messages"
    }
}
