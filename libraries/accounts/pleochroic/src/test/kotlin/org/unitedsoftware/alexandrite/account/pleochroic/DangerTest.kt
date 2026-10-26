package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DangerTest {
    @Test
    fun `a harmless agent field change names no dangerous field`() {
        val before = buildJsonObject { put("name", "Helper") }
        val after = buildJsonObject { put("name", "Helper 2") }

        assertEquals(emptyList(), dangerousAgentFields(before, after))
    }

    @Test
    fun `a changed permission, sandbox or tools field is named`() {
        val before = buildJsonObject { put("name", "Helper") }
        val after = buildJsonObject {
            put("name", "Helper")
            put("permission", buildJsonObject { put("mode", "full") })
        }

        assertEquals(listOf("permission"), dangerousAgentFields(before, after))
    }

    @Test
    fun `a brand new agent with a dangerous field is flagged, since before has none of it`() {
        val after = buildJsonObject { put("sandbox", buildJsonObject { put("type", "none") }) }

        assertEquals(listOf("sandbox"), dangerousAgentFields(null, after))
    }

    @Test
    fun `deleting an agent that held a dangerous field is flagged too`() {
        val before = buildJsonObject { put("tools", buildJsonObject { put("allow", "shell") }) }

        assertTrue(dangerousAgentFields(before, null).isNotEmpty())
    }

    @Test
    fun `changedSettingsFields names every top-level field that differs, is added or is removed`() {
        val before = buildJsonObject {
            put("language", "en")
            put("zone", "UTC")
        }
        val after = buildJsonObject {
            put("language", "zh-CN")
            put("plugins", "new")
        }

        assertEquals(setOf("language", "zone", "plugins"), changedSettingsFields(before, after))
    }

    @Test
    fun `changedSettingsFields is null when either side is not a plain object`() {
        assertEquals(null, changedSettingsFields(JsonPrimitive("not an object"), buildJsonObject {}))
        assertEquals(null, changedSettingsFields(buildJsonObject {}, JsonPrimitive("not an object")))
    }

    @Test
    fun `an allowlisted app settings field auto-applies on its own`() {
        assertEquals(
            emptyList(),
            dangerReasons(
                AREA_CONFIG,
                KIND_SETTINGS,
                "app",
                buildJsonObject { put("language", "en") },
                buildJsonObject { put("language", "zh-CN") },
                emptySet(),
            ),
        )
        assertEquals(
            emptyList(),
            dangerReasons(
                AREA_CONFIG,
                KIND_SETTINGS,
                "app",
                buildJsonObject { put("zone", "UTC") },
                buildJsonObject { put("zone", "Asia/Shanghai") },
                emptySet(),
            ),
        )
    }

    @Test
    fun `app settings plugins and updates fields are staged, never auto-applied`() {
        val pluginsChange = dangerReasons(
            AREA_CONFIG,
            KIND_SETTINGS,
            "app",
            buildJsonObject { put("plugins", buildJsonObject {}) },
            buildJsonObject { put("plugins", "a-new-plugin") },
            emptySet(),
        )
        assertEquals(listOf("settings_field:plugins"), pluginsChange)

        val updatesChange = dangerReasons(
            AREA_CONFIG,
            KIND_SETTINGS,
            "app",
            buildJsonObject { put("updates", buildJsonObject { put("install", false) }) },
            buildJsonObject { put("updates", buildJsonObject { put("install", true) }) },
            emptySet(),
        )
        assertEquals(listOf("settings_field:updates"), updatesChange)
    }

    @Test
    fun `a mix of an allowlisted and a non-allowlisted app settings field stages, naming only the latter`() {
        val reasons = dangerReasons(
            AREA_CONFIG,
            KIND_SETTINGS,
            "app",
            buildJsonObject {
                put("language", "en")
                put("plugins", buildJsonObject {})
            },
            buildJsonObject {
                put("language", "zh-CN")
                put("plugins", "a-new-plugin")
            },
            emptySet(),
        )

        assertEquals(listOf("settings_field:plugins"), reasons)
    }

    @Test
    fun `a plugin's own settings have no allowlist, so even a harmlessly-named field stages`() {
        val reasons = dangerReasons(
            AREA_CONFIG,
            KIND_SETTINGS,
            "heartbeat",
            buildJsonObject { put("language", "en") },
            buildJsonObject { put("language", "zh-CN") },
            emptySet(),
        )

        assertEquals(listOf("settings_field:language"), reasons)
    }

    @Test
    fun `a non-object settings document stages as a wildcard, not as no change at all`() {
        val reasons = dangerReasons(AREA_CONFIG, KIND_SETTINGS, "app", null, JsonPrimitive("not an object"), emptySet())

        assertEquals(listOf("settings_field:*"), reasons)
    }

    @Test
    fun `dangerReasons always stages mcp, provider and instructions kinds`() {
        assertEquals(listOf("mcp_server"), dangerReasons(AREA_CONFIG, KIND_MCP, "notes", null, null, emptySet()))
        assertEquals(
            listOf("provider_endpoint"),
            dangerReasons(AREA_CONFIG, KIND_PROVIDER, "p/x", null, null, emptySet()),
        )
        assertEquals(
            listOf("instructions_file"),
            dangerReasons(AREA_CONFIG, KIND_INSTRUCTIONS, "AGENTS.md", null, null, emptySet()),
        )
    }

    @Test
    fun `dangerReasons always stages a skill, any create, change or delete of one`() {
        assertEquals(listOf("skill"), dangerReasons(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill", null, null, emptySet()))
        assertEquals(
            listOf("skill"),
            dangerReasons(AREA_SKILL, agentSkillScope("helper"), "my-skill", JsonPrimitive("x"), null, emptySet()),
        )
    }

    @Test
    fun `dangerReasons stages overwriting an existing secret but not a brand new one`() {
        val old = JsonPrimitive("old")
        val new = JsonPrimitive("new")
        assertEquals(listOf("secret_overwrite"), dangerReasons(AREA_SECRET, "", "X", old, new, emptySet()))
        assertEquals(emptyList(), dangerReasons(AREA_SECRET, "", "X", null, new, emptySet()))
    }

    @Test
    fun `dangerReasons also names a suspicious secret reference inside a config document`() {
        val after = buildJsonObject { put("apiKey", "\${env:ALEXANDRITE_TELEGRAM_TOKEN}") }

        val reasons = dangerReasons(AREA_CONFIG, KIND_PROVIDER, "evil/x", null, after, emptySet())

        assertTrue("provider_endpoint" in reasons)
        assertTrue("suspicious_secret_reference" in reasons)
    }

    @Test
    fun `dangerReasons is empty for a harmless settings or agent change`() {
        assertEquals(
            emptyList(),
            dangerReasons(
                AREA_CONFIG,
                KIND_SETTINGS,
                "app",
                buildJsonObject { put("language", "en") },
                buildJsonObject { put("language", "zh-CN") },
                emptySet(),
            ),
        )
        assertEquals(
            emptyList(),
            dangerReasons(
                AREA_CONFIG,
                KIND_AGENT,
                "helper",
                buildJsonObject { put("name", "Helper") },
                buildJsonObject { put("name", "Helper 2") },
                emptySet(),
            ),
        )
    }
}
