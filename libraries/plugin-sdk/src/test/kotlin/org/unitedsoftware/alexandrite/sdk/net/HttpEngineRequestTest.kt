package org.unitedsoftware.alexandrite.sdk.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HttpEngineRequestTest {
    private fun request(block: HttpEngineRequest.Builder.() -> Unit = {}) = HttpEngineRequest.builder(
        "POST",
        "https://user:pw@example.com:8443/bot123:secret/send?key=k",
    ).apply(block).build()

    @Test
    fun `a request prints its method and origin alone`() {
        val printed = request { header("authorization", "Bearer sk-secret") }.toString()

        assertEquals("HttpEngineRequest(POST https://example.com:8443)", printed)
        assertFalse("secret" in printed)
    }

    @Test
    fun `header names are one ignoring case, spelled as first given, and their values keep their order`() {
        val built = request {
            header("X-Many", "a")
            header("x-many", "b")
        }

        assertEquals(listOf("a", "b"), built.headers.all("X-MANY"))
        assertEquals(setOf("X-Many"), built.headers.names)
        assertEquals(built.headers, HttpHeaders.of("x-many" to "a", "X-MANY" to "b"))
        assertEquals("HttpHeaders(X-Many)", built.headers.toString())
    }

    @Test
    fun `a request refuses what an engine would write itself or could not send`() {
        val refused = listOf<HttpEngineRequest.Builder.() -> Unit>(
            { header("Content-Length", "3") },
            { header("host", "example.com") },
            { header("x-key", "a\r\nb") },
            { header("x-key", "caf\u00e9") },
            { header("x-key", "a\u0085b") },
            { header("x-key", "\u0100") },
            { header("bad name", "a") },
            { method("GE T") },
            { url("ftp://example.com/") },
            { connectTimeout(Duration.ZERO) },
        )

        for (change in refused) assertFailsWith<IllegalArgumentException> { request(change) }
        assertEquals(listOf("a \t~"), request { header("x-key", "a \t~") }.headers.all("x-key"))
    }

    @Test
    fun `a request rebuilt keeps what it does not change`() {
        val body = HttpRequestBody.of("{}")
        val built = request {
            header("x-one", "1")
            body(body)
            connectTimeout(3.seconds)
        }

        val rebuilt = built.rebuild { method("PUT") }

        assertEquals("PUT", rebuilt.method)
        assertEquals(built.url, rebuilt.url)
        assertEquals(built.headers, rebuilt.headers)
        assertEquals(body, rebuilt.body)
        assertEquals(3.seconds, rebuilt.connectTimeout)
    }

    @Test
    fun `a body of bytes is read whole from each source it opens`() {
        val body = HttpRequestBody.of("Grüße")

        val reads = List(2) {
            body.open().use { source ->
                val buffer = ByteArray(16)
                val parts = generateSequence { source.read(buffer, 0, 3).takeIf { it >= 0 } }
                    .map { buffer.copyOf(it) }
                    .toList()
                parts.reduce(ByteArray::plus).decodeToString()
            }
        }

        assertEquals(7L, body.length)
        assertEquals(listOf("Grüße", "Grüße"), reads)
    }
}
