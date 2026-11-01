package org.unitedsoftware.alexandrite.testkit.net

import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineResponse
import org.unitedsoftware.alexandrite.testkit.store.expect
import org.unitedsoftware.alexandrite.testkit.store.expectEqual
import java.io.ByteArrayOutputStream

/** One run of an engine check: the engine under check and a server of the run's own. */
internal class HttpEngineRun(val engine: HttpEngine, val server: HttpEngineServer) {
    fun request(method: String = "GET", path: String = "/", block: HttpEngineRequest.Builder.() -> Unit = {}) =
        HttpEngineRequest.builder(method, "${server.baseUrl}$path").apply(block).build()

    suspend fun open(request: HttpEngineRequest = request()): HttpEngineResponse = engine.open(request)
}

/** The whole body, after which the response is closed. */
internal suspend fun HttpEngineResponse.readAll(): ByteArray = use {
    val out = ByteArrayOutputStream()
    while (true) {
        val piece = body.read() ?: break
        expect(piece.isNotEmpty()) { "The engine handed an empty piece of the body." }
        out.write(piece)
    }
    expectEqual(null, body.read(), "a read after the body's end")
    out.toByteArray()
}
