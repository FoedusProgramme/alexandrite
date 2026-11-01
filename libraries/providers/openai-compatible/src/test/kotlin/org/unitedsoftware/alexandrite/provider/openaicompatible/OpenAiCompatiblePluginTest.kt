package org.unitedsoftware.alexandrite.provider.openaicompatible

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.provider.common.chat.chunk
import org.unitedsoftware.alexandrite.provider.common.chat.chunks
import org.unitedsoftware.alexandrite.provider.common.chat.with
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

class OpenAiCompatiblePluginTest {
    private fun harness(config: String) = PluginHarness.builder(
        AlexandriteProviderOpenaiCompatibleIndex(),
    ).plugin(AlexandriteEngineJdkHttpIndex()).config(config)

    /** The config of one endpoint `x` at an example URL, with [fields] added. */
    private fun endpoint(fields: String = ""): String =
        """{"endpoints": {"x": {"baseUrl": "https://api.example.com"$fields}}}"""

    @Test
    fun `each configured endpoint is contributed under its id with its turn context mode`() = runBlocking {
        val config = """
            {"endpoints": {
                "vllm": {"baseUrl": "http://127.0.0.1:8000/v1", "apiKey": "sk-plugin-test-key"},
                "proxy": {"baseUrl": "https://proxy.example.com/v1", "turnContext": "bake",
                    "headers": {"X-Team": "core"}, "promptCacheKey": true}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("vllm", "proxy"), endpoints.map { it.id.value })
            val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
            assertEquals(listOf(TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED), modes)
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        Unit
    }

    @Test
    fun `an endpoint sends the cache key`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(chunks(chunk { with("content", "Hi.") }, chunk("stop")))
            val config = """
                {"endpoints": {"proxy": {"baseUrl": "${server.baseUrl}/v1", "promptCacheKey": true,
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
            assertEquals("cache-7", sent.json()["prompt_cache_key"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            endpoint().replace("https://", "https://user:hunter2@") to "baseUrl must be",
            endpoint(""", "profile": "deepseek"""") to "profile",
            """{"endpoints": {"x": {"apiKey": "k"}}}""" to "baseUrl",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.openai-compatible" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
