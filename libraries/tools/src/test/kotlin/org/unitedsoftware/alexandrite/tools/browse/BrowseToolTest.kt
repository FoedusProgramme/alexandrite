package org.unitedsoftware.alexandrite.tools.browse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.sdk.browse.BrowseAbilities
import org.unitedsoftware.alexandrite.sdk.browse.BrowseBackend
import org.unitedsoftware.alexandrite.sdk.browse.BrowseOpen
import org.unitedsoftware.alexandrite.sdk.browse.BrowseStep
import org.unitedsoftware.alexandrite.sdk.browse.BrowseTarget
import org.unitedsoftware.alexandrite.sdk.browse.BrowseView
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrowseToolTest {
    @Test
    fun `one session per conversation, kept across calls and closed on close`() = runBlocking {
        val backend = FakeBackend()
        val tool = browseTool(backend)
        val context = testToolContext()

        val first = tool.execute(args("action" to "navigate", "url" to "https://example.com/a"), context)
        val second = tool.execute(args("action" to "snapshot"), context)
        val closed = tool.execute(args("action" to "close"), context)
        val again = tool.execute(args("action" to "close"), context)

        assertFalse(first.isError, "$first")
        assertEquals(listOf("s1"), backend.opened)
        assertEquals(listOf("s1", "s1"), backend.steps.map { it.first })
        assertContains((second.content.first() as TextPart).text, "[ref=e1]")
        assertEquals(listOf("s1"), backend.closed)
        assertContains((closed.content.single() as TextPart).text, "closed")
        assertContains((again.content.single() as TextPart).text, "No screen session")
        assertTrue(context.untrustedReasons.isNotEmpty())
    }

    @Test
    fun `a screenshot comes back as an image`() = runBlocking {
        val tool = browseTool(FakeBackend())
        val result = tool.execute(args("action" to "screenshot"), testToolContext())
        val image = assertIs<MediaPart>(result.content.last())
        assertEquals("image/png" to 1, image.mediaType to image.width)
    }

    @Test
    fun `an unknown action or argument is refused before the backend is asked`() = runBlocking {
        val backend = FakeBackend()
        val tool = browseTool(backend)
        val unknown = tool.execute(args("action" to "fly"), testToolContext())
        val extra = tool.execute(args("action" to "snapshot", "cookie" to "x"), testToolContext())
        assertTrue(unknown.isError && extra.isError)
        assertTrue(backend.opened.isEmpty())
    }

    @Test
    fun `the tool offers only the steps and targets its backend has`() {
        val web = browseTool(FakeBackend())
        val desktopLike = browseTool(
            FakeBackend(
                BrowseAbilities(
                    setOf(BrowseStep.Kind.LOOK, BrowseStep.Kind.CLICK, BrowseStep.Kind.DRAG),
                    setOf(BrowseView.IMAGE),
                    setOf(BrowseTarget.Kind.POINT),
                ),
            ),
        )

        val webFields = web.definition.parameters["properties"]!!.jsonObject.keys
        val pointFields = desktopLike.definition.parameters["properties"]!!.jsonObject.keys
        val pointActions = desktopLike.definition.parameters["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]

        assertTrue("ref" in webFields && "x" !in webFields && "toX" !in webFields)
        assertEquals(setOf("action", "x", "y", "toX", "toY", "button", "count", "fullPage"), pointFields)
        assertEquals("""["screenshot","click","drag","close"]""", pointActions.toString())
        assertFalse("snapshot" in desktopLike.definition.description)
    }

    @Test
    fun `a point is counted in the screenshot as the model saw it`() = runBlocking {
        val wide = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(3136, 20, BufferedImage.TYPE_INT_RGB), "png", it)
        }
        val backend = FakeBackend(
            BrowseAbilities(
                setOf(BrowseStep.Kind.LOOK, BrowseStep.Kind.CLICK),
                setOf(BrowseView.IMAGE),
                setOf(BrowseTarget.Kind.POINT),
            ),
            screenshot = wide.toByteArray(),
        )
        val tool = browseTool(backend)
        val context = testToolContext()

        val before = tool.execute(args("action" to "click").plusInts("x" to 10, "y" to 5), context)
        val shot = tool.execute(args("action" to "screenshot"), context)
        val after = tool.execute(args("action" to "click").plusInts("x" to 100, "y" to 5), context)

        assertFalse(before.isError || after.isError, "$before $after")
        assertEquals(1568, assertIs<MediaPart>(shot.content.last()).width)
        val clicks = backend.steps.map { it.second }.filterIsInstance<BrowseStep.Click>().map { it.target }
        assertEquals(listOf(BrowseTarget.Point(10, 5), BrowseTarget.Point(200, 10)), clicks)
    }

    @Test
    fun `a target the backend lacks or an argument of the wrong type opens no session`() = runBlocking {
        val backend = FakeBackend()
        val tool = browseTool(backend)
        val point = tool.execute(args("action" to "click").plusInts("x" to 1, "y" to 2), testToolContext())
        val wrong = tool.execute(args("action" to "scroll").plusInts("direction" to 3), testToolContext())
        val both = tool.execute(args("action" to "click", "ref" to "e1", "selector" to "a"), testToolContext())

        assertTrue(point.isError && wrong.isError && both.isError)
        assertContains((wrong.content.single() as TextPart).text, "direction is a string")
        assertTrue(backend.opened.isEmpty())
    }

    @Test
    fun `a step the backend refuses whatever the session opens none`() = runBlocking {
        val backend = object : BrowseBackend by FakeBackend() {
            var opened = 0

            override fun check(step: BrowseStep) {
                require(step !is BrowseStep.Click || step.count == 1) { "single clicks only" }
            }

            override suspend fun open(options: BrowseOpen): String = "s".also { opened++ }
        }
        val tool = browseTool(backend)

        val result = tool.execute(args("action" to "click", "ref" to "e1").plusInts("count" to 2), testToolContext())

        assertTrue(result.isError)
        assertContains((result.content.single() as TextPart).text, "single clicks only")
        assertEquals(0, backend.opened)
    }
}
