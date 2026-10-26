package org.unitedsoftware.alexandrite.channel.qqbot.testing

import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.sdk.config.Secret

/** The config of one bot a test runs against [apiBase], with [gatewayUrl] when the test serves the gateway itself. */
internal fun testConfig(
    apiBase: String,
    gatewayUrl: String? = null,
    admins: Set<String> = emptySet(),
    syncLanguage: String? = null,
): QqBotInstanceConfig = QqBotInstanceConfig(
    appId = "10000",
    appSecret = Secret("secret"),
    apiBase = apiBase,
    gatewayUrl = gatewayUrl,
    admins = admins,
    syncLanguage = syncLanguage,
    connectTimeoutMillis = 5_000,
    firstByteTimeoutMillis = 5_000,
    idleTimeoutMillis = 5_000,
    reconnectIntervalMillis = 200,
)
