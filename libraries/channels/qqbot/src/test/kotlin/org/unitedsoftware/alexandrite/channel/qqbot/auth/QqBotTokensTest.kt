package org.unitedsoftware.alexandrite.channel.qqbot.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QqBotTokensTest {
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `a token is bought once and used until it is close to expiring`() = runBlocking {
        val server = FakeApiServer()
        server.on(TOKEN_PATH, """{"access_token":"one","expires_in":"7200"}""")
        val http = QqBotHttp(testConfig(server.base))
        try {
            val tokens = QqBotTokens(testConfig(server.base), http, clock)
            assertEquals("one", tokens.token())
            assertEquals("one", tokens.token())
            assertEquals(1, server.calls(TOKEN_PATH).size, "the token was bought again while it was valid")

            clock.at = clock.at.plusSeconds(7_200)
            assertEquals("one", tokens.token())

            server.on(TOKEN_PATH, """{"access_token":"two","expires_in":"7200"}""")
            clock.at = clock.at.plusSeconds(7_200)
            assertEquals("two", tokens.token())
            assertEquals(3, server.calls(TOKEN_PATH).size)
        } finally {
            http.close()
            server.close()
        }
    }

    @Test
    fun `concurrent callers racing an empty cache buy the token once`() = runBlocking {
        val server = FakeApiServer()
        val bought = AtomicInteger()
        server.on(TOKEN_PATH) {
            Thread.sleep(50)
            PlatformAnswer(200, """{"access_token":"t${bought.incrementAndGet()}","expires_in":"7200"}""")
        }
        val http = QqBotHttp(testConfig(server.base))
        try {
            val tokens = QqBotTokens(testConfig(server.base), http, clock)
            val results = (1..20).map { async { tokens.token() } }.awaitAll()
            assertEquals(1, server.calls(TOKEN_PATH).size, "concurrent callers bought the token more than once")
            assertTrue(results.all { it == results.first() }, "concurrent callers did not all get the one token bought")
        } finally {
            http.close()
            server.close()
        }
    }

    @Test
    fun `a refusal of the platform names its code and message`() = runBlocking {
        val server = FakeApiServer()
        server.on(TOKEN_PATH, """{"code":100016,"message":"invalid appid or secret"}""")
        val http = QqBotHttp(testConfig(server.base))
        try {
            val tokens = QqBotTokens(testConfig(server.base), http, clock)
            val failure = assertFailsWith<IllegalStateException> { tokens.token() }
            assertTrue("100016" in failure.message.orEmpty(), failure.message)
            assertTrue("invalid appid or secret" in failure.message.orEmpty(), failure.message)
        } finally {
            http.close()
            server.close()
        }
    }

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"
    }
}
