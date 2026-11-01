package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.commands.AlexandriteCommandsIndex
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ContextStatus
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CompactionPersistenceTest {
    @TempDir
    lateinit var directory: Path

    private val model = ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { contextWindow(100_000) }))
    private val read = recordingTool("fs.read") { arguments, _ ->
        ToolResult("Contents of ${arguments["path"]?.jsonPrimitive?.content}: " + "x".repeat(40_000))
    }
    private val summarizer = RequestMatch("a summarizer's request") { it.summarizes }
    private val turns = RequestMatch("a turn's request") { !it.summarizes }

    private val ModelRequest.summarizes: Boolean get() = instructions.any { it.id == "alexandrite.summarizer" }

    /** Runs [block] on the agent over the SQLite store in [directory], as one start. */
    private fun started(block: suspend PluginHarness.Running.() -> Unit) {
        val compaction = """{"clearAt": 0.3, "clearAtLeast": 0.05, "keepToolRounds": 1, "keepTurns": 1}"""
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {"model": "scripted/test-model", "channels": {"test:main": {}}, "compaction": $compaction}
              }
            }
        """.trimIndent()
        val harness = PluginHarness.builder(AlexandriteAgentIndex()).plugin(AlexandriteEngineJdkHttpIndex())
            .plugin(AlexandriteStoreSqliteIndex())
            .plugin(AlexandriteEngineSqliteXerialIndex())
            .plugin(AlexandriteCommandsIndex())
            .config(config)
            .model(model)
            .tool(read)
            .channel(operators = setOf("operator"))
            .dataRoot(directory.resolve("data"))
            .build()
        runBlocking { withTimeout(20.seconds) { harness.run(block) } }
    }

    private suspend fun PluginHarness.Running.ask(text: String) {
        (channel().receive(text, sender = channel().user("operator")) as Admission.Accepted).ticket.outcome()
    }

    private fun reads(vararg paths: String, answer: String, usage: Usage? = null) {
        for (path in paths) model.reply(turns) { toolCall("fs.read", """{"path": "$path"}""") }
        model.reply(turns) {
            text(answer)
            usage?.let(::usage)
        }
    }

    @Test
    fun `a restart keeps the last counted prompt, the summary and the cleared results of a conversation`() {
        val usage = Usage.builder().inputTokens(23_456).contextTokens(23_456).build()
        reads("a", "b", answer = "Read two.")
        reads("c", "d", "e", answer = "Read three.", usage = usage)
        model.reply(summarizer) { text("Ada had a and b read.") }
        model.reply(turns) { text("Still here.") }
        lateinit var before: ContextStatus
        lateinit var after: ContextStatus

        started {
            ask("First.")
            ask("Second.")
            (channel().receiveCommand("compact", issuer = channel().user("operator")) as Admission.Accepted).ticket
                .outcome()
            before = get<ConversationControl>().context(channel().chat())
        }
        started {
            after = get<ConversationControl>().context(channel().chat())
            ask("Third.")
        }

        assertEquals(before, after)
        assertEquals(23_456L, after.promptTokens)
        assertEquals(listOf(100_000, 6), listOf(after.window, after.summarized))
        assertNotNull(after.compactedAt)
        val last = model.requests.last()
        val summary = (last.history.first() as UserEntry)
        assertEquals(UserOrigin.SystemWritten("alexandrite.summary"), summary.origin)
        assertTrue("Ada had a and b read." in (summary.parts.single() as ContextPart).text)
        val results = last.history.filterIsInstance<ToolResultEntry>().map { (it.content.single() as TextPart).text }
        assertEquals(listOf(true, true, false), results.map { it.startsWith("[alexandrite:cleared] fs.read") })
        assertTrue(results.last().startsWith("Contents of e: x"))
        model.assertFinished()
    }
}
