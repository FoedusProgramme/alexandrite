package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.control.agentApis
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotApi
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.getOrNull
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.turn.ChatHistory
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.SideQuestions
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.UsageReports
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The commands against a running app, which checks, writes and reports the changes over its control socket. */
class ConfigControlTest {
    @TempDir
    lateinit var directory: Path

    private val fake = FakeBotApi()

    private lateinit var cli: Commands

    private val original: String
        get() = """
            {
              "app": {"zone": "UTC"},
              "channels": {"telegram": {"instances": {"main": {
                "botToken": "${fake.token}", "apiBaseUrl": "${fake.baseUrl}", "adminUserIds": [$ADA],
                "allowedChats": [$ADA], "polling": {"timeoutSeconds": 1}
              }}}}
            }

        """.trimIndent()

    @BeforeTest
    fun setUp() {
        cli = Commands(directory, FakeMachine(sleeps = true))
        cli.config(original)
    }

    @AfterTest
    fun close() {
        fake.close()
    }

    /** Runs the app on the config file and data directory of [cli] until [block] has run beside it. */
    private fun app(block: suspend AlexandriteRuntime.() -> Unit) {
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

    @Test
    fun `the running app resolves each host API of the agent from its services`() {
        var apis: List<Any?> = emptyList()

        app {
            apis = listOf(
                services.getOrNull<TurnControl>(),
                services.getOrNull<ConversationControl>(),
                services.getOrNull<TrustControl>(),
                services.getOrNull<PermissionRules>(),
                services.getOrNull<UsageReports>(),
                services.getOrNull<ChatHistory>(),
                services.getOrNull<SideQuestions>(),
                agentApis(services),
            )
        }

        assertEquals(8, apis.filterNotNull().size)
    }

    @Test
    fun `a change goes through the running app, which writes it and says it needs a restart`() {
        val printed = mutableListOf<Printed>()
        var resolved: HostConfig? = null

        app {
            resolved = services.getOrNull<HostConfig>()
            printed += cli("config", "set", "app.language", "zh-CN")
            printed += cli("config", "set", "nope.key", "1")
            printed += cli("agent", "list")
        }

        val (set, refused) = printed
        assertNotNull(resolved)
        assertEquals(
            Printed(
                0,
                "Changed app.language in ${cli.configFile.toAbsolutePath()}.\n" +
                    "Alexandrite applies it once it starts again: run alexandrite restart.\n",
                "",
            ),
            set,
        )
        assertContains(cli.config(), "\"language\": \"zh-CN\"")
        assertEquals(1, refused.code)
        assertContains(refused.err, "nope")
        assertFalse("nope" in cli.config())
        assertEquals(original.replace("\"zone\": \"UTC\"", "\"zone\": \"UTC\", \"language\": \"zh-CN\""), cli.config())
    }

    @Test
    fun `the running app changes a key with dots by its path in brackets, and hints at them for a split one`() {
        val path = "providers.lmstudio.endpoints.local.models[\"gpt-6.1-sol\"].contextWindow"
        val printed = mutableListOf<Printed>()

        app {
            printed += cli("config", "set", path, "200000")
            printed += cli("config", "set", "providers.lmstudio.endpoints.local.models.gpt-6.1-sol.contextWindow", "1")
            printed += cli("config", "unset", "providers.lmstudio.endpoints.local.models.gpt-6.1-sol")
            printed += cli("config", "unset", "providers.lmstudio.endpoints.local.models['gpt-6.1-sol']")
        }

        val (set, split, missed, unset) = printed
        assertEquals(
            Printed(
                0,
                "Changed $path in ${cli.configFile.toAbsolutePath()}.\n" +
                    "Alexandrite applies it once it starts again: run alexandrite restart.\n",
                "",
            ),
            set,
        )
        val hint = "Keys with dots go in brackets: providers.lmstudio.endpoints.local.models[\"gpt-6.1-sol\"]"
        assertEquals(1, split.code)
        assertContains(split.err, "$hint.contextWindow.")
        assertEquals(1, missed.code)
        assertContains(missed.err, "$hint.")
        assertEquals(0, unset.code, unset.err)
        assertFalse("gpt-6" in cli.config())
    }

    @Test
    fun `a change of the floor takes effect in the running app at once, and needs no restart`() {
        val printed = mutableListOf<Printed>()
        val shared = directory.resolve("shared").toAbsolutePath()
        var check: FloorCheck? = null

        app {
            printed += cli("config", "set", "agent.floor.deny", "[\"$shared\"]")
            check = services.resolver().get(key<HardFloor>()).checkRead(shared.resolve("notes.txt"))
        }

        assertEquals(
            Printed(
                0,
                "Changed agent.floor.deny in ${cli.configFile.toAbsolutePath()}.\n" +
                    "agent.floor.deny took effect at once.\n",
                "",
            ),
            printed.single(),
        )
        assertEquals(FloorCheck.Denied("a place the operator protects"), check)
    }

    @Test
    fun `the running app checks a change with the secrets saved after it started`() {
        val printed = mutableListOf<Printed>()

        app {
            cli.machine.answers += "header-secret"
            printed += cli("secret", "set", "DOCS_TOKEN")
            printed += cli(
                "config",
                "set",
                "mcp.servers.docs",
                """{"url": "https://mcp.example.com/mcp", "headers": {"Authorization": "${'$'}{env:DOCS_TOKEN}"}, "enabled": false}""",
            )
            printed += cli("mcp", "list")
        }

        val (secret, set, listed) = printed
        assertEquals(0, secret.code, secret.err)
        assertContains(secret.out, "Alexandrite reads it once it starts again: run alexandrite restart.")
        assertEquals(0, set.code, set.err)
        assertContains(cli.config(), "\${env:DOCS_TOKEN}")
        assertFalse("header-secret" in cli.config())
        assertEquals(Printed(0, "No MCP server is configured: add one with alexandrite mcp add.\n", ""), listed)
    }

    @Test
    fun `an edit goes through the running app, and status and chats answer from it`() {
        cli.environment = mapOf("EDITOR" to "edit")
        cli.machine.attach = { command ->
            val copy = Path.of(command.last())
            Files.writeString(copy, Files.readString(copy).replace("\"UTC\"", "\"Asia/Shanghai\""))
            0
        }
        val printed = mutableListOf<Printed>()

        app {
            printed += cli("config", "edit")
            printed += cli("status")
            printed += cli("chats")
            printed += cli("pairings", "--json")
        }

        val (edited, status, chats, pairings) = printed
        assertEquals(0, edited.code, edited.err)
        assertContains(edited.out, "Changed app.zone in ${cli.configFile.toAbsolutePath()}.")
        assertContains(cli.config(), "Asia/Shanghai")
        assertEquals(0, status.code, status.err)
        assertContains(status.out, "(pid ${ProcessHandle.current().pid()}) is running, started ")
        assertContains(status.out, "Channel instances: telegram:main open")
        assertEquals(0, chats.code, chats.err)
        assertContains(chats.out, "telegram:main:$ADA")
        assertEquals(0, pairings.code, pairings.err)
        assertContains(pairings.out, "\"waiting\": []")
        assertContains(pairings.out, "\"chat\": \"telegram:main:$ADA\"")
    }

    @Test
    fun `stop asks the app that runs without a service to stop, and status then exits 69`() {
        var stopped: Printed? = null
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
                        launch(Dispatchers.IO) {
                            awaitControlSocket(cli.dataDir.resolve("control.sock"))
                            stopped = cli("stop")
                        }
                    }
                }
            },
        )

        assertEquals(0, code, err.toString(Charsets.UTF_8))
        val printed = assertNotNull(stopped)
        assertEquals(0, printed.code, printed.err)
        assertTrue(printed.out.startsWith("Stopped Alexandrite "), printed.out)
        assertEquals(ExitCode.UNAVAILABLE, cli("status").code)
    }

    private companion object {
        const val ADA = 1001L
    }
}
