package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QqBotProgressTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")
    private val direct = ChatAddress(instance, "private:u1")
    private val turn = TurnId("turn1")
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `an approval closes the stream and defers the answer`() = runBlocking {
        val platform = Platform()
        try {
            val stream = QqBotStream(platform.sender, direct, "msg1", platform.sender.sequenceFor("msg1"))
            val progress = QqBotProgress()
            progress.attach(turn, direct, stream)
            stream.show("working")

            assertEquals(1, progress.deferChat(direct))

            assertTrue(progress.isDeferred(turn))
            val pieces = platform.server.calls(STREAM_DIRECT)
            assertEquals(2, pieces.size)
            assertTrue("\"input_state\":1" in pieces[0].body, pieces[0].body)
            assertTrue("\"input_state\":10" in pieces[1].body, pieces[1].body)
            assertTrue("\"content_raw\"" !in pieces[1].body, pieces[1].body)

            progress.done(turn)
            assertTrue(!progress.isDeferred(turn))
        } finally {
            platform.close()
        }
    }

    private inner class Platform : AutoCloseable {
        val server = FakeApiServer()
        private val http: QqBotHttp
        val sender: QqBotSender

        init {
            server.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
            server.on(STREAM_DIRECT, """{"id":"stream1"}""")
            val config = testConfig(server.base)
            http = QqBotHttp(config)
            val api = QqBotApi(http, QqBotTokens(config, http, clock))
            sender = QqBotSender(api, QqBotUploads(api, config), QqBotSentMessages(), QqBotMessageIndex())
        }

        override fun close() {
            http.close()
            server.close()
        }
    }

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"
        const val STREAM_DIRECT = "/v2/users/u1/stream_messages"
    }
}
