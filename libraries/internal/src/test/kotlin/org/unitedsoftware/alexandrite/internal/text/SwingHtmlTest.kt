package org.unitedsoftware.alexandrite.internal.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SwingHtmlTest {
    @Test
    fun `a query parameter named like a character reference stays as written`() {
        val read = SwingHtml.read("""<a href="/s?a=1&times=2&copy=3&amp;b=4">find</a>""", "https://example.org/")
        assertEquals("https://example.org/s?a=1&times=2&copy=3&b=4", read.links.single().href)
    }

    @Test
    fun `line ends inside pre survive the end tags that follow them`() {
        val read = SwingHtml.read("<pre><span>one\n</span><span>two\r\n</span>three</pre>", null)
        assertEquals("one\ntwo\nthree", read.text)
    }

    @Test
    fun `a link around blocks keeps their text and the words after it apart`() {
        val read = SwingHtml.read("<p>x</p><a href=/card><h3>Title</h3><p>Body</p></a>after", "https://example.org/")
        assertEquals("x\nTitle\nBody\nafter", read.text)
        assertEquals("Title Body", read.links.single().text)
    }

    @Test
    fun `what the HTML standard reads as bogus comments adds no text`() {
        assertEquals("a b c", SwingHtml.read("a<?xml version=\"1.0\"?>b</ junk>c", null).text)
    }
}
