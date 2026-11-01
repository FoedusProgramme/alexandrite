package org.unitedsoftware.alexandrite.engine.swinghtml

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.HTML_PARSER_CONTRACT
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SwingHtmlTest {
    @TestFactory
    fun `the Swing parser keeps the HTML parser contract`(): List<DynamicTest> =
        HTML_PARSER_CONTRACT.map { check -> DynamicTest.dynamicTest(check.name) { check.run(SwingHtmlParser()) } }

    @Test
    fun `the plugin binds its parser`() {
        runBlocking {
            PluginHarness.builder(AlexandriteEngineSwingHtmlIndex()).build().run {
                assertIs<SwingHtmlParser>(get<HtmlParser>())
            }
        }
    }

    @Test
    fun `a page is read with its title, text and links resolved`() {
        val page = SwingHtmlParser().parse("<title>T</title><p>Hi <a href=x>there</a></p>", "https://example.org/a/")

        assertEquals("T", page.title)
        assertEquals("Hi there", page.text)
        assertEquals("https://example.org/a/x", page.links.single().href)
    }

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
