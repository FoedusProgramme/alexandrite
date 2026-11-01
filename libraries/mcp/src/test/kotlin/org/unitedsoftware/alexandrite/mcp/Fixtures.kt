package org.unitedsoftware.alexandrite.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.mcp.client.McpClientOptions
import org.unitedsoftware.alexandrite.mcp.client.McpImplementation
import org.unitedsoftware.alexandrite.mcp.fake.FakeStdioServers
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import org.unitedsoftware.alexandrite.testkit.testRecords
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal const val PLUGIN = "alexandrite-mcp"

internal val QUICK = McpTimings(
    firstPause = 100.milliseconds,
    maxPause = 400.milliseconds,
    steady = 10.seconds,
    minTtl = 1.seconds,
    stopGrace = 1.seconds,
)

internal val QUICK_CLIENT = McpClientOptions(requestTimeout = 10.seconds)

/** Host paths under `/nonexistent`, with no config file. */
internal object TestPaths : HostPaths {
    override val dataRoot: Path = Path.of("/nonexistent/data")
    override val pluginDataRoot: Path = Path.of("/nonexistent/plugins")
    override val cacheRoot: Path = Path.of("/nonexistent/cache")
    override val configFile: Path? = null
    override val configTarget: Path? = null
    override val configBackup: Path? = null
    override val protected: List<Path> = emptyList()
}

/** The settings of [config] on a host with [environments]. */
internal fun settings(config: McpConfig, environments: List<ExecutionEnvironment> = emptyList()): McpSettings =
    McpSettings(config, environments, TestPaths)

/** A stdio server running the fake server in [mode]. */
internal fun fake(mode: String = "modern", block: ServerConfigBuilder.() -> Unit = {}): ServerConfig =
    ServerConfigBuilder(command = FakeStdioServers.command, args = FakeStdioServers.args(mode)).apply(block).build()

internal class ServerConfigBuilder(
    var command: String? = null,
    var args: List<String> = emptyList(),
    var env: Map<String, String> = emptyMap(),
    var url: String? = null,
    var headers: Map<String, String> = emptyMap(),
    var oauth: OAuthConfig? = null,
    var allow: List<String> = listOf("*"),
    var deny: List<String> = emptyList(),
    var risk: Map<String, String> = emptyMap(),
    var trustAnnotations: Boolean = false,
    var trusted: Boolean = false,
    var timeoutSeconds: Int = 60,
    var enabled: Boolean = true,
) {
    fun build(): ServerConfig = ServerConfig(
        command = command,
        args = args,
        env = env.mapValues { Secret(it.value) },
        url = url,
        headers = headers.mapValues { Secret(it.value) },
        oauth = oauth,
        tools = ToolsConfig(allow, deny),
        risk = risk,
        trustAnnotations = trustAnnotations,
        trusted = trusted,
        timeoutSeconds = timeoutSeconds,
        enabled = enabled,
    )
}

/** A hub of [servers] in a scope of its own, opened, drained when [block] ends. */
internal fun withHub(vararg servers: Pair<String, ServerConfig>, block: suspend Hub.() -> Unit) =
    withHub(McpConfig(servers.toMap()), block = block)

/** A hub of [config] with [records] at [clock] in a scope of its own, opened, drained when [block] ends. */
internal fun withHub(
    config: McpConfig,
    records: Records = testRecords(PLUGIN),
    clock: Clock = Clock.systemUTC(),
    block: suspend Hub.() -> Unit,
) = realTime {
    val scope = CoroutineScope(SupervisorJob())
    val hub = McpHub(
        settings(config),
        scope,
        McpImplementation("alexandrite-test", "0.0.1"),
        QUICK_CLIENT,
        System.getenv(),
        QUICK,
        records,
        clock,
        testHttpEngine(),
    )
    try {
        hub.onOpen()
        Hub(hub, this).block()
    } finally {
        hub.onDrain()
        hub.onDestroy()
        scope.cancel()
    }
}

internal class Hub(val hub: McpHub, scope: CoroutineScope) : CoroutineScope by scope {
    fun server(name: String): McpServer = checkNotNull(hub.server(name))

    val names: List<String> get() = hub.tools.value.map { it.definition.name }

    fun tool(name: String): Tool = hub.tools.value.first { it.definition.name == name }

    /** Waits until [predicate] holds of the hub. */
    suspend fun until(what: String, timeout: Duration = 20.seconds, predicate: Hub.() -> Boolean) {
        withTimeoutOrNull(timeout) {
            while (!predicate()) delay(20.milliseconds)
        } ?: throw AssertionError("Not $what: ${hub.servers.map { it.spec.name to it.status.value }}, $names")
    }

    suspend fun connected(vararg names: String) =
        until("connected") { names.all { server(it).status.value.state == ServerState.CONNECTED } }
}

internal fun realTime(limit: Duration = 60.seconds, block: suspend CoroutineScope.() -> Unit) =
    runBlocking { withTimeout(limit) { block() } }

internal fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, value) }
}

internal val ToolResult.text: String get() = content.filterIsInstance<TextPart>().joinToString("") { it.text }
