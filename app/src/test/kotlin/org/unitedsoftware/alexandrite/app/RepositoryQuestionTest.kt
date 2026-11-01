package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.engine.swinghtml.AlexandriteEngineSwingHtmlIndex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.tools.AlexandriteToolsIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class RepositoryQuestionTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val repository: Path get() = root.resolve("repo")
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val greeter: Path get() = repository.resolve("src/main/kotlin/Greeter.kt")

    private val config = """
        {
          "previewIntervalMillis": 0,
          "floor": {"deny": ["alexandrite"]},
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
    """.trimIndent()

    private fun write(file: Path, text: String) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun arguments(vararg values: Pair<String, Path>): String =
        JsonObject(values.associate { (name, path) -> name to JsonPrimitive("$path") }).toString()

    private val ToolResultEntry.text: String get() = content.joinToString("") { (it as TextPart).text }

    @Test
    fun `an operator asks about a repository and the agent answers from file reads, with a protected read refused`() {
        write(repository.resolve("README.md"), "# Greeter\n")
        write(greeter, "package demo\n\nfun greet(name: String) = \"Hello, \$name!\"\n")
        val model = ScriptedModel()
            .reply { toolCall("fs.glob", """{"pattern": "**/*.kt", "path": "$repository"}""") }
            .reply { toolCall("fs.grep", """{"pattern": "fun greet", "path": "$repository"}""") }
            .reply {
                toolCall("fs.read", arguments("path" to greeter))
                toolCall("fs.read", arguments("path" to configFile))
            }
            .reply { text("greet(name) in Greeter.kt says hello to name.") }
        val store = MemoryStore()
        val harness = PluginHarness.builder(AlexandriteAgentIndex()).plugin(AlexandriteEngineJdkHttpIndex())
            .plugin(AlexandriteToolsIndex())
            .plugin(AlexandriteEngineSwingHtmlIndex())
            .config(config)
            .store(store)
            .model(model)
            .channel(operators = setOf("operator"))
            .dataRoot(root.resolve("data"))
            .configFile(configFile)
            .build()

        runBlocking {
            withTimeout(20.seconds) {
                harness.run {
                    val admission = channel().receive("What does greet do?", sender = channel().user("operator"))
                    val ticket = assertIs<Admission.Accepted>(admission).ticket
                    assertIs<TurnOutcome.Completed>(ticket.outcome())

                    val reply = channel().reply(ticket.turn).completed?.text
                    assertEquals("greet(name) in Greeter.kt says hello to name.", reply)
                }
            }
        }

        val requests = model.requests
        assertContains(requests.first().tools.map { it.name }, "fs.read")
        assertEquals(true, requests.first().instructions.any { it.id == "workspace" })
        val conversation = runBlocking { store.conversations.current(AgentChatKey.parse("coder@test:main:chat")).id }
        val entries = runBlocking { store.transcripts.entries(conversation) }
        val calls = entries.filterIsInstance<AssistantEntry>().flatMap { it.parts.filterIsInstance<ToolCallPart>() }
        val results = entries.filterIsInstance<ToolResultEntry>().associateBy { it.callId }
        assertEquals(
            listOf(
                ToolOutcome.Succeeded to "$greeter",
                ToolOutcome.Succeeded to "$greeter\n  3: fun greet(name: String) = \"Hello, \$name!\"\n",
                ToolOutcome.Succeeded to "1\tpackage demo\n2\t\n3\tfun greet(name: String) = \"Hello, \$name!\"\n",
                ToolOutcome.Failed to
                    "$configFile is protected (Alexandrite's configuration): no file tool may read it.",
            ),
            calls.map { results.getValue(it.id) }.map { it.outcome to it.text },
        )
        assertEquals(results.values.toSet(), requests.last().history.filterIsInstance<ToolResultEntry>().toSet())
        assertEquals(false, Files.exists(root.resolve("data/workspaces")))
    }
}
