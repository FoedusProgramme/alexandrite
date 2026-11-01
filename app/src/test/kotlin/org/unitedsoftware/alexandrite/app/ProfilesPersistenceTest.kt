package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class ProfilesPersistenceTest {
    @TempDir
    lateinit var directory: Path

    private val model = ScriptedModel()

    /** Runs [block] on the agent over the SQLite store in [directory], as one start. */
    private fun started(block: suspend PluginHarness.Running.() -> Unit) {
        val config = """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
            }
        """.trimIndent()
        val harness = PluginHarness.builder(AlexandriteAgentIndex()).plugin(AlexandriteEngineJdkHttpIndex())
            .plugin(AlexandriteStoreSqliteIndex())
            .plugin(AlexandriteEngineSqliteXerialIndex())
            .config(config)
            .model(model)
            .channel(operators = setOf("operator"))
            .tool(recordingTool("notes.write"))
            .dataRoot(directory.resolve("data"))
            .build()
        runBlocking { withTimeout(20.seconds) { harness.run(block) } }
    }

    private suspend fun PluginHarness.Running.create(id: String) {
        model.reply {
            toolCall(
                "profile.create",
                """{"id": "$id", "name": "Reviewer", "description": "Reviews code.", "instructions": "Be strict."}""",
            )
        }.reply { text("Created.") }
        val admission = channel().receive("Make a profile", sender = channel().user("operator"))
        (admission as Admission.Accepted).ticket.outcome()
    }

    @Test
    fun `a profile the agent created survives a restart of the SQLite store`() {
        started { create("reviewer") }

        started {
            val profiles = get<AgentCatalog>().profiles()

            assertEquals(listOf("general", "reviewer"), profiles.map { it.profile.id.value }.sorted())
            val reviewer = profiles.single { it.profile.id.value == "reviewer" }
            assertEquals("Reviews code.", reviewer.profile.description)
            val source = assertIs<ProfileSource.Created>(reviewer.source)
            assertEquals("Operator", source.by?.displayName)
        }
    }
}
