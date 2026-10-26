package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.tui.Key
import org.unitedsoftware.alexandrite.app.tui.ScriptedConsole
import org.unitedsoftware.alexandrite.app.tui.typed
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMenuTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    private val original = """
        {
          "providers": {"deepseek": {"endpoints": {"deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_API_KEY}"}}}},
          "agent": {"agents": {"main": {"name": "Main", "description": "Helps", "model": "deepseek/deepseek-chat"}}}
        }

    """.trimIndent()

    @BeforeTest
    fun setUp() {
        cli = Commands(directory, FakeMachine(interactive = true), environment = mapOf("DEEPSEEK_API_KEY" to "sk-test"))
        cli.config(original)
    }

    private fun file(): JsonObject = Json.parseToJsonElement(cli.config()).jsonObject

    /** Runs the menu on a terminal that presses [keys], and returns the terminal. */
    private fun menu(keys: List<Key>, code: Int = 0): ScriptedConsole {
        val console = ScriptedConsole(keys, width = 200)
        cli.machine.console = console
        val printed = cli("config")
        assertEquals(code, printed.code, printed.out + printed.err + console.screen.lines().joinToString("\n"))
        assertEquals("", printed.out + printed.err)
        return console
    }

    /** The keys that choose the [number]th choice of a list. */
    private fun choose(number: Int): List<Key> = listOf(Key.Typed("$number"), Key.Enter)

    private val done = listOf(Key.Up, Key.Enter)

    @Test
    fun `an agent's default model is chosen among the models its endpoint lists`() {
        cli.machine.web["https://api.deepseek.com/models"] =
            Fetched(200, """{"data": [{"id": "deepseek-chat"}, {"id": "deepseek-reasoner"}]}""")

        val console = menu(
            choose(1) + Key.Enter + choose(2) + listOf(Key.Down, Key.Enter) + Key.Escape + Key.Escape + done,
        )
        val screen = console.screen.lines()

        assertEquals("\"deepseek/deepseek-reasoner\"", "${file().at("agent.agents.main.model")}")
        assertEquals("Bearer sk-test", cli.machine.fetched.single().second["Authorization"])
        assertContains(screen, "✓  Changed agent.agents.main.model in ${cli.configFile}.")
        assertContains(screen, "│  Alexandrite applies it when it starts.")
        assertTrue(
            console.frames.any { frame ->
                frame.any { "main (Main · deepseek/deepseek-chat · no channel)" in it }
            },
        )
        assertEquals("└  Done.", screen.last())
    }

    @Test
    fun `an agent is added with a model, given a permission mode and removed`() {
        cli.machine.web["https://api.deepseek.com/models"] = Fetched(200, """{"data": [{"id": "deepseek-chat"}]}""")

        val screen = menu(
            choose(1) + choose(2) + typed("helper") + Key.Enter + typed("Answers") + Key.Enter + Key.Enter +
                choose(2) + choose(3) + choose(3) + choose(5) + Key.Typed("y") + Key.Escape + done,
        ).screen.lines()

        assertContains(screen, "✓  Changed agent.agents.helper.permission.mode in ${cli.configFile}.")
        assertEquals(2, screen.count { it == "✓  Changed agent.agents.helper in ${cli.configFile}." }, "$screen")
        assertNull(file().at("agent.agents.helper"))
    }

    @Test
    fun `an endpoint is added with its key saved as a secret, and gives the agent its model`() {
        cli.machine.web["https://llm.example.com/v1/models"] = Fetched(200, """{"data": [{"id": "local-coder"}]}""")

        menu(
            choose(2) + choose(2) + choose(5) + Key.Enter + typed("https://llm.example.com/v1") + Key.Enter +
                typed("sk-local") + Key.Enter + Key.Enter + Key.Escape + done,
        )

        assertEquals(
            """{"baseUrl":"https://llm.example.com/v1","apiKey":"${'$'}{env:OPENAI_COMPATIBLE_API_KEY}"}""",
            "${file().at("providers.openai-compatible.endpoints.compatible")}",
        )
        assertEquals("\"compatible/local-coder\"", "${file().at("agent.agents.main.model")}")
        assertEquals("OPENAI_COMPATIBLE_API_KEY=sk-local\n", Files.readString(cli.secrets))
        assertEquals("Bearer sk-local", cli.machine.fetched.single().second["Authorization"])
    }

    @Test
    fun `a Telegram instance's trusted users are set, and removing it detaches the agents`() {
        val token = "123456:" + "a".repeat(35)
        cli.environment += "BOT_TOKEN" to token
        cli.config(
            original.replace(
                "\"model\": \"deepseek/deepseek-chat\"",
                "\"model\": \"deepseek/deepseek-chat\", \"channels\": {\"telegram:main\": {}}",
            ).replace(
                "\"agent\":",
                "\"channels\": {\"telegram\": {\"instances\": {\"main\": " +
                    "{\"botToken\": \"${'$'}{env:BOT_TOKEN}\", \"adminUserIds\": [1001]}}}},\n  \"agent\":",
            ),
        )

        val screen = menu(
            choose(3) + Key.Enter + choose(2) + typed("1002, 1003") + Key.Enter + choose(3) + Key.Typed("y") +
                Key.Escape + done,
        ).screen.lines()

        assertContains(screen, "✓  Changed channels.telegram.instances.main.trustedUserIds in ${cli.configFile}.")
        assertNull(file().at("channels.telegram.instances.main"))
        assertNull(file().at("agent.agents.main.channels.telegram:main"))
    }

    @Test
    fun `the language and zone are set together`() {
        menu(choose(6) + typed("zh-CN") + Key.Enter + Key.Enter + done)

        assertEquals("""{"language":"zh-CN","zone":"Europe/Berlin"}""", "${file().at("app")}")
    }

    @Test
    fun `an MCP server is added and removed`() {
        val keys = choose(4) + Key.Enter + typed("docs") + Key.Enter + Key.Enter +
            typed("https://mcp.example.com/mcp") + Key.Enter + Key.Enter + Key.Typed("y") + Key.Escape + done
        val console = ScriptedConsole(keys, width = 200)
        cli.machine.console = console

        val printed = cli("config")

        assertEquals(0, printed.code, printed.err + console.screen.lines())
        val screen = console.screen.lines()
        assertEquals(2, screen.count { it == "✓  Changed mcp.servers.docs in ${cli.configFile}." }, "$screen")
        assertContains(screen, "◇  Remove the MCP server docs?")
        assertTrue(console.frames.any { frame -> frame.any { "docs (https://mcp.example.com/mcp)" in it } })
        assertNull(file().at("mcp.servers.docs"), cli.config())
    }

    @Test
    fun `a heartbeat is turned on and given active hours`() {
        val screen = menu(
            choose(5) + Key.Enter + Key.Enter + typed("2h") + Key.Enter + choose(2) + typed("08:00-23:00") +
                Key.Enter + Key.Escape + Key.Escape + done,
        ).screen.lines()

        assertEquals(
            """{"every":"2h","activeHours":"08:00-23:00"}""",
            "${file().at("automations.heartbeat.agents.main")}",
        )
        assertContains(screen, "✓  Changed automations.heartbeat.agents.main.activeHours in ${cli.configFile}.")
    }

    @Test
    fun `Esc backs out of a prompt and the screens without writing, and Ctrl-C stops the menu`() {
        menu(choose(1) + Key.Enter + choose(1) + typed("Other") + Key.Escape + Key.Escape + Key.Escape + done)
        val stopped = menu(choose(1) + Key.Interrupt, code = 1).screen.lines()

        assertEquals(original, cli.config())
        assertEquals("└  Stopped at your request.", stopped.last())
    }

    @Test
    fun `without a terminal, with --plain or with --help config prints its usage`() {
        val usage = "alexandrite: config needs one of get, set, unset, edit, check, path.\n\n" +
            "Usage: alexandrite config\n\nChange the config in a menu on the terminal."
        val terminal = cli("config")
        cli.machine.console = ScriptedConsole()
        val plain = cli("config", "--plain")
        val help = cli("config", "--help")
        val bare = Commands(directory)("config")

        for (printed in listOf(terminal, plain, bare)) {
            assertEquals(ExitCode.USAGE, printed.code)
            assertTrue(printed.err.startsWith(usage), printed.err)
        }
        assertEquals(0, help.code)
        assertContains(help.out, "Esc goes")
        assertContains(cli("--help").out, "change the config in a menu on the terminal")
    }

    @Test
    fun `another key with dots goes in brackets, and a malformed one is asked again`() {
        cli.config("""{"app": {"zone": "UTC"}}""")
        val path = "providers.lmstudio.endpoints.local.models[\"gpt-6.1-sol\"].contextWindow"
        val console = menu(
            choose(7) + typed("providers.lmstudio.endpoints.local.models[gpt-6.1-sol]") + Key.Enter + Key.Clear +
                typed(path) + Key.Enter + typed("200000") + Key.Enter + done,
        )

        assertTrue(console.frames.any { frame -> frame.any { "is no config key (Malformed config path" in it } })
        assertContains(console.screen.lines(), "✓  Changed $path in ${cli.configFile}.")
        assertEquals("200000", "${file().at(path)}")
    }

    @Test
    fun `another key set in the running app says whether it took effect at once or needs a restart`() {
        cli.config("""{"app": {"zone": "UTC"}}""")
        val shared = directory.resolve("shared").toAbsolutePath()
        val console = ScriptedConsole(
            choose(7) + typed("agent.floor.deny") + Key.Enter + typed("[\"$shared\"]") + Key.Enter +
                choose(7) + typed("app.language") + Key.Enter + typed("zh-CN") + Key.Enter + done,
            width = 200,
        )
        cli.machine.console = console
        var printed: Printed? = null

        app { printed = cli("config") }

        assertEquals(0, printed?.code, "$printed ${console.screen.lines()}")
        val screen = console.screen.lines()
        assertContains(screen, "│  agent.floor.deny is not set.")
        assertContains(screen, "│  agent.floor.deny took effect at once.")
        assertContains(screen, "!  Alexandrite applies app.language once it starts again.")
        assertContains(screen, "!  Alexandrite applies it once it starts again: run alexandrite restart.")
        assertContains(cli.config(), "\"language\": \"zh-CN\"")
    }

    /** Runs the app on the config file and data directory of [cli] until [block] has run beside it. */
    private fun app(block: suspend () -> Unit) {
        val err = ByteArrayOutputStream()
        val code = run(
            listOf("--config", "${cli.configFile}", "--data-dir", "${cli.dataDir}"),
            emptyMap(),
            "Linux",
            cli.home,
            PrintStream(ByteArrayOutputStream(), true),
            PrintStream(err, true),
            execute = { spec, main ->
                AlexandriteRuntime.run(spec) {
                    coroutineScope {
                        launch { main() }
                        withContext(Dispatchers.IO) {
                            awaitControlSocket(cli.dataDir.resolve("control.sock"))
                            block()
                        }
                        stop(StopRequest.shutdown("requested by the test"))
                    }
                }
            },
        )
        assertEquals(0, code, err.toString(Charsets.UTF_8))
    }
}
