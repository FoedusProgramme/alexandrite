package org.unitedsoftware.alexandrite.http.common

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultipartBodyTest {
    private val server = TestServer()
    private val transport = HttpTransport(SHORT, ENGINE)

    @AfterTest
    fun close() {
        transport.close()
        server.close()
    }

    @Test
    fun `fields and files are sent with their length, the file streamed byte for byte`() = runBlocking {
        server.handle { it.header("content-type", "application/json").chunk("{}").end() }
        val file = ByteArray(70_000) { (it % 251).toByte() }
        val body = MultipartBody.builder()
            .field("chat_id", "1001")
            .field("caption", "Größe: \"big\"")
            .file("document", "re\"port\n.bin", "application/octet-stream", file.size.toLong()) {
                ByteArrayInputStream(file)
            }
            .build()

        transport.send(HttpCall("POST", server.uri.resolve("/upload"), multipart = body))

        val request = server.nextRequest()
        val boundary = body.contentType.substringAfter("boundary=")
        assertEquals(body.contentType, request.headers["content-type"])
        assertEquals("${body.length}", request.headers["content-length"])
        assertEquals(body.length, request.bytes.size.toLong())
        val text = request.bytes.toString(Charsets.ISO_8859_1)
        val head =
            "--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"re%22port%0A.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        val start = text.indexOf(head) + head.length
        assertTrue(start > head.length, text.take(400))
        assertContentEquals(file, request.bytes.copyOfRange(start, start + file.size))
        assertTrue(text.endsWith("\r\n--$boundary--\r\n"), text.takeLast(80))
        val caption = "--$boundary\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" +
            "Größe: \"big\"\r\n"
        assertTrue(caption in request.body, request.body.take(400))
    }

    @Test
    fun `an empty file is sent as an empty part`() = runBlocking {
        server.handle { it.chunk("ok").end() }
        val body = MultipartBody.builder().file("document", "empty.txt", "text/plain", 0) {
            error("Never read.")
        }.build()

        transport.send(HttpCall("POST", server.uri.resolve("/upload"), multipart = body))

        val request = server.nextRequest()
        assertEquals(body.length, request.bytes.size.toLong())
        assertTrue("filename=\"empty.txt\"\r\nContent-Type: text/plain\r\n\r\n\r\n--" in request.body, request.body)
    }

    @Test
    fun `each body has a boundary of its own, and a call carries one body`() {
        val first = MultipartBody.builder().field("a", "1").build()
        val second = MultipartBody.builder().field("a", "1").build()

        assertTrue(first.contentType != second.contentType)
        assertFailsWith<IllegalArgumentException> {
            HttpCall("POST", server.uri, body = "{}", multipart = first)
        }
        assertFailsWith<IllegalArgumentException> {
            MultipartBody.builder().file("document", "a", "text/plain\r\nX: y", 1) {
                ByteArrayInputStream(ByteArray(1))
            }
        }
    }
}
