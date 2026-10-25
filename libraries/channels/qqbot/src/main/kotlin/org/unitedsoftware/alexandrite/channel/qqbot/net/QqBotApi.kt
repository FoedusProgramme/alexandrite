package org.unitedsoftware.alexandrite.channel.qqbot.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFailure
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.failureOf
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import kotlin.coroutines.cancellation.CancellationException

/** What a call to the open platform answered: the body, or why it gave none. */
internal sealed class QqBotAnswer {
    class Ok(val body: JsonElement?) : QqBotAnswer()

    class Failed(val failure: QqBotFailure, val status: Int?) : QqBotAnswer()
}

/** The calls of one bot to the open platform, which carry the token the platform sells it. */
@ChannelInstanceScoped
internal class QqBotApi(private val http: QqBotHttp, private val tokens: QqBotTokens) {
    /** The WebSocket address of the gateway, which the platform names for this bot. */
    suspend fun gatewayUrl(): QqBotAnswer = call("GET", "/gateway", null)

    /** Sends [body] to [path], which is the messages endpoint of one chat. */
    suspend fun post(path: String, body: JsonObject): QqBotAnswer = call("POST", path, body)

    /** Sends [body] to [path], replacing what it holds. */
    suspend fun put(path: String, body: JsonObject): QqBotAnswer = call("PUT", path, body)

    /** Reads [path], with the [query] it names as its own query. */
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): QqBotAnswer =
        call("GET", withQuery(path, query), null)

    /** Deletes what [path] names. */
    suspend fun delete(path: String): QqBotAnswer = call("DELETE", path, null)

    /** [path] with [query] encoded after it, the path alone when there is nothing to name. */
    private fun withQuery(path: String, query: Map<String, String>): String {
        if (query.isEmpty()) return path
        val encoded = query.entries.joinToString("&") { (name, value) ->
            "${java.net.URLEncoder.encode(name, Charsets.UTF_8)}=" +
                java.net.URLEncoder.encode(value, Charsets.UTF_8)
        }
        return "$path?$encoded"
    }

    /** Calls [method] on [path] with [body], buying a new token once when the platform says the old one expired. */
    private suspend fun call(method: String, path: String, body: JsonObject?, retried: Boolean = false): QqBotAnswer =
        try {
            // Buying the token is part of the call: a platform that cannot be reached for the token must answer the
            // caller like any other failed call.
            val authorization = tokens.authorization(refresh = retried)
            val call = HttpCall(
                method = method,
                uri = http.uri(path),
                headers = buildMap {
                    put("Authorization", authorization)
                    if (body != null) put("Content-Type", "application/json; charset=utf-8")
                },
                body = body?.toString(),
                secrets = listOf(authorization.removePrefix("QQBot ")),
            )
            val text = http.send(call)
            val answer = if (text.isBlank()) {
                null
            } else {
                runCatching {
                    QqBotWire.json.parseToJsonElement(text)
                }.getOrNull()
            }
            val failure = failureOf(answer)
            if (failure.code != null && failure.code != 0) {
                if (failure.code in EXPIRED_TOKEN && !retried) {
                    logger.warn("{}: the token is not taken while calling {}; buying another", PLUGIN, path)
                    call(method, path, body, retried = true)
                } else {
                    QqBotAnswer.Failed(failure, null)
                }
            } else {
                QqBotAnswer.Ok(answer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val parsed = e.body?.let { runCatching { QqBotWire.json.parseToJsonElement(it) }.getOrNull() }
            if (e.status == UNAUTHORIZED && !retried) {
                logger.warn("{}: the token expired while calling {}; buying another", PLUGIN, path)
                call(method, path, body, retried = true)
            } else {
                QqBotAnswer.Failed(failureOf(parsed), e.status)
            }
        } catch (e: Exception) {
            logger.warn("{}: the call to {} failed: {}", PLUGIN, path, e.toString())
            QqBotAnswer.Failed(QqBotFailure(null, e.toString(), null), null)
        }

    private companion object {
        const val PLUGIN = "alexandrite-channel-qqbot"

        const val UNAUTHORIZED = 401

        /** The codes with which the platform says the token presented is no good, which another one may fix. */
        val EXPIRED_TOKEN = setOf(11241, 11243, 11244)

        val logger = LoggerFactory.getLogger(QqBotApi::class.java)
    }
}
