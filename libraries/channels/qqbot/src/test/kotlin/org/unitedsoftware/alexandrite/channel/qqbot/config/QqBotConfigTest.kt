package org.unitedsoftware.alexandrite.channel.qqbot.config

import org.unitedsoftware.alexandrite.sdk.config.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QqBotConfigTest {
    @Test
    fun `an instance names the bot and the platform`() {
        val config = QqBotInstanceConfig(appId = "10000", appSecret = Secret("secret"))

        assertEquals("10000", config.appId)
        assertEquals(QqBotInstanceConfig.DEFAULT_API_BASE, config.apiBase)
        assertEquals(1L shl 25, config.intents)
        assertEquals(true, config.streaming)
        assertEquals(10_000L, config.connectTimeoutMillis)
        assertEquals(10L, config.approvalMinutes)
    }

    @Test
    fun `a blank app id is refused`() {
        assertFailsWith<IllegalArgumentException> { QqBotInstanceConfig(appId = "", appSecret = Secret("secret")) }
    }

    @Test
    fun `an app id that is no number or word is refused`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotInstanceConfig(appId = "app id", appSecret = Secret("secret"))
        }
    }

    @Test
    fun `an api base that is no HTTP address is refused`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotInstanceConfig(appId = "10000", appSecret = Secret("secret"), apiBase = "api.bot.qq.com")
        }
    }

    @Test
    fun `a gateway address that is no websocket address is refused`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotInstanceConfig(
                appId = "10000",
                appSecret = Secret("secret"),
                gatewayUrl = "https://api.bot.qq.com/websocket",
            )
        }
    }

    @Test
    fun `intents that name no event group are refused`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotInstanceConfig(appId = "10000", appSecret = Secret("secret"), intents = 0)
        }
    }

    @Test
    fun `a timeout that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotInstanceConfig(appId = "10000", appSecret = Secret("secret"), connectTimeoutMillis = 0)
        }
    }

    @Test
    fun `a sync language that is no language tag is refused`() {
        assertFailsWith<IllegalArgumentException> {
            QqBotInstanceConfig(appId = "10000", appSecret = Secret("secret"), syncLanguage = "中文")
        }
    }
}
