package org.unitedsoftware.alexandrite.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.sandbox.bubblewrap.AlexandriteSandboxBubblewrapIndex
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** command.exec in an agent's bubblewrap sandbox, end to end: runs on Linux with `bwrap` and `socat`. */
@EnabledOnOs(OS.LINUX)
class TelegramBubblewrapTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()

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

    @BeforeEach
    fun available() {
        var available = false
        runBlocking {
            PluginHarness.builder(AlexandriteSandboxBubblewrapIndex()).plugin(AlexandriteEngineJdkHttpIndex()).build()
                .run { available = getAll<ExecutionEnvironment>().single().available() }
        }
        assumeTrue(available, "bubblewrap cannot make a sandbox here")
    }

    @AfterTest
    fun close() {
        server.stop(0)
        app.close()
    }

    @Test
    fun `contained commands and chains run unasked, reach only listed hosts and write only in the boundary`() {
        val port = server.address.port
        val outside = Path.of("build/sandbox-test").toAbsolutePath().createDirectories().toRealPath()
        Files.deleteIfExists(outside.resolve("escaped"))
        app.model
            .reply { toolCall("command.exec", exec("curl", "-sS", "-m", "10", "http://127.0.0.1:$port/hello")) }
            .reply { toolCall("command.exec", exec("curl", "-sS", "-f", "-m", "10", "https://example.org/")) }
            .reply {
                toolCall("command.exec", exec("sh", "-c", "echo x > '$outside/escaped' ; echo x > \"\$TMPDIR/ok\""))
            }
            .reply { toolCall("command.exec", exec("sh", "-c", "touch chained && ls chained")) }
            .reply { text("Done.") }

        app.run(builder(port)) {
            app.send(app.ada, app.adaChat, "Fetch it.")
            app.message(app.adaChat, "Done.")
            idle()
        }

        val results = app.model.requests.last().history.filterIsInstance<ToolResultEntry>().map { result ->
            result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
        }
        assertEquals(4, results.size, results.toString())
        assertContains(results[0], "Exit code: 0\nhello through the proxy")
        assertContains(results[1], "The sandbox's proxy refused example.org")
        assertContains(results[2], "Read-only file system")
        assertContains(results[3], "Exit code: 0\n\$ touch chained\n\$ ls chained\nchained")
        assertFalse(Files.exists(outside.resolve("escaped")))
        assertFalse(app.fake.botMessages(app.adaChat.id).any { it.text.startsWith("Approval needed") })
    }

    @Test
    fun `a monitor runs its program in the sandbox and stops it with its processes`() {
        val outside = Path.of("build/sandbox-test").toAbsolutePath().createDirectories().toRealPath()
        val marker = "300.${System.nanoTime()}"
        val script = "printf 'MATCH started\\n'; (echo x > '$outside/monitored') 2>/dev/null || echo MATCH refused; " +
            "sleep $marker"
        app.model.reply { toolCall("command.exec", monitor(script).toString()) }
            .reply { text("Monitoring.") }
            .reply { }
        app.run(builder(server.address.port)) {
            app.send(app.ada, app.adaChat, "Monitor this process.")
            app.message(app.adaChat, "Monitoring.")
            withTimeout(20000) { app.model.awaitRequests(3) }
            idle()
            val frame = app.model.requests.last().history.filterIsInstance<UserEntry>().last()
                .parts.filterIsInstance<ContextPart>().joinToString("\n") { it.text }
            assertContains(frame, "MATCH refused")
            assertTrue(running(marker))
            val control = get<TurnControl>()
            assertTrue(
                control.stopJob(app.address(app.adaChat), control.jobs(app.address(app.adaChat)).single().id, null),
            )
            withTimeout(10000) { while (running(marker)) delay(20) }
        }
        assertFalse(Files.exists(outside.resolve("monitored")))
    }

    private fun running(marker: String): Boolean =
        ProcessHandle.allProcesses().anyMatch { it.info().commandLine().orElse("").contains(marker) }

    private fun exec(vararg argv: String): String =
        JsonObject(mapOf("argv" to JsonArray(argv.map(::JsonPrimitive)))).toString()

    private fun monitor(script: String): JsonObject = buildJsonObject {
        put("argv", JsonArray(listOf("sh", "-c", script).map(::JsonPrimitive)))
        put("background", true)
        put(
            "monitor",
            buildJsonObject {
                put("filter", "MATCH")
                put("batchSeconds", 1)
                put("intervalSeconds", 1)
                put("maxLifetimeSeconds", 60)
            },
        )
    }

    private fun builder(port: Int): PluginHarness.Builder = app.builder(directory)
        .plugin(AlexandriteSandboxBubblewrapIndex())
        .config(
            """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {
                "name": "Coder", "model": "scripted/test-model", "foregroundSeconds": 60,
                "channels": {"telegram:work": {}},
                "sandbox": {"type": "bubblewrap", "network": {"allowedDomains": ["127.0.0.1:$port"]}}
              }}
            }
            """.trimIndent(),
        )
}
