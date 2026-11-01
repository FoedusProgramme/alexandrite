package org.unitedsoftware.alexandrite.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.mcp.fake.FakeStdioServers
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The plugin in a runtime: what it contributes, and the config problems that fail the start. */
class McpPluginTest {
    private fun harness(config: String): PluginHarness = PluginHarness.builder(
        AlexandriteMcpIndex(),
    ).plugin(AlexandriteEngineJdkHttpIndex()).config(config).environment(mapOf("FAKE_TOKEN" to "tok-1")).build()

    private val launch: String = """"command": ${quoted(FakeStdioServers.command)}, "args": ${
        JsonArray(FakeStdioServers.args("modern").map(::JsonPrimitive))
    }"""

    private fun quoted(text: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    /** The one config problem that the start fails with for the server `files` of [server] and the rest [plugin]. */
    private fun problem(server: String, name: String = "files", plugin: String = ""): String {
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking { harness("""{"servers": {"$name": {$server}}$plugin}""").run {} }
        }
        return error.problems.single().message
    }

    @Test
    fun `the plugin offers a configured server's tools and the mcp command`() {
        runBlocking {
            harness("""{"servers": {"files": {$launch, "env": {"TOKEN": "${'$'}{env:FAKE_TOKEN}"}}}}""").run {
                assertEquals(listOf("mcp"), getAll<CommandHandler>().flatMap { it.commands }.map { it.name })
                val source = getAll<ToolSource>().single()
                assertEquals("mcp", source.namespace)
                withTimeout(20.seconds) { while (source.tools.value.size < 8) delay(20.milliseconds) }
                assertEquals("mcp.files.echo", source.tools.value.first().definition.name)
            }
        }
    }

    private fun assertProblem(path: String, word: String, problem: String) {
        assertContains(problem, "Invalid config at '$path': ")
        assertContains(problem, word)
    }

    @Test
    fun `a server's config problems fail the start, naming their path`() {
        assertProblem("mcp.servers.files", "not both", problem("""$launch, "url": "https://mcp.example.test/mcp""""))
        assertProblem("mcp.servers.files", "needs a command", problem(""""trusted": true"""))
        assertProblem("mcp.servers.Files", "no server name", problem(launch, name = "Files"))
        assertProblem("mcp.servers.files.risk.get_*", "no risk", problem("""$launch, "risk": {"get_*": "cheap"}"""))
        assertProblem("mcp.servers.files.command", "empty", problem(""""command": " """"))
        assertProblem("mcp.servers.files.headers", "url", problem("""$launch, "headers": {"X-Key": "k"}"""))
        assertProblem(
            "mcp.servers.files.env",
            "command",
            problem(""""url": "https://mcp.example.test/mcp", "env": {"A": "b"}"""),
        )
        assertProblem("mcp.servers.files.url", "http or https", problem(""""url": "ftp://mcp.example.test/mcp""""))
        assertProblem("mcp.servers.files.cwd", "absolute", problem("""$launch, "cwd": "work""""))
        assertProblem("mcp.servers.files.timeoutSeconds", "positive", problem("""$launch, "timeoutSeconds": 0"""))
    }

    @Test
    fun `OAuth config problems fail the start, naming their path`() {
        val web = """"url": "https://mcp.example.test/mcp""""
        assertProblem("mcp.servers.files.oauth", "url", problem("""$launch, "oauth": {}"""))
        assertProblem(
            "mcp.servers.files.oauth.clientSecret",
            "clientId",
            problem("""$web, "oauth": {"clientSecret": "s"}"""),
        )
        assertProblem(
            "mcp.servers.files.oauth",
            "not both",
            problem("""$web, "headers": {"authorization": "Bearer t"}, "oauth": {}"""),
        )
        assertProblem(
            "mcp.servers.files.oauth.scopes[1]",
            "no scope",
            problem("""$web, "oauth": {"scopes": ["read", "a b"]}"""),
        )
        val origins = listOf(
            """"http://bot.example.com", "callbackPort": 8080""",
            """"https://bot.example.com/x", "callbackPort": 1""",
        )
        for (origin in origins) {
            assertProblem("mcp.publicOrigin", "https origin", problem(web, plugin = """, "publicOrigin": $origin"""))
        }
        assertProblem(
            "mcp.callbackPort",
            "publicOrigin",
            problem(web, plugin = """, "publicOrigin": "https://bot.example.com""""),
        )
        assertProblem("mcp.callbackPort", "1 to 65535", problem(web, plugin = """, "callbackPort": 70000"""))
    }
}
