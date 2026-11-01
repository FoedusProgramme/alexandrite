package org.unitedsoftware.alexandrite.testkit.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.unitedsoftware.alexandrite.internal.http.JdkHttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineResponse
import org.unitedsoftware.alexandrite.sdk.net.HttpHeaders
import org.unitedsoftware.alexandrite.sdk.net.HttpResponseBody
import org.unitedsoftware.alexandrite.sdk.net.rebuild
import org.unitedsoftware.alexandrite.testkit.HTTP_ENGINE_CONTRACT
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A way to break the engine contract, and the name of the check that catches it. */
private enum class EngineFlaw(val check: String) {
    DROPS_HEADERS("a request reaches the server as HTTP/1.1 with its method, target, headers and body"),
    EMPTY_PIECES("the status, headers and body come back as the server sent them"),
}

/** [engine] broken by [flaw]. */
private class FlawedEngine(private val engine: HttpEngine, private val flaw: EngineFlaw) : HttpEngine {
    override suspend fun open(request: HttpEngineRequest): HttpEngineResponse = when (flaw) {
        EngineFlaw.DROPS_HEADERS -> engine.open(request.rebuild { headers(HttpHeaders.EMPTY) })

        EngineFlaw.EMPTY_PIECES -> {
            val response = engine.open(request)
            val body = object : HttpResponseBody {
                private var emptied = false

                override suspend fun read(): ByteArray? {
                    if (emptied) return response.body.read()
                    emptied = true
                    return ByteArray(0)
                }

                override fun close() = response.body.close()
            }
            HttpEngineResponse(response.status, response.headers, body)
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpEngineContractTest {
    private val engine = JdkHttpEngine()

    @AfterAll
    fun close() {
        engine.close()
    }

    @TestFactory
    fun `the JDK engine keeps the engine contract`(): List<DynamicTest> = HTTP_ENGINE_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { runBlocking { check.run(engine) } }
    }

    @Test
    fun `each flaw breaks the check that names it`() {
        for (flaw in EngineFlaw.entries) {
            val check = HTTP_ENGINE_CONTRACT.single { it.name == flaw.check }
            assertFailsWith<AssertionError>("$flaw") { runBlocking { check.run(FlawedEngine(engine, flaw)) } }
        }
    }
}
