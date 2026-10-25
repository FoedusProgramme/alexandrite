package org.unitedsoftware.alexandrite.provider.pleochroic

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PleochroicConfigTest {
    private fun read(json: String) =
        Json.decodeFromString(PleochroicConfig.serializer(), json).endpoints.values.single()

    @Test
    fun `an endpoint that sets only its key takes the Pleochroic gateway's default URL`() {
        val endpoint = read("""{"endpoints": {"x": {"apiKey": "plc-test-key"}}}""")

        val settings = endpoint.settings
        assertEquals(PLEOCHROIC_BASE_URL, settings.baseUrl)
        assertEquals(TurnContextSetting.TRANSIENT, settings.turnContext)
        assertEquals(true to emptyMap(), settings.discover to settings.models)
    }

    @Test
    fun `a self-hosted standalone-service names its own URL`() {
        val endpoint = read(
            """{"endpoints": {"x": {"baseUrl": "https://pleochroic.example.com/v1", "apiKey": "service-token"}}}""",
        )

        assertEquals("https://pleochroic.example.com/v1", endpoint.settings.baseUrl)
    }

    @Test
    fun `an endpoint without a key fails to read`() {
        assertTrue(
            runCatching { read("""{"endpoints": {"x": {}}}""") }.isFailure,
        )
    }
}
