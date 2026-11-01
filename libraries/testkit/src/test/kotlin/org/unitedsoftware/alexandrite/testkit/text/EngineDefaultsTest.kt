package org.unitedsoftware.alexandrite.testkit.text

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.HTML_PARSER_CONTRACT
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.TestIndex
import org.unitedsoftware.alexandrite.testkit.blocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class EngineDefaultsTest {
    @TestFactory
    fun `the HTML parser the runtime binds on the JDK keeps the contract`(): List<DynamicTest> {
        val parser = defaults(TestIndex("probe")).first
        return HTML_PARSER_CONTRACT.map { check -> DynamicTest.dynamicTest(check.name) { check.run(parser) } }
    }

    @Test
    fun `the image codec the runtime binds on the JDK reads and fits images`() {
        val codec = defaults(TestIndex("probe")).second
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", it)
        }
            .toByteArray()
        assertEquals(ImageDimensions(40, 20), codec.dimensions(png, "image/png"))
        val fitted = assertNotNull(codec.fitted(png, "image/png", 10, 1_000_000))
        assertEquals("image/jpeg" to ImageDimensions(10, 5), fitted.mediaType to fitted.dimensions)
    }

    @Test
    fun `a plugin's parser takes the place of the default, which stays for the other port`() {
        val own = object : HtmlParser {
            override fun parse(html: String, baseUrl: String?) = HtmlDocument(null, "own", emptyList())
        }
        val engine = TestIndex("engine", listOf(instanceBinding(key<HtmlParser>(), own, "engine", "Own parser")))
        val (parser, codec) = defaults(engine)
        assertSame(own, parser)
        assertEquals("AwtImageCodec", codec.javaClass.simpleName)
    }

    private fun defaults(index: TestIndex): Pair<HtmlParser, ImageCodec> {
        var found: Pair<HtmlParser, ImageCodec>? = null
        blocking { PluginHarness.builder(index).build().run { found = get<HtmlParser>() to get<ImageCodec>() } }
        return checkNotNull(found)
    }
}
