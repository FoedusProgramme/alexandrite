package org.unitedsoftware.alexandrite.channel.qqbot.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFrame
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.asIntOrNull
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.asTextOrNull
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * The gateway of one bot: the WebSocket the open platform pushes its events on.
 *
 * The platform names the gateway for the bot, sends a hello that says how often to beat, and takes an identify that
 * names the bot's token and the event groups it subscribes to. A connection that drops is made again: one that still
 * holds a session resumes it and is sent what it missed, one that does not identifies again.
 *
 * The frames are handed on as they arrive; what they mean is the business of the channel above this class.
 */
@ChannelInstanceScoped
internal class QqBotGateway(
    private val config: QqBotInstanceConfig,
    private val api: QqBotApi,
    private val tokens: QqBotTokens,
) : Lifecycle {
    private val incoming = Channel<QqBotFrame>(EVENT_CAPACITY, BufferOverflow.DROP_OLDEST)

    /** The frames the platform pushed, which the channel reads. */
    val frames: Flow<QqBotFrame> = incoming.receiveAsFlow()

    @Volatile
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    @Volatile
    private var client: GatewayClient? = null

    /** The session a reconnect resumes, null when the bot has none the platform still knows. */
    @Volatile
    private var sessionId: String? = null

    /** The number of the last frame read, which a resume names and a heartbeat reports. */
    @Volatile
    private var lastSeq: Long? = null

    /** The id the platform gave the bot in the ready frame. */
    @Volatile
    var botId: String? = null
        private set

    /** Whether the platform closed the last connection for a token it would not take, so the next one buys a new one. */
    @Volatile
    private var authFailed = false

    override suspend fun onStart() {
        check(scope == null) { "The gateway is already open." }
        val opened = CompletableDeferred<Throwable?>()
        val failed = CoroutineExceptionHandler { _, error ->
            logger.error("{}: the gateway loop failed", PLUGIN, error)
        }
        val run = CoroutineScope(SupervisorJob() + Dispatchers.IO + failed)
        scope = run
        loop = run.launch { connectLoop(opened) }
        val failure = try {
            withTimeout(config.connectTimeoutMillis * START_ATTEMPTS) { opened.await() }
        } catch (e: TimeoutCancellationException) {
            onStop()
            IllegalStateException("The gateway did not become ready in time.", e)
        }
        if (failure != null) {
            onStop()
            throw failure
        }
    }

    override fun onStop() {
        // Closing the socket ends the loop by way of its close handler; cancelling the scope ends it after that.
        runCatching { client?.close() }
        scope?.cancel()
        scope = null
        loop = null
        client = null
    }

    private suspend fun connectLoop(opened: CompletableDeferred<Throwable?>) {
        // How often naming the gateway failed before the runtime was ready, which the startup retries before it gives
        // up: the platform may be briefly unreachable, and one blip must not fail the start.
        var unnamed = 0

        // How many connections in a row ended without becoming ready, which grows the pause before the next try.
        var failures = 0
        while (scope != null) {
            // Naming the gateway asks the platform, which may be unreachable: that is a reconnect, not the end of the
            // loop, so that a single failed call never leaves the bot without its gateway until the runtime restarts.
            val url = try {
                gatewayUrl()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!opened.isCompleted) {
                    opened.complete(e)
                    return
                }
                logger.warn("{}: the gateway could not be named: {}; trying again", PLUGIN, e.toString())
                failures++
                delay(backOff(failures))
                continue
            }
            if (url == null) {
                if (!opened.isCompleted) {
                    unnamed += 1
                    if (unnamed >= START_ATTEMPTS) {
                        opened.complete(IllegalStateException("The platform named no gateway in $unnamed tries."))
                        return
                    }
                }
                logger.warn("{}: the platform named no gateway; trying again", PLUGIN)
                failures++
                delay(backOff(failures))
                continue
            }
            val token = try {
                tokens.token(refresh = authFailed.also { authFailed = false })
            } catch (e: Exception) {
                if (!opened.isCompleted) {
                    opened.complete(e)
                    return
                }
                logger.warn("{}: no token for the gateway: {}; trying again", PLUGIN, e.toString())
                failures++
                delay(backOff(failures))
                continue
            }
            val ended = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Throwable?>()
            val socket = GatewayClient(URI(url), ended, ready).also { it.token = token }
            client = socket
            val connected = try {
                socket.connectBlocking(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                if (!opened.isCompleted) {
                    opened.complete(e)
                    return
                }
                logger.warn("{}: the gateway connection failed: {}; trying again", PLUGIN, e.toString())
                null
            }
            if (connected != true) {
                socket.close()
                if (!opened.isCompleted) {
                    opened.complete(IllegalStateException("The gateway at $url did not answer."))
                    return
                }
            } else {
                val failure = try {
                    withTimeout(config.connectTimeoutMillis * START_ATTEMPTS) { ready.await() }
                } catch (e: TimeoutCancellationException) {
                    IllegalStateException("The gateway at $url did not become ready in time.", e)
                }
                if (failure != null) {
                    socket.close()
                    if (!opened.isCompleted) {
                        opened.complete(failure)
                        return
                    }
                    logger.warn("{}: the gateway did not become ready: {}; trying again", PLUGIN, failure.message)
                } else {
                    failures = 0
                    if (!opened.isCompleted) opened.complete(null)
                    ended.await()
                }
            }
            client = null
            if (scope == null) return
            failures++
            delay(backOff(failures))
        }
    }

    /** The pause after the [failures]th connection in a row that failed or ended, doubling up to 20 times the interval. */
    internal fun backOff(failures: Int): Long {
        val base = config.reconnectIntervalMillis
        val grown = base * (1L shl (failures - 1).coerceAtMost(MAX_SHIFT))
        return grown.coerceAtMost(base * MAX_BACKOFF_MULTIPLIER)
    }

    /** The address of the gateway, from the config or from the platform. */
    private suspend fun gatewayUrl(): String? {
        config.gatewayUrl?.let { return it }
        return when (val answer = api.gatewayUrl()) {
            is QqBotAnswer.Ok -> (answer.body as? JsonObject)?.get("url")?.asTextOrNull()

            is QqBotAnswer.Failed -> {
                logger.warn(
                    "{}: the platform named no gateway: {} {}",
                    PLUGIN,
                    answer.failure.code,
                    answer.failure.message,
                )
                null
            }
        }
    }

    /** The socket of one connection, which reads its frames on the thread of the library. */
    private inner class GatewayClient(
        uri: URI,
        private val ended: CompletableDeferred<Unit>,
        val ready: CompletableDeferred<Throwable?>,
    ) : WebSocketClient(uri) {
        private val stopping = AtomicBoolean()
        private var heartbeat: Job? = null

        override fun onOpen(handshake: ServerHandshake) {
            logger.debug("{}: the gateway is open", PLUGIN)
        }

        override fun onMessage(message: String) {
            val frame = QqBotWire.frame(message) ?: run {
                logger.warn("{}: the gateway sent a frame this version does not know: {}", PLUGIN, message.take(200))
                return
            }
            frame.s?.let { lastSeq = it }
            when (frame.op) {
                OP_HELLO -> if (!startHeartbeat(frame)) return

                OP_DISPATCH -> dispatch(frame)

                OP_HEARTBEAT_ACK -> Unit

                OP_RECONNECT -> close()

                OP_INVALID_SESSION -> {
                    sessionId = null
                    lastSeq = null
                    close()
                }

                else -> logger.debug(
                    "{}: the gateway sent opcode {} which this version has no use for",
                    PLUGIN,
                    frame.op,
                )
            }
        }

        /** Starts beating as the hello asks, then identifies or resumes as the bot's session says. */
        private fun startHeartbeat(hello: QqBotFrame): Boolean {
            val interval = (hello.d as? JsonObject)?.get("heartbeat_interval")?.asIntOrNull()?.toLong()
            if (interval == null || interval <= 0) {
                ready.complete(IllegalStateException("The platform's hello named no heartbeat interval."))
                close()
                return false
            }
            val run = scope ?: return false
            heartbeat?.cancel()
            heartbeat = run.launch {
                while (true) {
                    delay(interval)
                    val seq = lastSeq
                    send(
                        buildJsonObject {
                            put("op", OP_HEARTBEAT)
                            put("d", seq)
                        }.toString(),
                    )
                }
            }
            val kept = sessionId
            if (kept != null) {
                send(resume(kept))
            } else {
                send(identify())
            }
            return true
        }

        /** Reads a dispatch frame: the ready and resumed frames settle the startup, the others are handed on. */
        private fun dispatch(frame: QqBotFrame) {
            when (frame.t) {
                "READY" -> {
                    val data = frame.d as? JsonObject
                    sessionId = (data?.get("session_id"))?.asTextOrNull()
                    botId = ((data?.get("user") as? JsonObject)?.get("id"))?.asTextOrNull()
                    logger.info("{}: the gateway is ready, session {}", PLUGIN, sessionId)
                    ready.complete(null)
                }

                "RESUMED" -> {
                    logger.info("{}: the gateway resumed the session", PLUGIN)
                    ready.complete(null)
                }

                null -> Unit

                else -> {
                    if (frame.t in MESSAGE_EVENTS) {
                        logger.info("{}: the platform dispatched {} at seq {}", PLUGIN, frame.t, frame.s)
                    } else {
                        logger.debug("{}: the platform dispatched {} at seq {}", PLUGIN, frame.t, frame.s)
                    }
                    if (!incoming.trySend(frame).isSuccess) {
                        logger.warn("{}: an event of the platform was dropped: the channel is behind", PLUGIN)
                    }
                }
            }
        }

        /** The identify frame, which names the token, the event groups and the one shard this connection reads. */
        private fun identify(): String = buildJsonObject {
            put("op", OP_IDENTIFY)
            putJsonObject("d") {
                put("token", "QQBot ${token ?: ""}")
                put("intents", config.intents)
                putJsonArray("shard") {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                }
                putJsonObject("properties") {
                    put("\$os", "linux")
                    put("\$browser", "alexandrite")
                    put("\$device", "alexandrite")
                }
            }
        }.toString()

        /** The resume frame, which names the session and the last frame read. */
        private fun resume(kept: String): String = buildJsonObject {
            put("op", OP_RESUME)
            putJsonObject("d") {
                put("token", "QQBot ${token ?: ""}")
                put("session_id", kept)
                put("seq", lastSeq)
            }
        }.toString()

        /** The token the identify and resume frames carry, read before the socket opened. */
        var token: String? = null

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            heartbeat?.cancel()
            heartbeat = null
            // A session resume would carry the same token the platform just refused, so the next attempt identifies
            // fresh with the token bought again, as it does for a session the platform dropped on its own.
            if (dropsSession(code) || code == AUTH_FAILED_CODE) {
                sessionId = null
                lastSeq = null
            }
            if (!stopping.get()) logger.warn("{}: the gateway closed: {} {}", PLUGIN, code, reason)
            if (!ready.isCompleted) {
                ready.complete(IllegalStateException("The gateway closed while starting: $code $reason"))
            }
            if (code == AUTH_FAILED_CODE) authFailed = true
            if (code in FATAL_CODES) {
                logger.error("{}: the gateway refuses this bot: {} {}", PLUGIN, code, reason)
                // Nothing this connection does makes the platform take the bot, so no reconnect is tried again.
                scope?.cancel()
            }
            ended.complete(Unit)
        }

        override fun onError(ex: Exception) {
            logger.warn("{}: the gateway failed: {}", PLUGIN, ex.toString())
            if (!ready.isCompleted) ready.complete(ex)
        }

        override fun close() {
            stopping.set(true)
            super.close()
        }
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-qqbot"

        const val EVENT_CAPACITY = 1024

        /** How often the startup may try a step before it gives up: the token, the gateway and the ready frame. */
        const val START_ATTEMPTS = 3L

        /** The dispatches that carry a message, which are worth a line at the usual level. */
        val MESSAGE_EVENTS = setOf("C2C_MESSAGE_CREATE", "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE")

        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_RESUME = 6
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10
        const val OP_HEARTBEAT_ACK = 11

        /** The codes the platform closes the connection with when the bot itself may not connect. */
        val FATAL_CODES = setOf(4001, 4002, 4010, 4011, 4012, 4013, 4014, 4914, 4915)

        /** The code the platform closes the connection with when the token it was sent is not one it takes. */
        const val AUTH_FAILED_CODE = 4004

        const val MAX_BACKOFF_MULTIPLIER = 20L
        const val MAX_SHIFT = 10

        val logger = LoggerFactory.getLogger(QqBotGateway::class.java)
    }
}

/** Whether the close code [code] means the session a reconnect would resume is gone. */
internal fun dropsSession(code: Int): Boolean = code == 4006 || code == 4007 || code in 4900..4913
