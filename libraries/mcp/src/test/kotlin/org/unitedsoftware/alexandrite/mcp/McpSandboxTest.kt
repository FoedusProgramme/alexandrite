package org.unitedsoftware.alexandrite.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.sandbox.DEFAULT_PROTECT
import org.unitedsoftware.alexandrite.mcp.fake.FakeStdioServers
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingEnvironment
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Stdio servers in a sandbox: launched through its environment with their boundary, reaching hosts by a proxy. */
class McpSandboxTest {
    @TempDir
    lateinit var directory: Path

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/hello") { exchange ->
            val body = "hello through the proxy".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val port = server.address.port

    @AfterTest
    fun stop() = server.stop(0)

    private fun quoted(text: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    private val launch: String = """"command": ${quoted(FakeStdioServers.command)}, "args": ${
        JsonArray(FakeStdioServers.args("sandboxed").map(::JsonPrimitive))
    }"""

    private fun harness(server: String, environment: RecordingEnvironment? = RecordingEnvironment("bubblewrap")) =
        PluginHarness.builder(AlexandriteMcpIndex())
            .apply { environment?.let { executionEnvironment(it) } }
            .config("""{"servers": {"files": {$launch, $server}}}""")
            .build()

    private fun problem(
        server: String,
        environment: RecordingEnvironment? = RecordingEnvironment("bubblewrap"),
    ): String {
        val error = assertFailsWith<RuntimeStartException> { runBlocking { harness(server, environment).run {} } }
        return error.problems.single().message
    }

    @Test
    fun `a sandboxed server starts in its environment, writing in its directory, reaching listed hosts by a proxy`() {
        val cwd = directory.resolve("cwd").toFile().apply { mkdirs() }.toPath()
        val cache = directory.resolve("cache")
        val environment = RecordingEnvironment("bubblewrap")
        var proxy = 0
        runBlocking {
            harness(
                """"cwd": ${quoted(cwd.toString())}, "sandbox": {"type": "bubblewrap", "allowWrite": [${
                    quoted(cache.toString())
                }], "network": {"allowedDomains": ["127.0.0.1:$port"]}}""",
                environment,
            ).run {
                val source = getAll<ToolSource>().single()
                withTimeout(20.seconds) {
                    while (source.tools.value.none { it.definition.name == "mcp.files.fetch" }) delay(20.milliseconds)
                }
                val fetch = source.tools.value.single { it.definition.name == "mcp.files.fetch" }
                val listed = fetch.execute(
                    JsonObject(mapOf("url" to JsonPrimitive("http://127.0.0.1:$port/hello"))),
                    testToolContext(),
                )
                val other = fetch.execute(
                    JsonObject(mapOf("url" to JsonPrimitive("http://example.org/"))),
                    testToolContext(),
                )

                assertEquals("200 hello through the proxy", listed.text)
                assertContains(other.text, "403 Alexandrite's sandbox proxy refused example.org")
                val boundary = environment.launches.single().boundary
                assertEquals(listOf(cwd, cache), boundary.writable)
                assertEquals(DEFAULT_PROTECT - "alexandrite", boundary.protectedNames)
                assertTrue(get<HostPaths>().dataRoot in boundary.readDenied)
                assertTrue(get<HostPaths>().dataRoot in boundary.writeDenied)
                proxy = assertIs<NetworkAccess.Proxy>(boundary.network).port
                assertEquals("http://127.0.0.1:$proxy", boundary.environment["HTTP_PROXY"])
            }
        }
        assertFalse(listens(proxy))
    }

    /**
     * Whether something accepts connections on [port]. Linux may connect a client to itself when it picks the closed
     * port as its own ephemeral one, which is no listener.
     */
    private fun listens(port: Int): Boolean = try {
        Socket(InetAddress.getLoopbackAddress(), port).use { it.localPort != it.port }
    } catch (e: IOException) {
        false
    }

    @Test
    fun `a server runs on the host unless its sandbox names an environment, and without hosts it has no network`() {
        val environment = RecordingEnvironment("bubblewrap")
        runBlocking {
            harness(""""sandbox": {"allowWrite": ["/tmp"]}""", environment).run {
                val source = getAll<ToolSource>().single()
                withTimeout(20.seconds) { while (source.tools.value.isEmpty()) delay(20.milliseconds) }
            }
        }
        assertEquals(emptyList(), environment.launches)

        runBlocking {
            harness(""""sandbox": {"type": "bubblewrap"}""", environment).run {
                val source = getAll<ToolSource>().single()
                withTimeout(20.seconds) { while (source.tools.value.isEmpty()) delay(20.milliseconds) }
            }
        }
        val boundary = environment.launches.single().boundary
        assertEquals(NetworkAccess.None, boundary.network)
        assertEquals(emptyList(), boundary.writable)
    }

    @Test
    fun `without any sandbox contributed, auto runs the server on the host and a named sandbox fails the start`() {
        runBlocking {
            harness(""""sandbox": {"type": "auto"}""", environment = null).run {
                val source = getAll<ToolSource>().single()
                withTimeout(20.seconds) { while (source.tools.value.isEmpty()) delay(20.milliseconds) }
            }
        }
        assertEquals(
            "Invalid config at 'mcp.servers.files.sandbox.type': no plugin contributes the sandbox 'bubblewrap'. " +
                "Sandboxes: none.",
            problem(""""sandbox": {"type": "bubblewrap"}""", environment = null),
        )
    }

    @Test
    fun `a sandbox's config problems fail the start, naming their path`() {
        assertEquals(
            "Invalid config at 'mcp.servers.files.sandbox.type': no plugin contributes the sandbox 'docker'. " +
                "Sandboxes: bubblewrap.",
            problem(""""sandbox": {"type": "docker"}"""),
        )
        assertEquals(
            "Invalid config at 'mcp.servers.files.sandbox.type': the sandbox 'bubblewrap' is not available on this host",
            problem(""""sandbox": {"type": "bubblewrap"}""", RecordingEnvironment("bubblewrap", available = false)),
        )
        assertContains(
            problem(""""sandbox": {"type": "bubblewrap", "network": {"allowedDomains": ["a b"]}}"""),
            "'mcp.servers.files.sandbox.network.allowedDomains': 'a b' is no host",
        )
        assertContains(
            problem(""""sandbox": {"type": "bubblewrap", "allowWrite": ["relative"]}"""),
            "'mcp.servers.files.sandbox.allowWrite': 'relative' is relative",
        )
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking {
                PluginHarness.builder(AlexandriteMcpIndex())
                    .config(
                        """{"servers": {"web": {"url": "https://mcp.example.test/mcp", "sandbox": {"type": "auto"}}}}""",
                    )
                    .build().run {}
            }
        }
        assertEquals(
            "Invalid config at 'mcp.servers.web.sandbox': only a server with a command runs in a sandbox.",
            error.problems.single().message,
        )
    }
}
