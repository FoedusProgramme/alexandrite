package org.unitedsoftware.alexandrite.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.internal.sandbox.NetworkProxy
import org.unitedsoftware.alexandrite.internal.sandbox.SYSTEM_RESOLVER
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.mcp.client.McpCallOutcome
import org.unitedsoftware.alexandrite.mcp.client.McpClient
import org.unitedsoftware.alexandrite.mcp.client.McpClientOptions
import org.unitedsoftware.alexandrite.mcp.client.McpConnection
import org.unitedsoftware.alexandrite.mcp.client.McpConnectionException
import org.unitedsoftware.alexandrite.mcp.client.McpEnvironment
import org.unitedsoftware.alexandrite.mcp.client.McpException
import org.unitedsoftware.alexandrite.mcp.client.McpHttpConnection
import org.unitedsoftware.alexandrite.mcp.client.McpHttpStatusException
import org.unitedsoftware.alexandrite.mcp.client.McpImplementation
import org.unitedsoftware.alexandrite.mcp.client.McpRpcException
import org.unitedsoftware.alexandrite.mcp.client.McpStdioConnection
import org.unitedsoftware.alexandrite.mcp.client.McpTimeoutException
import org.unitedsoftware.alexandrite.mcp.client.McpToolList
import org.unitedsoftware.alexandrite.mcp.client.McpUnauthorized
import org.unitedsoftware.alexandrite.mcp.client.StdioLaunch
import org.unitedsoftware.alexandrite.mcp.oauth.McpOAuth
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal enum class ServerState { CONNECTING, CONNECTED, DOWN, NEEDS_LOGIN, DISABLED }

internal data class ServerStatus(
    val state: ServerState,
    /** The protocol version of the current connection, null while there is none. */
    val protocolVersion: String? = null,
    val tools: Int = 0,
    /** Why the server last failed, null when it has not. */
    val lastError: String? = null,
    /** The server errors in a row on the current connection. */
    val serverErrors: Int = 0,
)

internal class McpTimings(
    /**
     * The first pause before a failed server starts again. [McpClientOptions.reconnectMin] paces a layer below: a
     * subscription that a live session opens again.
     */
    val firstPause: Duration = 1.seconds,
    val maxPause: Duration = 5.minutes,
    /** How long a connection must have lasted for the pause to start again from [firstPause]. */
    val steady: Duration = 10.minutes,
    /** The shortest wait between two listings that a server's `ttlMs` asks for. */
    val minTtl: Duration = 5.seconds,
    val stopGrace: Duration = 2.seconds,
)

/** What the servers share: how they are reached, what they are told of the client and their logins. */
internal class McpShared(
    val transport: HttpTransport,
    val client: McpImplementation,
    val clientOptions: McpClientOptions,
    /** The host's environment, of which a stdio server gets the minimal part. */
    val host: Map<String, String>,
    val timings: McpTimings,
    val oauth: McpOAuth,
    /** The proxy through which sandboxed servers reach their hosts. */
    val proxy: NetworkProxy = NetworkProxy(SYSTEM_RESOLVER),
)

/**
 * One configured server, connected again with a growing pause whenever it fails, whose current tools go to [publish].
 */
internal class McpServer(
    val spec: ServerSpec,
    private val shared: McpShared,
    private val publish: (List<Tool>) -> Unit,
) {
    private val timings = shared.timings
    private val restarts = Channel<Unit>(Channel.CONFLATED)
    private val current =
        MutableStateFlow(ServerStatus(if (spec.enabled) ServerState.CONNECTING else ServerState.DISABLED))
    private val warnedInvalid = AtomicBoolean()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var offered: Map<String, McpServerTool> = emptyMap()

    val status: StateFlow<ServerStatus> = current.asStateFlow()

    /** Ends the current connection, or the pause before the next, and connects again at once. */
    fun restart() {
        restarts.trySend(Unit)
    }

    /** Keeps the server connected until cancelled. */
    suspend fun run() {
        var pause = timings.firstPause
        while (true) {
            val ended = cycle()
            if (ended == null) {
                logger.info("MCP server {} restarts", spec.name)
                pause = timings.firstPause
                continue
            }
            val failure = ended.failure
            if (ended.connectedAt?.let { it.elapsedNow() >= timings.steady } == true) pause = timings.firstPause
            if (failure is McpUnauthorized) refused(failure)
            failed(failure, pause)
            val early = withTimeoutOrNull(pause) { restarts.receive() } != null
            pause = if (early) timings.firstPause else (pause * 2).coerceAtMost(timings.maxPause)
        }
    }

    /** Calls the server's tool [tool] with [arguments] for [context]. */
    suspend fun call(tool: String, arguments: JsonObject, context: ToolContext): ToolResult {
        val active = session ?: return unavailable(context)
        val outcome = try {
            active.client.callTool(tool, arguments, spec.timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpTimeoutException) {
            return error("MCP server ${spec.name} did not answer within ${spec.timeout}, so the call was cancelled.")
        } catch (e: McpUnauthorized) {
            val wider = if (e.status == FORBIDDEN) {
                refused(e)
                shared.oauth.widerScopes(spec.name)
            } else {
                null
            }
            if (wider != null) {
                val scope = wider.joinToString(" ")
                return error(
                    "MCP server ${spec.name} needs a login with more permissions (scope '$scope'), so the call did " +
                        "not run. ${loginHint()}",
                )
            }
            active.broken.complete(e)
            return error("MCP server ${spec.name} needs a login, so the call did not run. ${loginHint()}")
        } catch (e: McpRpcException) {
            taint(context)
            val message = cutWithEllipsis(e.rpcMessage, MAX_ERROR)
            val status = serverError(e)
            if (status != null) {
                return serverFailed(
                    active,
                    status,
                    "MCP server ${spec.name} failed the call with HTTP $status: $message (${e.code}).",
                )
            }
            answered(active)
            return error("MCP server ${spec.name} refused the call: $message (${e.code}).")
        } catch (e: McpConnectionException) {
            taint(context)
            active.broken.complete(e)
            return error("MCP server ${spec.name} went down during the call: ${e.message}")
        } catch (e: McpException) {
            taint(context)
            val status = serverError(e)
            if (status != null) {
                return serverFailed(active, status, "MCP server ${spec.name} failed the call with HTTP $status.")
            }
            return error("The call to MCP server ${spec.name} failed: ${e.message}")
        }
        answered(active)
        taint(context)
        return when (outcome) {
            is McpCallOutcome.Completed -> McpResults.completed(outcome)
            is McpCallOutcome.NeedsInput -> McpResults.needsInput(spec.name)
        }
    }

    /** Null when a restart cut the cycle short, else how the server failed. */
    private suspend fun cycle(): Ended? = coroutineScope {
        val serving = async { serve() }
        val restart = async { restarts.receive() }
        select<Ended?> {
            serving.onAwait { it }
            restart.onAwait { null }
        }.also {
            serving.cancel()
            restart.cancel()
        }
    }

    /** Connects and serves until the connection ends, and returns why it did or why it could not connect. */
    private suspend fun serve(): Ended {
        current.update { it.copy(state = ServerState.CONNECTING, protocolVersion = null, serverErrors = 0) }
        val client = try {
            McpClient.connect(open(), shared.client, shared.clientOptions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Ended(e, null)
        }
        val active = Session(client)
        var connectedAt: TimeMark? = null
        try {
            val listed = list(client)
            session = active
            offer(listed)
            connectedAt = TimeSource.Monotonic.markNow()
            shared.oauth.connected(spec.name)
            current.update { it.copy(state = ServerState.CONNECTED, protocolVersion = client.server.protocolVersion) }
            logger.info(
                "MCP server {} is connected (protocol {}) and offers {} tools",
                spec.name,
                client.server.protocolVersion,
                offered.size,
            )
            val failure = coroutineScope {
                val wake = Channel<Unit>(Channel.CONFLATED)
                val watching = launch { client.toolsChanged.collect { wake.trySend(Unit) } }
                val refreshing = launch { refresh(active, wake, listed) }
                select<Exception> {
                    client.ended.onAwait { it }
                    active.broken.onAwait { it }
                }.also {
                    watching.cancel()
                    refreshing.cancel()
                }
            }
            return Ended(failure, connectedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Ended(e, connectedAt)
        } finally {
            session = null
            offer(null)
            withContext(NonCancellable) { client.close() }
        }
    }

    /** Lists the tools again whenever [wake] fires or the last listing's freshness runs out. */
    private suspend fun refresh(active: Session, wake: Channel<Unit>, first: McpToolList) {
        var ttl = first.ttl
        while (true) {
            val fresh = ttl
            if (fresh ==
                null
            ) {
                wake.receive()
            } else {
                withTimeoutOrNull(fresh.coerceAtLeast(timings.minTtl)) { wake.receive() }
            }
            val listed = try {
                list(active.client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpConnectionException) {
                active.broken.complete(e)
                return
            } catch (e: McpUnauthorized) {
                active.broken.complete(e)
                return
            } catch (e: McpException) {
                serverError(e)?.let { status -> if (counted(active, status) >= MAX_SERVER_ERRORS) return }
                logger.warn(
                    "MCP server {} could not list its tools again, which stay as they were: {}",
                    spec.name,
                    e.message,
                )
                continue
            }
            answered(active)
            offer(listed)
            ttl = listed.ttl
        }
    }

    private suspend fun open(): McpConnection = when (val transport = spec.transport) {
        is Transport.Stdio -> McpStdioConnection.start(
            launch(transport),
            onStderr = { logger.debug("MCP server {} says: {}", spec.name, cutWithEllipsis(it, MAX_STDERR)) },
            onInvalid = ::invalid,
            stopGrace = timings.stopGrace,
        )

        is Transport.Http -> McpHttpConnection(transport.url, transport.headers, shared.transport) {
            shared.oauth.bearer(spec.name)
        }
    }

    /** How to start [transport]'s server, in its sandbox with a proxy held for the connection's life when it has one. */
    private suspend fun launch(transport: Transport.Stdio): StdioLaunch {
        val environment = McpEnvironment.minimal(shared.host, transport.env)
        val sandbox = transport.sandbox
            ?: return StdioLaunch(transport.command, transport.args, environment, transport.cwd)
        val lease = if (sandbox.allowed.isEmpty()) {
            null
        } else {
            try {
                withContext(Dispatchers.IO) { shared.proxy.open(sandbox.allowed) }
            } catch (e: IOException) {
                throw McpConnectionException("The sandbox's network proxy could not listen: ${e.message}", e)
            }
        }
        val boundary = lease?.let { sandbox.through(it.port) } ?: sandbox.boundary
        return StdioLaunch(
            transport.command,
            transport.args,
            environment,
            transport.cwd,
            start = { builder -> sandbox.environment.launch(builder, boundary) },
            held = lease,
        )
    }

    private suspend fun list(client: McpClient): McpToolList {
        val listed = client.listTools()
        for (rejected in listed.rejected) {
            once("${rejected.name}: ${rejected.reason}") {
                logger.warn(
                    "MCP server {} lists a tool '{}' that is left out: {}",
                    spec.name,
                    rejected.name,
                    rejected.reason,
                )
            }
        }
        return listed
    }

    /** Offers the tools of [listed], none when null. */
    @Synchronized
    private fun offer(listed: McpToolList?) {
        val built = McpDefinitions.of(spec, listed?.tools.orEmpty())
        for ((name, reason) in built.rejected) {
            once("$name: $reason") { logger.warn("MCP server {}'s tool '{}' is left out: {}", spec.name, name, reason) }
        }
        val previous = offered.values.associateBy { it.serverTool }
        offered = built.definitions.entries.associate { (tool, definition) ->
            val kept = previous[tool]?.takeIf { it.definition == definition }
            definition.name to (kept ?: mcpServerTool(definition, tool, this))
        }
        publish(offered.values.toList())
        current.update { it.copy(tools = offered.size) }
    }

    private fun failed(failure: Exception, pause: Duration) {
        val state = if (failure is McpUnauthorized) ServerState.NEEDS_LOGIN else ServerState.DOWN
        val message = cutWithEllipsis(failure.message ?: failure.toString(), MAX_ERROR)
        val before = current.value
        current.value = ServerStatus(state, lastError = message)
        if (before.state != state || before.lastError != message) {
            logger.warn("MCP server {} is {}: {}; it is tried again in {}", spec.name, words(state), message, pause)
        } else {
            logger.debug(
                "MCP server {} is still {}: {}; it is tried again in {}",
                spec.name,
                words(state),
                message,
                pause,
            )
        }
    }

    /** [text] as the error of a call that got the server error [status] on [active]. */
    private fun serverFailed(active: Session, status: Int, text: String): ToolResult {
        val count = counted(active, status)
        if (count < MAX_SERVER_ERRORS) return error(text)
        return error("$text It failed $count times in a row, so it counts as down and connects again.")
    }

    /** Counts the server error [status] on [active], which ends the connection at [MAX_SERVER_ERRORS] in a row. */
    private fun counted(active: Session, status: Int): Int {
        val count = active.serverErrors.incrementAndGet()
        current.update { if (session === active) it.copy(serverErrors = count, lastError = "HTTP $status") else it }
        if (count >= MAX_SERVER_ERRORS) {
            active.broken.complete(McpException("HTTP $status from $count requests in a row"))
        }
        return count
    }

    /** Ends the run of server errors of [active], which answered. */
    private fun answered(active: Session) {
        if (active.serverErrors.getAndSet(0) == 0) return
        current.update { if (session === active) it.copy(serverErrors = 0) else it }
    }

    private fun invalid(line: String) {
        if (warnedInvalid.compareAndSet(false, true)) {
            logger.warn("MCP server {} wrote output that is no JSON-RPC message, such as: {}", spec.name, line)
        } else {
            logger.debug("MCP server {} wrote output that is no JSON-RPC message: {}", spec.name, line)
        }
    }

    private suspend fun refused(e: McpUnauthorized) {
        shared.oauth.refused(spec.name, e.status, e.wwwAuthenticate, e.authorized)
    }

    private fun loginHint(): String = if (shared.oauth.callbacks) {
        "An operator must send /mcp login ${spec.name} in a direct chat with the bot, then try again."
    } else {
        "It cannot log in: ${McpOAuth.NO_CALLBACK}"
    }

    /** Why the call for [context] did not run, which the server's last error taints. */
    private fun unavailable(context: ToolContext): ToolResult {
        val status = current.value
        if (status.state == ServerState.NEEDS_LOGIN) {
            return error("MCP server ${spec.name} needs a login, so the call did not run. ${loginHint()}")
        }
        if (status.lastError != null) taint(context)
        val error = status.lastError?.let { " (last error: $it)" } ?: ""
        return error("MCP server ${spec.name} is ${words(status.state)}$error, so the call did not run.")
    }

    private fun taint(context: ToolContext) {
        if (!spec.trusted) context.untrusted("output of MCP server ${spec.name}")
    }

    private inline fun once(key: String, log: () -> Unit) {
        if (warned.add(key)) log()
    }

    private class Session(val client: McpClient) {
        val broken = CompletableDeferred<Exception>()
        val serverErrors = AtomicInteger()
    }

    /** Why a connection ended, and when it was made, null when it never was. */
    private class Ended(val failure: Exception, val connectedAt: TimeMark?)

    private companion object {
        const val FORBIDDEN = 403
        const val MAX_ERROR = 300
        const val MAX_STDERR = 1000
        const val MAX_SERVER_ERRORS = 3
        val SERVER_ERRORS = 500..599

        /** The HTTP status of [e] when it is a server error, null when it is none. */
        fun serverError(e: McpException): Int? = when (e) {
            is McpRpcException -> e.httpStatus
            is McpHttpStatusException -> e.status
            else -> null
        }?.takeIf { it in SERVER_ERRORS }

        fun error(text: String) = ToolResult(text, isError = true)

        fun words(state: ServerState): String = when (state) {
            ServerState.CONNECTING -> "connecting"
            ServerState.CONNECTED -> "connected"
            ServerState.DOWN -> "down"
            ServerState.NEEDS_LOGIN -> "waiting for a login"
            ServerState.DISABLED -> "disabled"
        }
    }
}

private val logger = LoggerFactory.getLogger(McpServer::class.java)
