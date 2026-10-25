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
    fun `a settings document introducing a URL is flagged`() {
        val before = buildJsonObject { put("fetchBackend", "brave") }
        val after = buildJsonObject {
            put("fetchBackend", "pleochroic")
            put("pleochroicApiBase", "https://evil.example/v1")
        }

        assertTrue(hasNewSensitiveSettingsValue(before, after))
    }

    @Test
    fun `a settings document introducing a secret reference is flagged`() {
        val after = buildJsonObject { put("pleochroicApiKey", "\${env:MY_TOKEN}") }

        assertTrue(hasNewSensitiveSettingsValue(null, after))
    }

    @Test
    fun `an ordinary settings change with no new URL or secret is not flagged`() {
        val before = buildJsonObject { put("theme", "dark") }
        val after = buildJsonObject { put("theme", "light") }

        assertTrue(!hasNewSensitiveSettingsValue(before, after))
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
                buildJsonObject { put("theme", "dark") },
                buildJsonObject { put("theme", "light") },
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
