package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory [HostConfig] a test scripts directly, with the same path semantics [ConfigPath] gives. */
@OptIn(InternalAlexandriteApi::class)
private class FakeHostConfig(root: JsonObject = JsonObject(emptyMap())) : HostConfig {
    var root: JsonObject = root
        private set

    override suspend fun get(path: String): JsonElement? =
        if (path.isEmpty()) root else navigate(root, ConfigPath.parse(path))

    override suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges {
        val edit = Edit(root)
        edit.block()
        root = edit.current
        return ConfigChanges(emptyList(), emptyList())
    }

    override suspend fun replace(text: String): ConfigChanges {
        root = Json.parseToJsonElement(text) as JsonObject
        return ConfigChanges(emptyList(), emptyList())
    }

    private class Edit(var current: JsonObject) : ConfigEdit {
        override fun get(path: String): JsonElement? = navigate(current, ConfigPath.parse(path))

        override fun set(path: String, value: JsonElement) {
            current = setAt(current, ConfigPath.parse(path), value)
        }

        override fun remove(path: String) {
            current = removeAt(current, ConfigPath.parse(path))
        }
    }

    companion object {
        fun navigate(root: JsonElement, keys: List<String>): JsonElement? {
            var current: JsonElement? = root
            for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
            return current
        }

        fun setAt(root: JsonObject, keys: List<String>, value: JsonElement): JsonObject {
            val key = keys.first()
            if (keys.size == 1) return JsonObject(root + (key to value))
            val child = root[key] as? JsonObject ?: JsonObject(emptyMap())
            return JsonObject(root + (key to setAt(child, keys.drop(1), value)))
        }

        fun removeAt(root: JsonObject, keys: List<String>): JsonObject {
            val key = keys.first()
            if (keys.size == 1) return JsonObject(root - key)
            val child = root[key] as? JsonObject ?: return root
            return JsonObject(root + (key to removeAt(child, keys.drop(1))))
        }
    }
}

class HostConfigSyncLocalTest {
    private fun local(directory: Path, hostConfig: FakeHostConfig = FakeHostConfig()): HostConfigSyncLocal {
        val configFile = directory.resolve("config/alexandrite.json")
        configFile.parent.createDirectories()
        return HostConfigSyncLocal(hostConfig, configFile, directory.resolve("config/secrets.env"))
    }

    @Test
    fun `collect reads provider endpoints, agents, mcp servers and app settings`(@TempDir tmp: Path) = runBlocking {
        val hostConfig = FakeHostConfig(
            Json.parseToJsonElement(
                """{"providers": {"openrouter": {"endpoints": {"work": {"baseUrl": "x"}}}},
                    "agent": {"agents": {"helper": {"name": "Helper"}}},
                    "mcp": {"servers": {"notes": {"command": "mcp-server"}}},
                    "app": {"theme": "dark"},
                    "plugins": {"heartbeat": {"enabled": true}}}""",
            ) as JsonObject,
        )

        val items = local(tmp, hostConfig).collect()

        fun of(area: String, kind: String, id: String) = items.find {
            it.area == area && it.kind == kind && it.id == id
        }
        assertEquals(buildJsonObject { put("baseUrl", "x") }, of(AREA_CONFIG, KIND_PROVIDER, "openrouter/work")?.data)
        assertEquals(buildJsonObject { put("name", "Helper") }, of(AREA_CONFIG, KIND_AGENT, "helper")?.data)
        assertEquals(buildJsonObject { put("command", "mcp-server") }, of(AREA_CONFIG, KIND_MCP, "notes")?.data)
        assertEquals(buildJsonObject { put("theme", "dark") }, of(AREA_CONFIG, KIND_SETTINGS, "app")?.data)
        assertEquals(buildJsonObject { put("enabled", true) }, of(AREA_CONFIG, KIND_SETTINGS, "heartbeat")?.data)
    }

    @Test
    fun `collect reads AGENTS-md and per-agent PERSONA-md as instructions`(@TempDir tmp: Path) = runBlocking {
        val hostConfig = FakeHostConfig(
            Json.parseToJsonElement("""{"agent": {"agents": {"helper": {"name": "Helper"}}}}""") as JsonObject,
        )
        val configFile = tmp.resolve("config/alexandrite.json")
        configFile.parent.createDirectories()
        configFile.resolveSibling("AGENTS.md").writeText("# Root instructions")
        configFile.resolveSibling("agents/helper").createDirectories()
        configFile.resolveSibling("agents/helper/PERSONA.md").writeText("# Helper persona")

        val items = HostConfigSyncLocal(hostConfig, configFile, tmp.resolve("config/secrets.env")).collect()

        val root = items.find { it.kind == KIND_INSTRUCTIONS && it.id == "AGENTS.md" }
        val persona = items.find { it.kind == KIND_INSTRUCTIONS && it.id == "agents/helper/PERSONA.md" }
        assertEquals(
            "# Root instructions",
            (root?.data as? JsonObject)?.get("text")?.let {
                (it as JsonPrimitive).content
            },
        )
        assertEquals(
            "# Helper persona",
            (persona?.data as? JsonObject)?.get("text")?.let {
                (it as JsonPrimitive).content
            },
        )
    }

    @Test
    fun `collect reads secrets as bare values and never reads the raw env reference from config`(@TempDir tmp: Path) =
        runBlocking {
            val local = local(tmp)
            tmp.resolve("config/secrets.env").writeText("MY_TOKEN=hunter2\n")

            val items = local.collect()

            val secret = items.single { it.area == AREA_SECRET }
            assertEquals("MY_TOKEN", secret.id)
            assertEquals(JsonPrimitive("hunter2"), secret.data)
        }

    @Test
    fun `collect reads global and per-agent skills with every file under them`(@TempDir tmp: Path) = runBlocking {
        val hostConfig = FakeHostConfig(
            Json.parseToJsonElement("""{"agent": {"agents": {"helper": {"name": "Helper"}}}}""") as JsonObject,
        )
        val configFile = tmp.resolve("config/alexandrite.json")
        val globalSkill = configFile.resolveSibling("skills/my-skill")
        globalSkill.createDirectories()
        globalSkill.resolve("SKILL.md").writeText("# My skill")
        val agentSkill = configFile.resolveSibling("agents/helper/skills/notes")
        agentSkill.createDirectories()
        agentSkill.resolve("SKILL.md").writeText("# Notes")

        val items = HostConfigSyncLocal(hostConfig, configFile, tmp.resolve("config/secrets.env")).collect()

        val global = items.single { it.area == AREA_SKILL && it.kind == SKILL_SCOPE_GLOBAL }
        assertEquals("my-skill", global.id)
        val globalFiles = (global.data as JsonObject)["files"]
        assertEquals(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", "SKILL.md")
                        put("encoding", "utf8")
                        put("content", "# My skill")
                    },
                )
            },
            globalFiles,
        )
        val perAgent = items.single { it.area == AREA_SKILL && it.kind == agentSkillScope("helper") }
        assertEquals("notes", perAgent.id)
    }

    @Test
    fun `collect encodes a non-utf8 skill file as base64`(@TempDir tmp: Path) = runBlocking {
        val local = local(tmp)
        val binary = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x02)
        val skillDir = tmp.resolve("config/skills/my-skill")
        skillDir.createDirectories()
        Files.write(skillDir.resolve("icon.bin"), binary)

        val item = local.collect().single { it.area == AREA_SKILL }
        val file = ((item.data as JsonObject)["files"] as kotlinx.serialization.json.JsonArray).single() as JsonObject

        assertEquals(JsonPrimitive("base64"), file["encoding"])
        assertTrue(Base64.getDecoder().decode((file["content"] as JsonPrimitive).content).contentEquals(binary))
    }

    @Test
    fun `apply writes every config change through one hostConfig edit`(@TempDir tmp: Path) = runBlocking {
        val hostConfig = FakeHostConfig()
        val local = local(tmp, hostConfig)

        local.apply(
            listOf(
                LocalChange(AREA_CONFIG, KIND_PROVIDER, "openrouter/work", buildJsonObject { put("baseUrl", "x") }),
                LocalChange(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("command", "y") }),
            ),
        )

        assertEquals(JsonPrimitive("x"), hostConfig.get("providers.openrouter.endpoints.work.baseUrl"))
        assertEquals(JsonPrimitive("y"), hostConfig.get("mcp.servers.notes.command"))
    }

    @Test
    fun `apply with a null data removes the config entry`(@TempDir tmp: Path) = runBlocking {
        val hostConfig = FakeHostConfig(
            Json.parseToJsonElement("""{"agent": {"agents": {"helper": {"name": "Helper"}}}}""") as JsonObject,
        )
        local(tmp, hostConfig).apply(listOf(LocalChange(AREA_CONFIG, KIND_AGENT, "helper", null)))

        assertNull(hostConfig.get("agent.agents.helper"))
    }

    @Test
    fun `apply writes and removes secrets by file`(@TempDir tmp: Path) = runBlocking {
        val local = local(tmp)
        val secretsFile = tmp.resolve("config/secrets.env")

        local.apply(listOf(LocalChange(AREA_SECRET, "", "MY_TOKEN", JsonPrimitive("hunter2"))))
        assertEquals("MY_TOKEN=hunter2\n", secretsFile.readText())

        local.apply(listOf(LocalChange(AREA_SECRET, "", "MY_TOKEN", null)))
        assertEquals("", secretsFile.readText())
    }

    @Test
    fun `apply writes a secret given as the service's one-field value document`(@TempDir tmp: Path) = runBlocking {
        val local = local(tmp)
        val secretsFile = tmp.resolve("config/secrets.env")

        local.apply(listOf(LocalChange(AREA_SECRET, "", "MY_TOKEN", buildJsonObject { put("value", "hunter2") })))

        assertEquals("MY_TOKEN=hunter2\n", secretsFile.readText())
    }

    @Test
    fun `apply writes a skill's utf8 and base64 files under its scope's root, replacing what was there`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val local = local(tmp)
        val binary = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x02)
        val files = buildJsonArray {
            add(
                buildJsonObject {
                    put("path", "SKILL.md")
                    put("encoding", "utf8")
                    put("content", "# Hi")
                },
            )
            add(
                buildJsonObject {
                    put("path", "assets/icon.bin")
                    put("encoding", "base64")
                    put("content", Base64.getEncoder().encodeToString(binary))
                },
            )
        }
        val stale = tmp.resolve("config/skills/my-skill/STALE.md")
        stale.parent.createDirectories()
        stale.writeText("stale")

        local.apply(
            listOf(
                LocalChange(
                    AREA_SKILL,
                    SKILL_SCOPE_GLOBAL,
                    "my-skill",
                    buildJsonObject {
                        put("files", files)
                    },
                ),
            ),
        )

        val root = tmp.resolve("config/skills/my-skill")
        assertEquals("# Hi", root.resolve("SKILL.md").readText())
        assertTrue(Files.readAllBytes(root.resolve("assets/icon.bin")).contentEquals(binary))
        assertTrue(Files.notExists(stale))
    }

    @Test
    fun `apply writes a per-agent skill under that agent's skills root`(@TempDir tmp: Path) = runBlocking {
        val local = local(tmp)
        val files =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", "SKILL.md")
                        put("encoding", "utf8")
                        put("content", "# Notes")
                    },
                )
            }

        local.apply(
            listOf(
                LocalChange(
                    AREA_SKILL,
                    agentSkillScope("helper"),
                    "notes",
                    buildJsonObject {
                        put("files", files)
                    },
                ),
            ),
        )

        assertEquals("# Notes", tmp.resolve("config/agents/helper/skills/notes/SKILL.md").readText())
    }

    @Test
    fun `apply with a null data deletes the whole skill directory`(@TempDir tmp: Path) = runBlocking {
        val local = local(tmp)
        val skillDir = tmp.resolve("config/skills/my-skill")
        skillDir.createDirectories()
        skillDir.resolve("SKILL.md").writeText("# Hi")

        local.apply(listOf(LocalChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill", null)))

        assertTrue(Files.notExists(skillDir))
    }

    @Test
    fun `apply ignores a skill file path that escapes its directory`(@TempDir tmp: Path) = runBlocking {
        val local = local(tmp)
        val files = buildJsonArray {
            add(
                buildJsonObject {
                    put("path", "../escape.md")
                    put("encoding", "utf8")
                    put("content", "nope")
                },
            )
        }

        local.apply(
            listOf(
                LocalChange(
                    AREA_SKILL,
                    SKILL_SCOPE_GLOBAL,
                    "my-skill",
                    buildJsonObject {
                        put("files", files)
                    },
                ),
            ),
        )

        assertTrue(Files.notExists(tmp.resolve("config/escape.md")))
    }
}
