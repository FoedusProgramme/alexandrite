package org.unitedsoftware.alexandrite.channel.telegram.api

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.OTHER_TOKEN
import org.unitedsoftware.alexandrite.channel.telegram.QUICK
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.configOf
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotApi
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeUser
import org.unitedsoftware.alexandrite.channel.telegram.logged
import org.unitedsoftware.alexandrite.channel.telegram.reply.SendLimiter
import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.config.Secret
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TokenMaskingTest {
    private val fake = FakeBotApi()
    private val config = configOf(fake)
    private val api = HttpBotApi(config, JdkHttpEngine(), QUICK)
    private val chat = fake.direct(FakeUser(42, "Ada")).id
    private val failures = mutableListOf<BotApiException>()

    @AfterTest
    fun close() {
        api.close()
        fake.close()
    }

    private suspend fun fails(block: suspend () -> Unit) {
        failures += assertFailsWith<BotApiException> { block() }
    }

    private fun assertMasked(text: String) {
        for (token in listOf(fake.token, OTHER_TOKEN)) {
            assertFalse(token.substringAfter(':') in text, text)
        }
    }

    @Test
    fun `no failure path shows the token in an exception, a toString or a log line`() = runBlocking {
        val echo = "/bot${fake.token}/getMe"
        val closedPort = ServerSocket(0).use { it.localPort }
        val lines = logged {
            fake.fail("sendMessage", 400, "Bad Request: chat ${fake.token} not found")
            fails { api.sendMessage(SendMessage(chat, "Hi")) }
            fake.respond("getMe", 502, "<html>No upstream for $echo</html>")
            fails { api.getMe() }
            fake.respond("getMe", 200, """{"ok":false,"description":"$echo is down"}""")
            fails { api.getMe() }
            fake.respond("getMe", 200, "<html>$echo</html>")
            fails { api.getMe() }
            fake.respond("getMe", 200, """{"ok":true,"result":{"id":"${fake.token}"}}""")
            fails { api.getMe() }
            fake.respond("getMe", 302, "", mapOf("location" to "${fake.baseUrl}$echo"))
            fails { api.getMe() }
            fake.breakConnection("getMe")
            fails { api.getMe() }
            fake.delay("getMe", 2.seconds)
            fails { api.getMe() }
            repeat(2) { fake.fail("sendMessage", 429, "Too Many Requests: ${fake.token}", retryAfter = 1) }
            fails { SendLimiter().message(chat) { api.sendMessage(SendMessage(chat, "Hi")) } }
            HttpBotApi(configOf(fake, OTHER_TOKEN), JdkHttpEngine()).use { other -> fails { other.getMe() } }
            val gone = TelegramInstanceConfig(Secret(fake.token), apiBaseUrl = "http://127.0.0.1:$closedPort")
            HttpBotApi(gone, JdkHttpEngine(), QUICK).use { unreachable -> fails { unreachable.getMe() } }
            val logger = LoggerFactory.getLogger(TokenMaskingTest::class.java)
            failures.forEach { logger.error("The call failed", it) }
        }

        assertEquals(11, failures.size)
        assertEquals(11, lines.count { it.startsWith("ERROR The call failed") })
        assertTrue(lines.any { it.startsWith("WARN") && "not retried" in it }, lines.joinToString("\n"))
        for (failure in failures) {
            assertMasked(failure.toString())
            assertMasked(failure.stackTraceToString())
            assertMasked(failure.description.orEmpty())
        }
        lines.forEach(::assertMasked)
        assertMasked(api.toString())
        assertMasked(config.toString())
        assertMasked(config.botToken.toString())
    }

    @Test
    fun `no download or upload shows the token in an exception or a log line`() = runBlocking {
        val path = checkNotNull(api.getFile(fake.file("data".encodeToByteArray())).filePath)
        val file = OutboundFile.builder("a.txt", "text/plain", "data".encodeToByteArray()).build()
        val lines = logged {
            assertEquals("data", api.download(path, 10).decodeToString())
            api.sendDocument(SendFile(chat, file))
            fails { api.download("documents/missing.bin", 10) }
            fails { api.download(path, 2) }
            fake.respond("download", 502, "<html>No upstream for /file/bot${fake.token}/$path</html>")
            fails { api.download(path, 10) }
            fake.breakConnection("download")
            fails { api.download(path, 10) }
            fake.delay("download", 2.seconds)
            fails { api.download(path, 10) }
            fake.respond("sendDocument", 500, "<html>/bot${fake.token}/sendDocument failed</html>")
            fails { api.sendDocument(SendFile(chat, file)) }
            fails { api.getFile("missing") }
            val logger = LoggerFactory.getLogger(TokenMaskingTest::class.java)
            failures.forEach { logger.error("The call failed", it) }
        }

        assertEquals(7, failures.size)
        for (failure in failures) {
            assertMasked(failure.toString())
            assertMasked(failure.stackTraceToString())
            assertMasked(failure.description.orEmpty())
        }
        lines.forEach(::assertMasked)
    }
}
