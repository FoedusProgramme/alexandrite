package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretRefsTest {
    @Test
    fun `a wire secret reference to a synced secret is accepted and translated to the local form`() {
        val data = buildJsonObject { put("apiKey", "\${secret:MY_TOKEN}") }

        assertNull(suspiciousSecretReference(data, setOf("MY_TOKEN")))
        assertEquals(buildJsonObject { put("apiKey", "\${env:MY_TOKEN}") }, toLocalSecretRefs(data, setOf("MY_TOKEN")))
    }

    @Test
    fun `a wire secret reference naming a secret this account does not sync is suspicious`() {
        val data = buildJsonObject { put("apiKey", "\${secret:NOT_SYNCED}") }

        assertEquals("\${secret:NOT_SYNCED}", suspiciousSecretReference(data, setOf("MY_TOKEN")))
        // Never translated: it stays a harmless literal string rather than becoming a usable reference.
        assertEquals(data, toLocalSecretRefs(data, setOf("MY_TOKEN")))
    }

    @Test
    fun `a literal local-form reference is always suspicious, even naming a synced secret`() {
        val data = buildJsonObject { put("apiKey", "\${env:MY_TOKEN}") }

        assertEquals("\${env:MY_TOKEN}", suspiciousSecretReference(data, setOf("MY_TOKEN")))
    }

    @Test
    fun `a hijack attempt naming an unrelated local variable is caught the same way`() {
        val data = buildJsonObject {
            put("baseUrl", "https://evil.example/v1")
            put("apiKey", "\${env:ALEXANDRITE_TELEGRAM_TOKEN}")
        }

        assertEquals("\${env:ALEXANDRITE_TELEGRAM_TOKEN}", suspiciousSecretReference(data, emptySet()))
    }

    @Test
    fun `ordinary values hold no reference and are not suspicious`() {
        val data = buildJsonObject {
            put("baseUrl", "https://openrouter.ai/api/v1")
            put("model", "gpt-5")
        }

        assertNull(suspiciousSecretReference(data, emptySet()))
    }

    @Test
    fun `a local env reference to a synced secret is translated to the wire form for a push`() {
        val data = buildJsonObject { put("apiKey", "\${env:MY_TOKEN}") }

        assertEquals(
            buildJsonObject {
                put("apiKey", "\${secret:MY_TOKEN}")
            },
            toWireSecretRefs(data, setOf("MY_TOKEN")),
        )
    }

    @Test
    fun `a local env reference to a secret this account does not sync is left as is for a push`() {
        val data = buildJsonObject { put("apiKey", "\${env:LOCAL_ONLY}") }

        assertEquals(data, toWireSecretRefs(data, setOf("MY_TOKEN")))
    }

    @Test
    fun `nested arrays and objects are walked for references too`() {
        val data = buildJsonObject {
            put(
                "headers",
                buildJsonArray {
                    add(buildJsonObject { put("value", "\${secret:MY_TOKEN}") })
                },
            )
        }

        assertNull(suspiciousSecretReference(data, setOf("MY_TOKEN")))
        val translated = toLocalSecretRefs(data, setOf("MY_TOKEN")) as JsonObject
        val headers = translated["headers"] as JsonArray
        val value = (headers[0] as JsonObject)["value"] as JsonPrimitive
        assertEquals("\${env:MY_TOKEN}", value.content)
    }
}
