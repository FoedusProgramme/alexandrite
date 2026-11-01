package com.example.host

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.engine.swinghtml.AlexandriteEngineSwingHtmlIndex
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.tools.AlexandriteToolsIndex
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class HostTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the agent answers a turn after reading a file with a built-in tool`() {
        val file = directory.toRealPath().resolve("greeting.txt")
        file.writeText("Hello")
        val model = ScriptedModel()
            .reply { toolCall("fs.read", """{"path": "$file"}""") }
            .reply { text("The file says hello.") }
        val harness = PluginHarness.builder(AlexandriteAgentIndex())
            .plugin(AlexandriteToolsIndex())
            .plugin(AlexandriteEngineJdkHttpIndex())
            .plugin(AlexandriteEngineSwingHtmlIndex())
            .config("""{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}""")
            .store(MemoryStore())
            .model(model)
            .channel(operators = setOf("operator"))
            .build()

        runBlocking {
            withTimeout(20.seconds) {
                harness.run {
                    val admission = channel().receive("What does the file say?", sender = channel().user("operator"))
                    val ticket = assertIs<Admission.Accepted>(admission).ticket
                    assertIs<TurnOutcome.Completed>(ticket.outcome())

                    assertEquals("The file says hello.", channel().reply(ticket.turn).completed?.text)
                }
            }
        }

        val result = model.requests.last().history.filterIsInstance<ToolResultEntry>().single()
        assertEquals(ToolOutcome.Succeeded, result.outcome)
    }
}
