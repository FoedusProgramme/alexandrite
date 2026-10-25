package org.unitedsoftware.alexandrite.channel.qqbot.gateway

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeGatewayServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QqBotGatewayTest {
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `the gateway identifies, becomes ready and hands the pushed events on`() = runBlocking {
        val api = FakeApiServer()
        api.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
        val platform = FakeGatewayServer().startAndAwait()
        val config = testConfig(api.base, gatewayUrl = platform.url)
        val http = QqBotHttp(config)
        try {
            val tokens = QqBotTokens(config, http, clock)
            val gateway = QqBotGateway(config, QqBotApi(http, tokens), tokens)
            gateway.onStart()

            val identify = assertNotNull(platform.awaitFrame(predicate = { it.contains("\"op\":2") }))
            assertTrue("QQBot access" in identify, identify)
            assertTrue("\"intents\":${1L shl 25}" in identify, identify)
            assertTrue("\"shard\":[0,1]" in identify, identify)
            assertEquals("bot1", gateway.botId)

            platform.push(EVENT)
            val frame = withTimeout(5_000) { gateway.frames.first { it.t == "C2C_MESSAGE_CREATE" } }
            assertEquals("event1", frame.id)
            assertEquals(
                "msg1",
                frame.d?.let {
                    org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire.message(it)?.id
                },
            )

            gateway.onStop()
        } finally {
            platform.stop()
            http.close()
            api.close()
        }
    }

    @Test
    fun `a token the platform cannot sell is retried rather than ending the gateway loop`() = runBlocking {
        val api = FakeApiServer()
        val bought = AtomicInteger()
        api.on(TOKEN_PATH) {
            if (bought.incrementAndGet() <= 2) {
                PlatformAnswer(500, """{"message":"token endpoint unavailable"}""")
            } else {
                PlatformAnswer(200, """{"access_token":"access","expires_in":"7200"}""")
            }
        }
        val platform = FakeGatewayServer().startAndAwait()
        api.on(GATEWAY_PATH, """{"url":"${platform.url}"}""")
        val config = testConfig(api.base)
        val http = QqBotHttp(config)
        try {
            val tokens = QqBotTokens(config, http, clock)
            val gateway = QqBotGateway(config, QqBotApi(http, tokens), tokens)
            gateway.onStart()

            assertTrue(platform.awaitConnection(), "the gateway was not connected after the token was bought")
            val identify = assertNotNull(platform.awaitFrame(predicate = { it.contains("\"op\":2") }))
            assertTrue("QQBot access" in identify, identify)
            assertTrue(bought.get() >= 3, "the token was bought ${bought.get()} times, so it was not retried")
            assertEquals("bot1", gateway.botId)

            gateway.onStop()
        } finally {
            platform.stop()
            http.close()
            api.close()
        }
    }

    @Test
    fun `an authentication failure buys a new token before the gateway reconnects`() = runBlocking {
        val api = FakeApiServer()
        val bought = AtomicInteger()
        api.on(TOKEN_PATH) {
            PlatformAnswer(200, """{"access_token":"access${bought.incrementAndGet()}","expires_in":"7200"}""")
        }
        val platform = FakeGatewayServer().startAndAwait()
        val config = testConfig(api.base, gatewayUrl = platform.url)
        val http = QqBotHttp(config)
        try {
            val tokens = QqBotTokens(config, http, clock)
            val gateway = QqBotGateway(config, QqBotApi(http, tokens), tokens)
            gateway.onStart()

            val first = assertNotNull(platform.awaitFrame(predicate = { it.contains("\"op\":2") }))
            assertTrue("access1" in first, first)
            assertEquals(1, bought.get())

            platform.closeConnection(4004, "bad token")

            val second = assertNotNull(platform.awaitFrame(predicate = { it.contains("\"op\":2") }))
            assertTrue("access2" in second, second)
            assertEquals(2, bought.get(), "the stale token was not refreshed after the platform rejected it")

            gateway.onStop()
        } finally {
            platform.stop()
            http.close()
            api.close()
        }
    }

    @Test
    fun `the gateway's backoff grows and caps as failures repeat`() {
        val config = testConfig("http://127.0.0.1:1")
        val http = QqBotHttp(config)
        try {
            val tokens = QqBotTokens(config, http, clock)
            val gateway = QqBotGateway(config, QqBotApi(http, tokens), tokens)
            assertEquals(200L, gateway.backOff(1))
            assertEquals(400L, gateway.backOff(2))
            assertEquals(800L, gateway.backOff(3))
            assertEquals(4_000L, gateway.backOff(20))
        } finally {
            http.close()
        }
    }

    @Test
    fun `a close code that ends the session is not resumed`() {
        assertTrue(dropsSession(4006))
        assertTrue(dropsSession(4007))
        assertTrue(dropsSession(4900))
        assertTrue(dropsSession(4913))
        assertFalse(dropsSession(4008))
        assertFalse(dropsSession(4009))
        assertFalse(dropsSession(4914))
    }

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"

        const val GATEWAY_PATH = "/gateway"

        const val EVENT = """{"id":"event1","op":0,"s":2,"t":"C2C_MESSAGE_CREATE","d":""" +
            """{"id":"msg1","author":{"id":"u1","user_openid":"u1","username":"Ada"},"content":"hi",""" +
            """"message_type":0,"timestamp":"2026-07-21T10:00:00+08:00"}}"""
    }
}
