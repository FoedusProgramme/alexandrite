package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.engine.swinghtml.AlexandriteEngineSwingHtmlIndex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.tools.AlexandriteToolsIndex
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PersonaEditTest {
    @TempDir
    lateinit var directory: Path

    private val root: Path get() = directory.toRealPath()
    private val configFile: Path get() = root.resolve("config/alexandrite.json")
    private val persona: Path get() = configFile.resolveSibling("agents/coder/PERSONA.md")
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val model = ScriptedModel()
    private val paste = recordingTool("notes.paste") { _, context ->
        context.untrusted("a pasted page")
        ToolResult("Pasted.")
    }

    private val config = """
        {
          "previewIntervalMillis": 0,
          "floor": {"deny": ["alexandrite"]},
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "tools": {"forGuests": ["fs.*"]}
            }
          }
        }
    """.trimIndent()

    private fun started(block: suspend PluginHarness.Running.() -> Unit) {
        persona.parent.createDirectories()
        persona.writeText("I like tea.\n")
        Files.setLastModifiedTime(persona, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")))
        val harness = PluginHarness.builder(AlexandriteAgentIndex()).plugin(AlexandriteEngineJdkHttpIndex())
            .plugin(AlexandriteToolsIndex())
            .plugin(AlexandriteEngineSwingHtmlIndex())
            .config(config)
            .store(MemoryStore())
            .model(model)
            .tool(paste)
            .channel(operators = setOf("operator"))
            .dataRoot(root.resolve("data"))
            .configFile(configFile)
            .build()
        runBlocking { withTimeout(20.seconds) { harness.run(block) } }
    }

    private suspend fun PluginHarness.Running.ask(user: String = "operator") {
        (channel().receive("Hi", sender = channel().user(user)) as Admission.Accepted).ticket.outcome()
    }

    private suspend fun PluginHarness.Running.outcomes(): List<ToolOutcome> {
        val conversation = get<ConversationStore>().current(key).id
        return get<TranscriptStore>().entries(conversation).filterIsInstance<ToolResultEntry>().map { it.outcome }
    }

    private val read: String get() = JsonObject(mapOf("path" to JsonPrimitive("$persona"))).toString()

    private fun edit(old: String, new: String): String = JsonObject(
        mapOf(
            "path" to JsonPrimitive("$persona"),
            "edits" to JsonArray(listOf(JsonObject(mapOf("old" to JsonPrimitive(old), "new" to JsonPrimitive(new))))),
        ),
    ).toString()

    @Test
    fun `an operator's fs edit of the persona runs unasked and reaches the prompt of the next turn`() {
        model.reply { toolCall("fs.read", read) }.reply { toolCall("fs.edit", edit("tea", "coffee")) }
            .reply { text("Noted.") }
            .reply { text("Coffee it is.") }

        started {
            ask()
            ask()

            assertEquals(listOf(ToolOutcome.Succeeded, ToolOutcome.Succeeded), outcomes())
            assertEquals(emptyList(), channel().approvals)
        }

        val instructions = model.requests.map { request -> request.instructions.map { it.text } }
        assertEquals(listOf(true, true, true, false), instructions.map { "I like tea.\n" in it })
        assertEquals(listOf(false, false, false, true), instructions.map { "I like coffee.\n" in it })
        assertEquals("I like coffee.\n", persona.readText())
    }

    @Test
    fun `a guest's edit and one in a tainted operator's turn wait for an operator, whom none can reach yet`() {
        model.reply { toolCall("fs.read", read) }.reply { toolCall("fs.edit", edit("tea", "coffee")) }
            .reply { text("Not edited.") }
            .reply { toolCall("notes.paste") }.reply { toolCall("fs.edit", edit("tea", "cake")) }
            .reply { text("Not edited either.") }

        started {
            ask(user = "guest")
            ask()

            val refused = ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY)
            assertEquals(listOf(ToolOutcome.Succeeded, refused, ToolOutcome.Succeeded, refused), outcomes())
        }

        assertEquals("I like tea.\n", persona.readText())
    }
}
