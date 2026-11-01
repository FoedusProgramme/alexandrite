package org.unitedsoftware.alexandrite.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SseParserTest {
    private fun parse(vararg pieces: String): List<String> {
        val parser = SseParser()
        val events = pieces.flatMap { parser.feed(it.toByteArray()) } + listOfNotNull(parser.finish())
        return events.map { "${it.type}|${it.id}|${it.data}" }
    }

    @Test
    fun `data lines of one event join with line feeds`() {
        assertEquals(listOf("message|null|a\nb\n"), parse("data: a\ndata:b\ndata\n\n"))
    }

    @Test
    fun `an event names its type, which resets after dispatch`() {
        assertEquals(listOf("ping|null|1", "message|null|2"), parse("event: ping\ndata: 1\n\ndata: 2\n\n"))
    }

    @Test
    fun `ids carry over to later events and an id with NUL is ignored`() {
        assertEquals(
            listOf("message|7|a", "message|7|b", "message|null|c"),
            parse("id: 7\ndata: a\n\nid: 8\u0000\ndata: b\n\nid\ndata: c\n\n"),
        )
    }

    @Test
    fun `retry takes digits only`() {
        val parser = SseParser()
        parser.feed("retry: 1500\n\nretry: 15x\n\nretry: -1\n\n".toByteArray())

        assertEquals(1500, parser.retry)
    }

    @Test
    fun `comments, unknown fields and events without data dispatch nothing`() {
        assertEquals(listOf("message|null|x"), parse(": keep-alive\n\nfoo: bar\nevent: e\n\ndata: x\n\n"))
    }

    @Test
    fun `lines end at CR, LF and CRLF, also across pieces`() {
        assertEquals(
            listOf("message|null|a", "message|null|b", "message|null|c"),
            parse("data: a\r\n\r", "\ndata: b\r\rdata: c\n", "\n"),
        )
    }

    @Test
    fun `the stream's end dispatches a last event without a blank line`() {
        assertEquals(listOf("message|null|a", "message|null|[DONE]"), parse("data: a\n\ndata: [DONE]"))
        assertEquals(listOf("message|null|b"), parse("data: b\n"))
    }

    @Test
    fun `an empty data field dispatches an empty event`() {
        assertEquals(listOf("message|null|"), parse("data:\n\n"))
    }

    @Test
    fun `a leading byte order mark is dropped`() {
        assertEquals(listOf("message|null|a"), parse("\uFEFFdata: a\n\n"))
    }

    @Test
    fun `multibyte characters split across pieces decode whole`() {
        val bytes = "data: 你好\n\n".toByteArray()
        val parser = SseParser()
        val events = bytes.indices.flatMap { parser.feed(bytes, it, 1) }

        assertEquals(listOf("你好"), events.map { it.data })
        assertNull(parser.finish())
    }

    @Test
    fun `a line or the data of an event past the limit fails the stream`() {
        fun parse(text: String) = SseParser(limit = 10).feed(text.toByteArray()).map { it.data }

        assertEquals(listOf("abcd\nefghi"), parse("data:abcd\ndata:efghi\n\n"))
        assertEquals(List(5) { "abcd" }, parse("data:abcd\n\n".repeat(5)))
        for (text in listOf("data:abcdef\n", "data:abcd\ndata:efgh\ndata:ij\n")) {
            assertEquals(HttpFailureKind.PROTOCOL, assertFailsWith<HttpException>(text) { parse(text) }.kind)
        }
    }

    @Test
    fun `a value keeps every space after the first`() {
        assertEquals(listOf("message|null|  x "), parse("data:   x \n\n"))
    }
}
