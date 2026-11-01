package org.unitedsoftware.alexandrite.agent.sandbox

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.assertStartFails
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.permission.INSTRUCTION_NAMES
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.Contained
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingEnvironment
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SandboxesTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path by lazy { directory.toRealPath() }
    private val data: Path by lazy { root.resolve("data") }
    private val configFile: Path by lazy { root.resolve("config/alexandrite.json") }
    private val project: Path by lazy { root.resolve("project").createDirectories() }
    private val elsewhere: Path by lazy { root.resolve("elsewhere").createDirectories() }
    private val extra: Path by lazy { root.resolve("extra") }
    private val hidden: Path by lazy { root.resolve("hidden") }
    private val asked: Path by lazy { root.resolve("asked") }
    private val turn = testTurn { agent(AgentId("coder")).actor(testUser("operator", isOperator = true)) }

    private fun harness(sandbox: String, environment: RecordingEnvironment? = RecordingEnvironment("seatbelt")) =
        agentHarness(
            """
            {
              "trustedFolders": [${JsonPrimitive(project.toString())}],
              "floor": {"deny": ["alexandrite", ${JsonPrimitive(
                hidden.toString(),
            )}], "ask": [${JsonPrimitive(asked.toString())}]},
              "agents": {"coder": {"description": "Codes.", "sandbox": $sandbox}}
            }
            """.trimIndent(),
        ) {
            dataRoot(data)
            configFile(configFile)
            environment?.let { executionEnvironment(it) }
        }

    private fun PluginHarness.Running.sandboxes(): Sandboxes = get<Sandboxes>()

    // Choosing the environment.

    @Test
    fun `an agent runs commands on the host unless it names a sandbox`() {
        harness("""{"type": "host"}""").execute { assertNull(sandboxes().sandbox(turn, null)) }
        harness("{}").execute { assertNull(sandboxes().sandbox(turn, null)) }
        harness("""{"type": "seatbelt"}""").execute {
            assertEquals("seatbelt", assertNotNull(sandboxes().sandbox(turn, null)).environment.name)
        }
    }

    @Test
    fun `auto takes a sandbox that is available on this host, and falls back to the host when none is`() {
        val platform = "jail"
        harness("""{"type": "auto"}""", RecordingEnvironment(platform)).execute {
            assertEquals(platform, sandboxes().sandbox(turn, null)?.environment?.name)
        }
        harness("""{"type": "auto"}""", RecordingEnvironment(platform, available = false)).execute {
            assertNull(sandboxes().sandbox(turn, null))
        }
    }

    @Test
    fun `a named sandbox that no plugin contributes, or that is not available, fails the start`() {
        harness("""{"type": "docker"}""").assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.sandbox.type': no plugin contributes the sandbox 'docker'. " +
                "Sandboxes: seatbelt.",
        )
        harness("""{"type": "seatbelt"}""", RecordingEnvironment("seatbelt", available = false)).assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.sandbox.type': the sandbox 'seatbelt' is not available on this host",
        )
    }

    @Test
    fun `without any sandbox contributed, auto runs on the host and a named sandbox fails the start`() {
        harness("""{"type": "auto"}""", environment = null).execute { assertNull(sandboxes().sandbox(turn, null)) }
        harness("""{"type": "seatbelt"}""", environment = null).assertStartFails(
            StartStage.GRAPH,
            "Invalid config at 'agent.agents.coder.sandbox.type': no plugin contributes the sandbox 'seatbelt'. " +
                "Sandboxes: none.",
        )
    }

    // The boundary.

    @Test
    fun `a call writes in the workspace, its conversation, the trusted folder it works in, temp and allowWrite`() {
        val config = """{"type": "seatbelt", "allowWrite": [${JsonPrimitive(extra.toString())}]}"""
        harness(config).execute {
            val paths = get<HostPaths>()
            val boundary = assertNotNull(sandboxes().sandbox(turn, null)).boundary(listOf(project.resolve("src")))
            val workspace = paths.dataRoot.resolve("workspaces/coder")
            val temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()

            assertEquals(5, boundary.writable.size, boundary.writable.toString())
            assertTrue(
                boundary.writable.containsAll(listOf(workspace, project, temp, extra)),
                boundary.writable.toString(),
            )
            assertTrue(boundary.writable.any { it.startsWith(paths.dataRoot) && "test" in it.toString() })
            assertFalse(
                elsewhere in assertNotNull(sandboxes().sandbox(turn, null)).boundary(listOf(elsewhere)).writable,
            )
            assertEquals(NetworkAccess.None, boundary.network)
        }
    }

    @Test
    fun `protected names, Alexandrite's places, the floor's places and denyRead bound the call`() {
        val config = """{"type": "seatbelt", "denyRead": [${JsonPrimitive(extra.toString())}]}"""
        harness(config).execute {
            val paths = get<HostPaths>()
            val sandbox = assertNotNull(sandboxes().sandbox(turn, null))
            val boundary = sandbox.boundary(listOf(project))

            assertContains(boundary.protectedNames, ".git/hooks")
            assertContains(boundary.protectedNames, ".bashrc")
            assertTrue(boundary.writeDenied.containsAll(listOf(paths.dataRoot, configFile.parent, asked)))
            assertTrue(boundary.readDenied.containsAll(listOf(paths.dataRoot, hidden, asked, extra)))
            assertContains(boundary.readable, paths.dataRoot.resolve("workspaces/coder"))
            val opened = assertNotNull(
                sandboxes().sandbox(turn, Clearance(ClearanceKind.HUMAN)),
            ).boundary(listOf(project))
            assertFalse(asked in opened.readDenied || asked in opened.writeDenied)
        }
    }

    @Test
    fun `instruction places are closed to a call's programs unless a person allowed the call`() {
        harness("""{"type": "seatbelt", "protect": [".npmrc"]}""").execute {
            val closed = assertNotNull(sandboxes().sandbox(turn, null)).boundary(listOf(project))
            val opened = assertNotNull(
                sandboxes().sandbox(turn, Clearance(ClearanceKind.HUMAN)),
            ).boundary(listOf(project))
            val persona = configFile.resolveSibling("agents/coder")

            assertTrue(closed.writeDenied.containsAll(listOf(configFile, persona, configFile.resolveSibling("skills"))))
            assertTrue(closed.protectedNames.containsAll(listOf("AGENTS.md", "CLAUDE.md", ".claude/skills")))
            assertFalse(persona in opened.writeDenied || configFile in opened.writeDenied)
            assertEquals(listOf(".npmrc"), opened.protectedNames)
        }
    }

    @Test
    fun `protect replaces the default list`() {
        harness("""{"type": "seatbelt", "protect": [".npmrc", "~/notes"]}""").execute {
            val boundary = assertNotNull(sandboxes().sandbox(turn, null)).boundary(emptyList())

            assertEquals(listOf(".npmrc") + INSTRUCTION_NAMES, boundary.protectedNames)
            assertTrue(boundary.writeDenied.any { it.endsWith("notes") })
            assertFalse(get<HostPaths>().dataRoot in boundary.writeDenied)
        }
    }

    @Test
    fun `an opened network goes through a proxy that the program's variables name`() {
        harness("""{"type": "seatbelt"}""").execute {
            val sandbox = assertNotNull(sandboxes().sandbox(turn, null))
            sandbox.open(sandbox.boundary(emptyList()), listOf("example.com")).use { lease ->
                val port = (lease.boundary.network as NetworkAccess.Proxy).port

                assertEquals("http://127.0.0.1:$port", lease.boundary.environment["HTTPS_PROXY"])
                assertEquals("http://127.0.0.1:$port", lease.boundary.environment["http_proxy"])
                assertEquals("", lease.boundary.environment["NO_PROXY"])
            }
            assertNull(sandbox.hostProblem(listOf("example.com", "*.example.org:8443", "127.0.0.1")))
            assertContains(assertNotNull(sandbox.hostProblem(listOf("http://example.com"))), "is no host")
        }
    }

    // What the gate learns.

    @Test
    fun `a contained call asks for the hosts that neither the config nor the session allows`() {
        harness("""{"type": "seatbelt", "network": {"allowedDomains": ["*.example.com"]}}""").execute {
            val review = contained("api.example.com", "example.org:8080")

            val containment = assertNotNull(sandboxes().containment(turn, review))

            assertTrue(containment.clearable)
            assertEquals(listOf("example.org:8080"), containment.hosts)
            assertNull(sandboxes().containment(turn, CallReview.builder().build()))
        }
    }

    @Test
    fun `a call with variables, or of an agent that does not auto-allow, is not clearable`() {
        harness("""{"type": "seatbelt"}""").execute {
            val segment = CommandSegment(listOf("make"), project, null, null, environment = mapOf("A" to "1"))
            val preview = ReviewPreview.Command(listOf("env", "A=1", "make"), project, null, false, listOf(segment))
            val review = CallReview.builder().preview(preview).contained(Contained("seatbelt", boundary())).build()

            assertFalse(assertNotNull(sandboxes().containment(turn, review)).clearable)
        }
        harness("""{"type": "seatbelt", "autoAllow": false}""").execute {
            assertFalse(assertNotNull(sandboxes().containment(turn, contained())).clearable)
        }
    }

    @Test
    fun `sandbox settings are checked with the config`() {
        val wrong = listOf(
            """{"type": "Seat Belt"}""" to "agents.coder.sandbox.type must be \"host\", \"auto\" or the name of a " +
                "sandbox, such as \"seatbelt\"",
            """{"protect": ["../up"]}""" to "agents.coder.sandbox.protect[0] is neither \"alexandrite\", a path, " +
                "nor a relative path without \".\" or \"..\"",
            """{"network": {"allowedDomains": ["http://x"]}}""" to "agents.coder.sandbox.network.allowedDomains[0] " +
                "is no host: write a name such as \"example.com\" or \"*.example.com\", or an address such as " +
                "\"127.0.0.1\", each with an optional \":port\"",
        )
        for ((sandbox, message) in wrong) {
            agentHarness("""{"agents": {"coder": {"description": "Codes.", "sandbox": $sandbox}}}""")
                .assertStartFails(StartStage.CONFIG, "Invalid config at 'agent': $message")
        }
    }

    private fun boundary() = Boundary(listOf(project))

    private fun contained(vararg hosts: String) =
        CallReview.builder().contained(Contained("seatbelt", boundary(), hosts.toList())).build()
}
