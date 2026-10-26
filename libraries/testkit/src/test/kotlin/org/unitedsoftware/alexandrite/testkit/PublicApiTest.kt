@file:OptIn(ExperimentalCompilerApi::class)

package org.unitedsoftware.alexandrite.testkit

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicApiTest {
    @TempDir
    lateinit var workingDir: File

    /** A third-party plugin's test that uses the main doubles, compiled without the internal API. */
    private val sample = """
        import kotlinx.coroutines.flow.toList
        import kotlinx.serialization.json.JsonObject
        import org.unitedsoftware.alexandrite.sdk.channel.*
        import org.unitedsoftware.alexandrite.sdk.chat.*
        import org.unitedsoftware.alexandrite.sdk.model.*
        import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
        import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
        import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
        import org.unitedsoftware.alexandrite.sdk.transcript.*
        import org.unitedsoftware.alexandrite.sdk.turn.*
        import org.unitedsoftware.alexandrite.testkit.*
        import java.nio.file.Path

        suspend fun models(): List<Any?> {
            val scripted = ScriptedModel(EndpointId("main"), listOf(ScriptedModel.modelInfo("m")))
            scripted.reply(RequestMatch.round(0)) {
                text("Sav", "ing.")
                toolCall("notes.add", "{}", ToolCallId("c1"))
                finish(FinishKind.TOOL_USE)
            }
            scripted.fail(ModelErrorKind.OVERLOADED, "Busy.")
            val events: List<ModelEvent> = scripted.stream(testModelRequest()).toList()
            return listOf(events, scripted.requests, scripted.remaining)
        }

        suspend fun channels(): List<Any?> {
            val channel = RecordingChannel(TEST_INSTANCE, setOf("ada"))
            val admission = channel.receive("hi")
            channel.scriptApprovals(ApprovalAnswer.Expired)
            val request = ApprovalRequest.builder("a1", testTurn(), "notes.add", ToolRisk.EXEC, "{}", "a test").build()
            return listOf(admission, channel.askApproval(request), channel.sent, channel.approvals)
        }

        suspend fun tools(): List<Any?> {
            val tool = recordingTool("notes.find", ToolRisk.READ_ONLY) { _, context -> ToolResult("in ${'$'}{context.call}") }
            val context = testToolContext(workspace = Path.of("/srv/work"))
            val result = tool.execute(JsonObject(emptyMap()), context)
            return listOf(result, tool.calls, context.trigger, testHardFloor())
        }

        suspend fun harness(index: PluginIndex, backend: PluginIndex): List<Any?> {
            val store = MemoryStore()
            val seen = mutableListOf<Any?>()
            PluginHarness.builder(index)
                .tool(recordingTool())
                .model(ScriptedModel())
                .channel("main", "test", setOf("ada"), 100)
                .agent(RecordingAgent())
                .store(store)
                .config("{}")
                .build()
                .run {
                    seen += channel(TEST_INSTANCE)
                    stop()
                }
            for (check in STORE_CONTRACT) check.run { PluginHarness.builder(backend) }
            return seen + PROVIDER_CONTRACT.map { it.name } + FakeModelServer().baseUrl
        }
    """.trimIndent()

    private fun errors(vararg sources: Pair<String, String>): List<String> = KotlinCompilation().apply {
        workingDir = this@PublicApiTest.workingDir
        this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text) }
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile().messages.lines().filter { it.startsWith("e: ") }

    @Test
    fun `a plugin's test uses the main doubles without the internal API`() {
        assertEquals(emptyList(), errors("Sample.kt" to sample))
    }
}
