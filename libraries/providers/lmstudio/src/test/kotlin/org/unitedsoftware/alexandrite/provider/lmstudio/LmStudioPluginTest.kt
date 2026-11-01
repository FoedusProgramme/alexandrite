package org.unitedsoftware.alexandrite.provider.lmstudio

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

class LmStudioPluginTest {
    private fun harness(config: String) =
        PluginHarness.builder(AlexandriteProviderLmstudioIndex()).plugin(AlexandriteEngineJdkHttpIndex()).config(config)

    @Test
    fun `an endpoint needs no settings and keeps turn context transient unless told otherwise`() = runBlocking {
        val config = """
            {"endpoints": {
                "local": {},
                "studio": {"baseUrl": "http://127.0.0.1:4321/v1", "apiKey": "sk-plugin-test-key", "turnContext": "bake"}
            }}
        """.trimIndent()

        harness(config).build().run {
            val endpoints = getAll<ModelProvider>().flatMap { it.endpoints }
            assertEquals(listOf("local", "studio"), endpoints.map { it.id.value })
            val modes = endpoints.map { it.turnContextMode("m", ModelOptions.DEFAULT, Trust.UNTRUSTED) }
            assertEquals(listOf(TurnContextMode.TRANSIENT, TurnContextMode.NOT_SUPPORTED), modes)
            assertFalse("sk-plugin-test-key" in endpoints.toString())
        }
        Unit
    }

    @Test
    fun `a malformed endpoint fails the start at CONFIG without showing its values`() = runBlocking {
        val cases = listOf(
            """{"endpoints": {"x": {"apiKey": "hunter2", "baseUrl": "ftp://a"}}}""" to "baseUrl must be",
        )

        for ((config, expected) in cases) {
            val error = assertFailsWith<RuntimeStartException> { harness(config).build().run {} }
            assertEquals(StartStage.CONFIG, error.stage)
            val message = error.problems.joinToString { it.message }
            assertTrue(expected in message, message)
            assertTrue("providers.lmstudio" in message, message)
            assertFalse("hunter2" in message, message)
        }
    }
}
