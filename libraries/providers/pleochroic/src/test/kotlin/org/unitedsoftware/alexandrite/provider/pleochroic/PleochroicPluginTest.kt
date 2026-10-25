package org.unitedsoftware.alexandrite.provider.pleochroic

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.provider.common.chat.chunk
import org.unitedsoftware.alexandrite.provider.common.chat.chunks
import org.unitedsoftware.alexandrite.provider.common.chat.with
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PleochroicPluginTest {
    private fun harness(config: String) = PluginHarness.builder(AlexandriteProviderPleochroicIndex()).config(config)

    @Test
    fun `an endpoint needs its base URL and key and keeps turn context transient unless told otherwise`() =
        runBlocking {
            val config = """
                {"endpoints": {
                    "gateway": {"baseUrl": "https://api.pleochroic.co.uk/v1", "apiKey": "plc-plugin-test-key"},
                    "baked": {"baseUrl": "https://api.pleochroic.co.uk/v1", "apiKey": "plc-plugin-test-key",
                        "turnContext": "bake"}
                }}
            """.trimIndent()

            harness(config).build().run {
                val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
                assertEquals(listOf("gateway", "baked"), endpoints.map { it.id.value })
                val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
                assertEquals(listOf(TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED), modes)
                assertFalse("plc-plugin-test-key" in endpoints.toString())
            }
            Unit
        }

    @Test
    fun `an endpoint streams a reply through the gateway`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(chunks(chunk { with("content", "Hi.") }, chunk("stop")))
            val config = """
                {"endpoints": {"gateway": {"baseUrl": "${server.baseUrl}/v1", "apiKey": "plc-plugin-test-key",
                    "discover": false, "models": {"m": {}}}}}
            """.trimIndent()

            harness(config).build().run {
                val endpoint = getAll<ModelProvider>().single().endpoints.single()
                val turn = testTurn()
                val request = ModelRequest.builder(
                    ModelRef(endpoint.id, "m"),
                    listOf(testUserEntry(turn)),
                    0,
                    RequestIds(turn.conversation, turn.id, 0),
                ).build()
                val events = endpoint.stream(request).toList()
                assertTrue(events.last() is ModelEvent.Completed)
            }

            assertEquals("m", server.requests.single().json()["model"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            """{"endpoints": {"x": {"baseUrl": "https://api.example.com"}}}""" to "apiKey",
            """{"endpoints": {"x": {"apiKey": "hunter2", "baseUrl": "https://api.example.com",
                "headers": {"Authorization": "b"}}}}""" to "authorization",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.pleochroic" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
