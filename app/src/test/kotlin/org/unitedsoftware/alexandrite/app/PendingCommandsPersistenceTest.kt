package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.commands.AlexandriteCommandsIndex
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PendingCommandsPersistenceTest {
    @TempDir
    lateinit var directory: Path

    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val model = ScriptedModel()

    /** Runs [block] on the agent over the SQLite store in [directory], as one start. */
    private fun started(block: suspend PluginHarness.Running.() -> Unit) {
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
        val harness = PluginHarness.builder(AlexandriteAgentIndex())
            .plugin(AlexandriteStoreSqliteIndex())
            .plugin(AlexandriteEngineSqliteXerialIndex())
            .plugin(AlexandriteCommandsIndex())
            .config(config)
            .model(model)
            .channel(operators = setOf("operator"))
            .dataRoot(directory.resolve("data"))
            .build()
        runBlocking { withTimeout(20.seconds) { harness.run(block) } }
    }

    private suspend fun PluginHarness.Running.runMode(arguments: String) {
        (channel().receiveCommand("mode", arguments, issuer = channel().user("operator")) as Admission.Accepted).ticket
            .outcome()
    }

    private suspend fun PluginHarness.Running.ask() {
        (channel().receive("Hi", sender = channel().user("operator")) as Admission.Accepted).ticket.outcome()
    }

    private suspend fun PluginHarness.Running.stored(): List<TranscriptEntry> =
        get<TranscriptStore>().entries(get<ConversationStore>().current(key).id)

    private fun notices(entries: List<TranscriptEntry>): List<String> = entries.filterIsInstance<UserEntry>()
        .filter { it.origin == UserOrigin.SystemWritten("alexandrite.commands") }
        .map { (it.parts.single() as ContextPart).text }

    @Test
    fun `a restart keeps the command run before it, and tells the next turn of the SQLite store's conversation`() {
        model.reply { text("Hi.") }
        lateinit var entries: List<TranscriptEntry>

        started { runMode("allowedit") }
        started {
            ask()
            entries = stored()
        }

        assertEquals(
            listOf(
                "[alexandrite:commands]\n" +
                    "Commands run in this chat since your last turn (they need no answer of their own):\n" +
                    "- Operator (operator) ran /mode allowedit and was told: \"The permission mode of this chat is " +
                    "now allowedit.\"\n" +
                    "[/alexandrite:commands]",
            ),
            notices(entries),
        )
        assertEquals(listOf("UserEntry", "UserEntry", "AssistantEntry"), entries.map { it::class.simpleName })
        assertEquals(notices(entries), notices(entries.take(1)))
    }
}
