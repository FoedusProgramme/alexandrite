package org.unitedsoftware.alexandrite.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ToolsPluginTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the plugin contributes the fs tools and command exec, which check paths against the bound floor`() {
        val root = directory.toRealPath()
        val secret = Files.createDirectories(root.resolve("secret")).resolve("key.txt")
        Files.writeString(secret, "SECRET")
        lateinit var risks: Map<String, ToolRisk>
        lateinit var refused: String
        lateinit var exec: ToolDefinition

        runBlocking {
            PluginHarness.builder(AlexandriteToolsIndex()).hardFloor(testHardFloor(listOf(secret.parent))).build().run {
                val tools = getAll<Tool>()
                risks = tools.associate { it.definition.name to it.definition.risk }
                val read = tools.single { it.definition.name == "fs.read" }
                val result = read.execute(JsonObject(mapOf("path" to JsonPrimitive("$secret"))), testToolContext())
                refused = (result.content.single() as TextPart).text
                exec = tools.single { it.definition.name == "command.exec" }.definition
            }
        }

        assertEquals(
            mapOf(
                "command.exec" to ToolRisk.EXEC,
                "fs.edit" to ToolRisk.WORKSPACE_WRITE,
                "fs.glob" to ToolRisk.READ_ONLY,
                "fs.grep" to ToolRisk.READ_ONLY,
                "fs.read" to ToolRisk.READ_ONLY,
                "fs.write" to ToolRisk.WORKSPACE_WRITE,
                "web.fetch" to ToolRisk.NETWORK_READ,
                "web.search" to ToolRisk.NETWORK_READ,
            ),
            risks,
        )
        assertEquals("$secret is protected (a place the test protects): no file tool may read it.", refused)
        assertEquals(1800.seconds, exec.timeout)
        assertContains(exec.parameters.toString(), "at most 1800, which is the default.")
    }

    @Test
    fun `command exec takes its limits from the exec section and declares its longest run`() {
        lateinit var definition: ToolDefinition
        lateinit var description: String

        runBlocking {
            val config = """{"exec": {"maxTimeoutSeconds": 30, "maxOutputChars": 500, "environment": ["JAVA_HOME"]}}"""
            PluginHarness.builder(AlexandriteToolsIndex()).config(config).hardFloor(testHardFloor()).build().run {
                definition = getAll<Tool>().single { it.definition.name == "command.exec" }.definition
                description = definition.parameters.toString()
            }
        }

        assertEquals(30.seconds, definition.timeout)
        assertContains(description, "at most 30, which is the default.")
    }

    @Test
    fun `web search has the brave, tavily and pleochroic backends`() = runBlocking {
        PluginHarness.builder(AlexandriteToolsIndex()).hardFloor(testHardFloor()).build().run {
            assertEquals(setOf("brave", "tavily", "pleochroic"), getAll<WebSearchBackend>().map { it.id }.toSet())
        }
        Unit
    }
}
