package org.unitedsoftware.alexandrite.provider.deepseek

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

class DeepSeekPluginTest {
    private fun harness(config: String) =
        PluginHarness.builder(AlexandriteProviderDeepseekIndex()).plugin(AlexandriteEngineJdkHttpIndex()).config(config)

    @Test
    fun `an endpoint needs only its key and bakes turn context unless told otherwise`() = runBlocking {
        val config = """
            {"endpoints": {
                "deepseek": {"apiKey": "sk-plugin-test-key"},
                "proxy": {"apiKey": "sk-plugin-test-key", "baseUrl": "https://proxy.example.com",
                    "turnContext": "transient"}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("deepseek", "proxy"), endpoints.map { it.id.value })
            val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
            assertEquals(listOf(TurnContextMode.NOT_SUPPORTED, TurnContextMode.TRANSIENT), modes)
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        Unit
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            """{"endpoints": {"x": {"baseUrl": "https://api.example.com"}}}""" to "apiKey",
            """{"endpoints": {"x": {"apiKey": "hunter2", "promptCacheKey": true}}}""" to "promptCacheKey",
            """{"endpoints": {"x": {"apiKey": "hunter2", "baseUrl": "ftp://a"}}}""" to "baseUrl must be",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.deepseek" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
