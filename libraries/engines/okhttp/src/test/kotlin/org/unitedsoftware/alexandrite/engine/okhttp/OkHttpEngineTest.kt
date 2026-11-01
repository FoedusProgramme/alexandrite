package org.unitedsoftware.alexandrite.engine.okhttp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.testkit.HTTP_ENGINE_CONTRACT
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertIs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkHttpEngineTest {
    private val engine = OkHttpEngine()

    @AfterAll
    fun close() {
        engine.close()
    }

    @TestFactory
    fun `the OkHttp engine keeps the HTTP engine contract`(): List<DynamicTest> = HTTP_ENGINE_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(engine) } }
    }

    @Test
    fun `the plugin binds its engine`() {
        runBlocking {
            PluginHarness.builder(AlexandriteEngineOkhttpIndex()).build().run {
                assertIs<OkHttpEngine>(get<HttpEngine>())
            }
        }
    }
}
