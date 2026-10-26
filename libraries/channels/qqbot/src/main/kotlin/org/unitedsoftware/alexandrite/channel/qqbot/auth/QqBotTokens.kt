package org.unitedsoftware.alexandrite.channel.qqbot.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotAccessToken
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.asTextOrNull
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.failureOf
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.number
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.time.Clock
import java.time.Instant

/**
 * The access token of one bot, which the open platform sells for its AppID and AppSecret and expires after two hours.
 *
 * The platform answers the same token while it is valid, so the token is kept until shortly before it expires and
 * bought again then. A call that fails because the token expired asks for a new one.
 */
@ChannelInstanceScoped
internal class QqBotTokens(
    private val config: QqBotInstanceConfig,
    private val http: QqBotHttp,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var token: QqBotAccessToken? = null
    private var expiresAt: Instant = Instant.EPOCH

    /** The token to present, bought again when it is time or when [refresh] asks for one. */
    suspend fun token(refresh: Boolean = false): String = mutex.withLock {
        val kept = token
        if (!refresh && kept != null && clock.instant().isBefore(expiresAt)) return kept.value
        buy().also {
            token = it
            expiresAt = clock.instant().plusSeconds((it.expiresInSeconds - SKEW_SECONDS).coerceAtLeast(1))
        }.value
    }

    /** The value the platform's authorization header carries: `QQBot <token>`. */
    suspend fun authorization(refresh: Boolean = false): String = "QQBot ${token(refresh)}"

    /** Buys a token with the AppID and AppSecret of the instance. */
    private suspend fun buy(): QqBotAccessToken {
        val call = HttpCall(
            method = "POST",
            uri = http.uri("/app/getAppAccessToken"),
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = buildJsonObject {
                put("appId", config.appId)
                put("clientSecret", config.appSecret.reveal())
            }.toString(),
            secrets = listOf(config.appSecret.reveal()),
        )
        val text = http.send(call)
        val body = runCatching { QqBotWire.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val failure = failureOf(body)
        if (failure.code != null && failure.code != 0) {
            logger.warn(
                "{}: the platform gave no access token for AppID {}: {} {}",
                PLUGIN,
                config.appId,
                failure.code,
                failure.message,
            )
            throw IllegalStateException(
                "The open platform gave no access token for AppID ${config.appId}: " +
                    "${failure.message} (code ${failure.code})",
            )
        }
        val value = body?.get("access_token")?.asTextOrNull()
        if (value == null) {
            logger.warn("{}: no access token came back for AppID {}: {}", PLUGIN, config.appId, text.take(200))
            throw IllegalStateException("The open platform gave no access token for AppID ${config.appId}.")
        }
        val expires = body.number("expires_in") ?: DEFAULT_EXPIRES_SECONDS
        return QqBotAccessToken(value, expires)
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-qqbot"

        /** The platform expires a token in two hours; a new one is had this long before that. */
        const val SKEW_SECONDS = 60L

        const val DEFAULT_EXPIRES_SECONDS = 7_200L

        val logger = LoggerFactory.getLogger(QqBotTokens::class.java)
    }
}
