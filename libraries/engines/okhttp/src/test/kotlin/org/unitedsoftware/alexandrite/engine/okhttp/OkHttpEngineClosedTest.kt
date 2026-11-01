package org.unitedsoftware.alexandrite.engine.okhttp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OkHttpEngineClosedTest {
    private val release = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("a".toByteArray())
            exchange.responseBody.flush()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        start()
    }

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    @Test
    fun `a read after the engine closed fails as the exchange does`() = runBlocking {
        val engine = OkHttpEngine()
        val response = engine.open(HttpEngineRequest.builder("GET", "http://127.0.0.1:${server.address.port}/").build())
        assertNotNull(response.body.read())

        engine.close()

        assertFailsWith<IOException> { response.body.read() }
        response.close()
    }
}
