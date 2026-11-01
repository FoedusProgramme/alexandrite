package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicCompatiblePluginTest {
    private fun harness(config: String) = PluginHarness.builder(
        AlexandriteProviderAnthropicCompatibleIndex(),
    ).plugin(AlexandriteEngineJdkHttpIndex()).config(config)

    /** The config of one endpoint `x` with [fields]. */
    private fun endpoint(fields: String = ""): String = """{"endpoints": {"x": {"apiKey": "hunter2"$fields}}}"""

    @Test
    fun `each configured endpoint is contributed under its id with its turn context modes`() = runBlocking {
        val config = """
            {"endpoints": {
                "anthropic": {"apiKey": "sk-plugin-test-key"},
                "newest": {"apiKey": "sk-plugin-test-key", "turnScopedSystem": true,
                    "betas": ["some-beta-2026-01-01"], "promptCaching": false},
                "proxy": {"baseUrl": "https://proxy.example.com/anthropic", "turnContext": "bake",
                    "headers": {"X-Team": "core"}}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("anthropic", "newest", "proxy"), endpoints.map { it.id.value })
            val modes = endpoints.map { endpoint ->
                Trust.entries.map { endpoint.turnContextMode("m", ModelOptions.DEFAULT, it) }
            }
            assertEquals(
                listOf(
                    listOf(TurnContextMode.TRANSIENT, TurnContextMode.TRANSIENT),
                    listOf(TurnContextMode.KEPT_UNRENDERED, TurnContextMode.TRANSIENT),
                    listOf(TurnContextMode.NOT_SUPPORTED, TurnContextMode.NOT_SUPPORTED),
                ),
                modes,
            )
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        Unit
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            endpoint(""", "baseUrl": "https://user:hunter2@api.example.com"""") to "baseUrl must be",
            endpoint(""", "headers": {"X-Api-Key": "k"}""") to "may not set x-api-key when apiKey is set",
            endpoint(""", "headers": {"anthropic-beta": "b"}""") to "may not set anthropic-beta",
            endpoint(""", "betas": ["a, b"]""") to "betas holds",
            endpoint(""", "promptCacheKey": true""") to "promptCacheKey",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.anthropic-compatible" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
