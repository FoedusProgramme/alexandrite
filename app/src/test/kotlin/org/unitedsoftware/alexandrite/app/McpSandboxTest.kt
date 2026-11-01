package org.unitedsoftware.alexandrite.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.mcp.AlexandriteMcpIndex
import org.unitedsoftware.alexandrite.mcp.fake.FakeStdioServers
import org.unitedsoftware.alexandrite.sandbox.bubblewrap.AlexandriteSandboxBubblewrapIndex
import org.unitedsoftware.alexandrite.sandbox.seatbelt.AlexandriteSandboxSeatbeltIndex
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Seatbelt admits the proxy's port on 127.0.0.1 only, which a JVM on its dual-stack sockets does not use by default. */
private const val IPV4 = "-Djava.net.preferIPv4Stack=true"

/** An MCP stdio server in the platform's sandbox: Seatbelt on macOS, bubblewrap on Linux. */
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

    @AfterTest
    fun stop() = server.stop(0)

    private fun quoted(text: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    @Test
    fun `a sandboxed server writes only in its directory and reaches only its listed hosts`() {
        val root = directory.toRealPath()
        val cwd = root.resolve("server").createDirectories()
        val outside = root.resolve("outside").createDirectories()
        val port = server.address.port
        val harness = PluginHarness.builder(AlexandriteMcpIndex()).plugin(AlexandriteEngineJdkHttpIndex())
            .plugin(AlexandriteSandboxSeatbeltIndex())
            .plugin(AlexandriteSandboxBubblewrapIndex())
            .config(
                """{"servers": {"files": {"command": ${quoted(FakeStdioServers.command)}, "args": ${
                    JsonArray((listOf(IPV4) + FakeStdioServers.args("sandboxed")).map(::JsonPrimitive))
                }, "cwd": ${quoted(cwd.toString())}, "sandbox": {"type": "auto", "network": {"allowedDomains": [
                    "127.0.0.1:$port"]}}}}}""",
            )
            .build()
        runBlocking {
            harness.run {
                assumeTrue(getAll<ExecutionEnvironment>().any { it.available() }, "no sandbox here")
                val source = getAll<ToolSource>().single { it.namespace == "mcp" }
                withTimeout(30.seconds) {
                    while (source.tools.value.none { it.definition.name == "mcp.files.fetch" }) delay(20.milliseconds)
                }
                val tools = source.tools.value.associateBy { it.definition.name }
                suspend fun Tool.call(vararg arguments: Pair<String, String>): String =
                    execute(JsonObject(arguments.associate { it.first to JsonPrimitive(it.second) }), testToolContext())
                        .content.filterIsInstance<TextPart>().joinToString("") { it.text }

                assertEquals("done", tools.getValue("mcp.files.write").call("path" to "$cwd/inside"))
                assertContains(tools.getValue("mcp.files.write").call("path" to "$outside/escaped"), "failed")
                assertEquals(
                    "200 hello through the proxy",
                    tools.getValue("mcp.files.fetch").call("url" to "http://127.0.0.1:$port/hello"),
                )
                assertContains(
                    tools.getValue("mcp.files.fetch").call("url" to "http://example.org/"),
                    "403 Alexandrite's sandbox proxy refused example.org",
                )
            }
        }
        assertEquals("written", cwd.resolve("inside").readText())
        assertFalse(Files.exists(outside.resolve("escaped")))
    }
}
