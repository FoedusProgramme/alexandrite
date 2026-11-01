package org.unitedsoftware.alexandrite.runtime.engine

import org.unitedsoftware.alexandrite.sdk.di.container.PluginBindings
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentEnginesTest {
    @Test
    fun `the JDK defaults are bound for the ports no plugin binds`() {
        val parser = object : HtmlParser {
            override fun parse(html: String, baseUrl: String?) = HtmlDocument(null, "", emptyList())
        }
        val engine = PluginBindings("engine", listOf(instanceBinding(key<HtmlParser>(), parser, "engine", "Parser")))

        assertEquals(listOf(key<HtmlParser>(), key<ImageCodec>()), contentEngineBindings(emptyList()).map { it.key })
        assertEquals(listOf(key<ImageCodec>()), contentEngineBindings(listOf(engine)).map { it.key })
        assertEquals("alexandrite-runtime", contentEngineBindings(emptyList()).map { it.plugin }.distinct().single())
    }

    @Test
    fun `the Swing default reads a page`() {
        val page = SwingHtmlParser().parse("<title>T</title><p>Hi <a href=x>there</a></p>", "https://example.org/a/")
        assertEquals("T", page.title)
        assertEquals("Hi there", page.text)
        assertEquals("https://example.org/a/x", page.links.single().href)
    }
}
