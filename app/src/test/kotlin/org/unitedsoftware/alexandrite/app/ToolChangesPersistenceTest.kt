package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.tools.AlexandriteToolsIndex
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ToolChangesPersistenceTest {
    @TempDir
    lateinit var directory: Path

    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val model = ScriptedModel()

    /** Runs [block] on the agent over the SQLite store in [directory] with [tools] and without [deny], as one start. */
    private fun started(deny: List<String>, vararg tools: Tool, block: suspend PluginHarness.Running.() -> Unit) {
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "tools": {"deny": [${deny.joinToString { "\"$it\"" }}]}
                }
              }
            }
        """.trimIndent()
        val harness = PluginHarness.builder(AlexandriteAgentIndex())
            .plugin(AlexandriteStoreSqliteIndex())
            .plugin(AlexandriteEngineSqliteXerialIndex())
            .plugin(AlexandriteToolsIndex())
            .config(config)
            .model(model)
            .channel(operators = setOf("operator"))
            .apply { tools.forEach(::tool) }
            .dataRoot(directory.resolve("data"))
            .build()
        runBlocking { withTimeout(20.seconds) { harness.run(block) } }
    }

    private suspend fun PluginHarness.Running.ask() {
        (channel().receive("Which tools do you have?", sender = channel().user("operator")) as Admission.Accepted)
            .ticket.outcome()
    }

    private suspend fun PluginHarness.Running.stored(): List<TranscriptEntry> =
        get<TranscriptStore>().entries(get<ConversationStore>().current(key).id)

    private fun notices(entries: List<TranscriptEntry>): List<String> = entries.filterIsInstance<UserEntry>()
        .filter { it.origin == UserOrigin.SystemWritten("alexandrite.tools") }
        .map { (it.parts.single() as ContextPart).text }

    /** Each of [entries] as `tools`, `user` or `assistant`. */
    private fun shape(entries: List<TranscriptEntry>): List<String> = entries.map { entry ->
        when {
            entry !is UserEntry -> "assistant"
            entry.origin is UserOrigin.SystemWritten -> "tools"
            else -> "user"
        }
    }

    @Test
    fun `a restart with other tools tells the next turn of the SQLite store's conversation, once`() {
        repeat(3) { model.reply { text("Only file and permission tools.") } }
        lateinit var entries: List<TranscriptEntry>

        started(listOf("command.exec", "fs.edit"), recordingTool("notes.write")) { ask() }
        started(emptyList()) {
            ask()
            ask()
            entries = stored()
        }

        assertEquals(
            listOf(
                "[alexandrite:tools]\nYour tools changed since your last turn in this conversation.\n" +
                    "Added: command.exec, fs.edit.\nRemoved: notes.write.\n" +
                    "What you said about your tools earlier in this conversation may be out of date.\n" +
                    "[/alexandrite:tools]",
            ),
            notices(entries),
        )
        assertEquals(
            listOf("user", "assistant", "tools", "user", "assistant", "user", "assistant"),
            shape(entries),
        )
        assertEquals(notices(entries), notices(model.requests[1].history))
    }
}
