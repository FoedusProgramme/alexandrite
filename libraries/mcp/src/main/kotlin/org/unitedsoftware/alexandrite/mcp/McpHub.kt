package org.unitedsoftware.alexandrite.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.mcp.client.McpClientOptions
import org.unitedsoftware.alexandrite.mcp.client.McpImplementation
import org.unitedsoftware.alexandrite.mcp.oauth.McpOAuth
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import java.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The configured servers, connected from the open until the drain, whose tools it offers. */
@Singleton
@Contribute(ToolSource::class)
internal class McpHub internal constructor(
    settings: McpSettings,
    private val scope: CoroutineScope,
    client: McpImplementation,
    clientOptions: McpClientOptions,
    host: Map<String, String>,
    timings: McpTimings,
    records: Records,
    clock: Clock,
    engine: HttpEngine,
) : ToolSource,
    Lifecycle {
    @Inject
    constructor(
        settings: McpSettings,
        scope: PluginScope,
        info: PluginInfo,
        records: Records,
        engine: HttpEngine,
    ) : this(
        settings,
        scope,
        McpImplementation(CLIENT_NAME, info.version),
        McpClientOptions(),
        System.getenv(),
        McpTimings(),
        records,
        Clock.systemUTC(),
        engine,
    )

    private val transport =
        HttpTransport(HttpTimeouts(connect = 10.seconds, firstByte = 60.seconds, idle = 5.minutes), engine)

    val oauth: McpOAuth = McpOAuth(settings, records, transport, scope, clock) {
        server(it)?.restart()
    }

    private val shared = McpShared(transport, client, clientOptions, host, timings, oauth)
    private val published = MutableStateFlow<List<Tool>>(emptyList())
    private val byServer = LinkedHashMap<String, List<Tool>>()
    private val jobs = mutableListOf<Job>()

    val servers: List<McpServer> = settings.servers.map { spec -> McpServer(spec, shared) { publish(spec.name, it) } }

    override val namespace: String = McpNames.NAMESPACE

    override val tools: StateFlow<List<Tool>> = published.asStateFlow()

    fun server(name: String): McpServer? = servers.firstOrNull { it.spec.name == name }

    override suspend fun onOpen() {
        val enabled = servers.filter { it.spec.enabled }
        for (server in enabled) server.spec.warning?.let { logger.warn("MCP server {}: {}", server.spec.name, it) }
        if (servers.isNotEmpty()) logger.info("Connecting to {} of {} MCP servers", enabled.size, servers.size)
        oauth.open()
        synchronized(jobs) { enabled.forEach { server -> jobs += scope.launch { server.run() } } }
    }

    override suspend fun onDrain() {
        val running = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        running.forEach { it.cancel() }
        running.joinAll()
    }

    override fun onStop() {
        synchronized(jobs) { jobs.forEach { it.cancel() } }
    }

    override fun onDestroy() {
        oauth.close()
        transport.close()
    }

    private fun publish(server: String, tools: List<Tool>) {
        synchronized(byServer) {
            byServer[server] = tools
            published.value = servers.flatMap { byServer[it.spec.name].orEmpty() }
        }
    }

    private companion object {
        const val CLIENT_NAME = "alexandrite"
    }
}

private val logger = LoggerFactory.getLogger(McpHub::class.java)
