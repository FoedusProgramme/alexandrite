package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.provider.common.responses.sse
import org.unitedsoftware.alexandrite.provider.common.responses.textEvents
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
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

class OpenAiResponsesPluginTest {
    private fun harness(config: String) = PluginHarness.builder(
        AlexandriteProviderOpenaiResponsesIndex(),
    ).plugin(AlexandriteEngineJdkHttpIndex()).config(config)

    /** The config of one endpoint `x` with [fields]. */
    private fun endpoint(fields: String = ""): String = """{"endpoints": {"x": {"apiKey": "hunter2"$fields}}}"""

    @Test
    fun `each configured endpoint is contributed under its id with its turn context mode`() = runBlocking {
        val config = """
            {"endpoints": {
                "openai": {"apiKey": "sk-plugin-test-key"},
                "proxy": {"baseUrl": "https://proxy.example.com/v1", "turnContext": "bake",
                    "headers": {"X-Team": "core"}, "reasoningSummary": null, "promptCacheKey": false}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("openai", "proxy"), endpoints.map { it.id.value })
            val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
            assertEquals(listOf(TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED), modes)
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        Unit
    }

    @Test
    fun `an endpoint posts to responses with the cache key`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(sse(*textEvents(listOf("Hi."))))
            val config = """
                {"endpoints": {"proxy": {"baseUrl": "${server.baseUrl}/v1", "apiKey": "sk-plugin-test-key",
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
                ).cacheKey("cache-7").build()
                endpoint.stream(request).toList()
            }

            val sent = server.requests.single()
            assertEquals("/v1/responses", sent.path)
            assertEquals("cache-7", sent.json()["prompt_cache_key"]?.jsonPrimitive?.content)
            assertEquals("false", sent.json()["store"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            endpoint(""", "baseUrl": "https://user:hunter2@api.example.com"""") to "baseUrl must be",
            endpoint(""", "headers": {"Authorization": "k"}""") to "may not set authorization when apiKey is set",
            endpoint(""", "reasoningSummary": "verbose"""") to "reasoningSummary must be",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.openai-responses" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
