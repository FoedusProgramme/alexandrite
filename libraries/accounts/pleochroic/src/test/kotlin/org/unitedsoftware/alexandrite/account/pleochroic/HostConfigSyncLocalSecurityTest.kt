package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pull must never be able to reach outside its own directory, however the account answers (the threat model: it
 * may be compromised or impersonated). These exercise [HostConfigSyncLocal.apply] directly with traversal ids,
 * Windows path forms and a pre-planted symlink, asserting nothing outside the intended directory is ever touched.
 */
class HostConfigSyncLocalSecurityTest {
    @OptIn(InternalAlexandriteApi::class)
    private object NoOpHostConfig : HostConfig {
        override suspend fun get(path: String): JsonElement? = null

        override suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges = ConfigChanges(emptyList(), emptyList())

        override suspend fun replace(text: String): ConfigChanges = ConfigChanges(emptyList(), emptyList())
    }

    private fun local(tmp: Path): HostConfigSyncLocal {
        val configFile = tmp.resolve("config/alexandrite.json")
        configFile.parent.createDirectories()
        return HostConfigSyncLocal(NoOpHostConfig, configFile, tmp.resolve("config/secrets.env"))
    }

    private fun filesDoc(path: String, content: String = "danger"): JsonElement = buildJsonObject {
        put(
            "files",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", path)
                        put("encoding", "utf8")
                        put("content", content)
                    },
                )
            },
        )
    }

    @Test
    fun `a traversal skill name writes nothing outside the config directory`(@TempDir tmp: Path) = runBlocking {
        val outside = tmp.resolve("outside")
        outside.createDirectories()
        val secretFile = outside.resolve("secret.txt").also { it.writeText("do not touch") }

        local(tmp).apply(listOf(LocalChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "../../outside", filesDoc("x.txt"))))

        assertTrue(Files.exists(secretFile))
        assertFalse(Files.exists(outside.resolve("x.txt")))
        assertFalse(Files.exists(tmp.resolve("config/skills")))
    }

    @Test
    fun `a traversal agent id in the skill scope writes nothing outside the config directory`(@TempDir tmp: Path) =
        runBlocking {
            val outside = tmp.resolve("outside")
            outside.createDirectories()

            local(tmp).apply(
                listOf(LocalChange(AREA_SKILL, "agent:../../outside", "my-skill", filesDoc("x.txt"))),
            )

            assertFalse(Files.exists(outside.resolve("skills")))
            assertFalse(Files.exists(outside.resolve("my-skill")))
        }

    @Test
    fun `a traversal file path inside a skill refuses the whole change, nothing written or deleted`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val existing = tmp.resolve("config/skills/my-skill")
        existing.createDirectories()
        existing.resolve("SKILL.md").writeText("# kept")

        local(tmp).apply(
            listOf(LocalChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill", filesDoc("../escape.txt"))),
        )

        assertTrue(Files.exists(existing.resolve("SKILL.md")))
        assertFalse(Files.exists(tmp.resolve("config/escape.txt")))
        assertFalse(Files.exists(tmp.resolve("escape.txt")))
    }

    @Test
    fun `Windows path forms in a skill file's path are refused`(@TempDir tmp: Path) = runBlocking {
        for (path in listOf("C:\\Windows\\System32\\evil.txt", "a\\..\\..\\b.txt", "\\\\server\\share\\x")) {
            local(tmp).apply(listOf(LocalChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill", filesDoc(path))))
        }

        assertFalse(Files.exists(tmp.resolve("config/skills/my-skill")))
    }

    @Test
    fun `a symlink planted where a skill directory would go is refused, never followed`(@TempDir tmp: Path) =
        runBlocking {
            val outside = tmp.resolve("outside").also { it.createDirectories() }
            val sentinel = outside.resolve("sentinel.txt").also { it.writeText("do not touch") }
            val skillsRoot = tmp.resolve("config/skills").also { it.createDirectories() }
            val link = skillsRoot.resolve("escape")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (e: Exception) {
                return@runBlocking // symlinks unsupported here; nothing to assert
            }

            local(tmp).apply(listOf(LocalChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "escape", filesDoc("x.txt"))))

            assertTrue(Files.exists(sentinel))
            assertFalse(Files.exists(outside.resolve("x.txt")))
            // The link itself must survive untouched too: a refused change never deletes a name it did not validate.
            assertTrue(Files.isSymbolicLink(link))
        }

    @Test
    fun `a null data skill delete through a symlinked name never deletes what it points to`(@TempDir tmp: Path) =
        runBlocking {
            val outside = tmp.resolve("outside").also { it.createDirectories() }
            val sentinel = outside.resolve("sentinel.txt").also { it.writeText("do not touch") }
            val skillsRoot = tmp.resolve("config/skills").also { it.createDirectories() }
            val link = skillsRoot.resolve("escape")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (e: Exception) {
                return@runBlocking
            }

            local(tmp).apply(listOf(LocalChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "escape", null)))

            assertTrue(Files.exists(sentinel))
        }

    @Test
    fun `a traversal instructions id writes nothing outside the config directory`(@TempDir tmp: Path) = runBlocking {
        local(tmp).apply(
            listOf(
                LocalChange(
                    AREA_CONFIG,
                    KIND_INSTRUCTIONS,
                    "../../AGENTS.md",
                    buildJsonObject {
                        put("text", "x")
                    },
                ),
            ),
        )
        local(tmp).apply(
            listOf(
                LocalChange(
                    AREA_CONFIG,
                    KIND_INSTRUCTIONS,
                    "agents/../../../etc/PERSONA.md",
                    buildJsonObject { put("text", "x") },
                ),
            ),
        )

        assertFalse(Files.exists(tmp.resolve("AGENTS.md")))
        assertFalse(Files.exists(tmp.resolve("etc")))
    }

    @Test
    fun `a secret name that would inject a line into the secrets file is refused`(@TempDir tmp: Path) = runBlocking {
        val secretsFile = tmp.resolve("config/secrets.env")

        local(tmp).apply(
            listOf(LocalChange(AREA_SECRET, "", "FOO\nEVIL=1", kotlinx.serialization.json.JsonPrimitive("x"))),
        )

        assertFalse(Files.exists(secretsFile))
    }

    @Test
    fun `a secret value holding a newline is refused rather than splicing a second line in`(@TempDir tmp: Path) =
        runBlocking {
            val secretsFile = tmp.resolve("config/secrets.env")

            local(tmp).apply(
                listOf(LocalChange(AREA_SECRET, "", "MY_TOKEN", kotlinx.serialization.json.JsonPrimitive("x\nEVIL=1"))),
            )

            assertFalse(Files.exists(secretsFile))
        }

    @Test
    fun `an oversized skill (too many files) is refused as a whole`(@TempDir tmp: Path) = runBlocking {
        val files = buildJsonArray {
            repeat(MAX_SKILL_FILES + 1) { i ->
                add(
                    buildJsonObject {
                        put("path", "f$i.txt")
                        put("encoding", "utf8")
                        put("content", "x")
                    },
                )
            }
        }
        local(tmp).apply(
            listOf(LocalChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill", buildJsonObject { put("files", files) })),
        )

        assertFalse(Files.exists(tmp.resolve("config/skills/my-skill")))
    }

    @Test
    fun `a skill whose decoded content exceeds the total size cap is refused as a whole`(@TempDir tmp: Path) =
        runBlocking {
            val big = base64Filler()
            local(tmp).apply(
                listOf(
                    LocalChange(
                        AREA_SKILL,
                        SKILL_SCOPE_GLOBAL,
                        "my-skill",
                        buildJsonObject {
                            put(
                                "files",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("path", "big.bin")
                                            put("encoding", "base64")
                                            put("content", big)
                                        },
                                    )
                                },
                            )
                        },
                    ),
                ),
            )

            assertFalse(Files.exists(tmp.resolve("config/skills/my-skill")))
        }
}

/** Base64 for a few bytes over [MAX_SKILL_TOTAL_BYTES], cheaply (zero bytes, not held as text at all). */
private fun base64Filler(): String {
    val bytes = ByteArray((MAX_SKILL_TOTAL_BYTES + 1024).toInt())
    return java.util.Base64.getEncoder().encodeToString(bytes)
}
