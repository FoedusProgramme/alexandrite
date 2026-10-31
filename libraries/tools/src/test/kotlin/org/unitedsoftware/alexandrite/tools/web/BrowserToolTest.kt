package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.sdk.browse.BrowseAbilities
import org.unitedsoftware.alexandrite.sdk.browse.BrowseBackend
import org.unitedsoftware.alexandrite.sdk.browse.BrowseImage
import org.unitedsoftware.alexandrite.sdk.browse.BrowseOpen
import org.unitedsoftware.alexandrite.sdk.browse.BrowseReport
import org.unitedsoftware.alexandrite.sdk.browse.BrowseSessionGone
import org.unitedsoftware.alexandrite.sdk.browse.BrowseStep
import org.unitedsoftware.alexandrite.sdk.browse.BrowseSurface
import org.unitedsoftware.alexandrite.sdk.browse.BrowseTarget
import org.unitedsoftware.alexandrite.sdk.browse.BrowseView
import org.unitedsoftware.alexandrite.sdk.browse.MouseButton
import org.unitedsoftware.alexandrite.sdk.browse.ScrollDirection
import org.unitedsoftware.alexandrite.sdk.browse.WebStep
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.tools.browse.FakeBackend
import org.unitedsoftware.alexandrite.tools.browse.PIXEL
import org.unitedsoftware.alexandrite.tools.browse.args
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrowserToolTest {
    private fun tool(backend: FakeBackend = FakeBackend()): Pair<ReviewedTool, FakeBackend> {
        val config = WebConfig(browserBackend = "fake")
        val source = BrowserTools(config, listOf(backend))
        return source.tools.value.single() as ReviewedTool to backend
    }

    // --- PleochroicBrowser, against a fake service ---

    @Test
    fun `the Pleochroic backend opens a session, sends steps by its id and closes it`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body("""{"id": "bs_1", "createdAt": "x", "expiresAt": "y"}""")
                },
                "/browser/sessions",
            )
            server.enqueue(
                fakeResponse {
                    body(
                        """{"url": "https://example.com/", "title": "Example", "screenshot": {"mediaType": "image/png", "data": "$PIXEL"}}""",
                    )
                },
                "/browser/sessions/bs_1/actions",
            )
            server.enqueue(fakeResponse { body("""{"ok": true, "minutes": 1}""") }, "/browser/sessions/bs_1")
            val config = WebConfig(pleochroicApiBase = server.baseUrl, pleochroicApiKey = Secret("plc-fixture-key"))
            val browser = PleochroicBrowser(config)

            val id = browser.open(BrowseOpen(120, "en-GB"))
            val view = browser.act(id, BrowseStep.Look(setOf(BrowseView.IMAGE)))
            browser.close(id)

            assertEquals("bs_1", id)
            assertEquals("https://example.com/" to "Example", view.url to view.title)
            assertEquals(0x89.toByte(), view.image!!.toByteArray()[0])
            val (open, act, close) = server.requests
            assertEquals("POST" to "Bearer plc-fixture-key", open.method to open.header("authorization"))
            assertEquals(
                JsonPrimitive(120) to JsonPrimitive("en-GB"),
                open.json()["idleSeconds"] to open.json()["locale"],
            )
            assertEquals("screenshot", act.json()["action"]!!.jsonPrimitive.content)
            assertEquals("DELETE", close.method)
        }
    }

    @Test
    fun `a session the service no longer has is gone, and a refused step says why without the key`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse(404) {
                    body(
                        """{"error": {"code": "not_found", "message": "this browser session has closed (idle for 120 s)"}}""",
                    )
                },
            )
            server.enqueue(
                fakeResponse(400) {
                    body("""{"error": {"code": "invalid_request", "message": "refuses to open 10.0.0.1: private"}}""")
                },
            )
            server.enqueue(fakeResponse(404) { body("""{"error": {"code": "not_found"}}""") })
            val config = WebConfig(pleochroicApiBase = server.baseUrl, pleochroicApiKey = Secret("plc-fixture-key"))
            val browser = PleochroicBrowser(config)

            val gone =
                assertFailsWith<BrowseSessionGone> {
                    browser.act("bs_old", BrowseStep.Look(setOf(BrowseView.LAYOUT)))
                }
            val refused =
                assertFailsWith<IllegalStateException> {
                    browser.act("bs_1", WebStep.Navigate("http://10.0.0.1/"))
                }
            browser.close("bs_old") // (already gone: no failure)

            assertContains(gone.message.orEmpty(), "idle")
            assertContains(refused.message.orEmpty(), "refuses to open 10.0.0.1")
            assertFalse("plc-fixture-key" in refused.message.orEmpty())
        }
    }

    @Test
    fun `the Pleochroic backend reads a layout and an image in one look, and maps typed steps`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body("""{"url": "https://example.com/", "title": "A", "snapshot": "- a"}""")
                },
            )
            server.enqueue(
                fakeResponse {
                    body(
                        """{"url": "https://example.com/", "title": "A", "screenshot": {"mediaType": "image/png", "data": "$PIXEL"}}""",
                    )
                },
            )
            server.enqueue(fakeResponse { body("""{"url": "https://example.com/", "title": "A"}""") })
            val config = WebConfig(pleochroicApiBase = server.baseUrl, pleochroicApiKey = Secret("plc-fixture-key"))
            val browser = PleochroicBrowser(config)

            val both = browser.act("bs_1", BrowseStep.Look(setOf(BrowseView.LAYOUT, BrowseView.IMAGE), fullPage = true))
            browser.act("bs_1", BrowseStep.Click(BrowseTarget.Ref("e4")))

            assertEquals("- a", both.layout)
            assertTrue(both.image!!.size > 0)
            val (snapshot, screenshot, click) = server.requests.map { it.json() }
            assertEquals("snapshot", snapshot["action"]!!.jsonPrimitive.content)
            assertEquals("true", screenshot["fullPage"].toString())
            assertEquals("""{"action":"click","ref":"e4"}""", click.toString())
        }
    }

    @Test
    fun `the Pleochroic backend refuses a point, a hover and a right click without asking the service`() = runBlocking {
        FakeModelServer().use { server ->
            val config = WebConfig(pleochroicApiBase = server.baseUrl, pleochroicApiKey = Secret("plc-fixture-key"))
            val browser = PleochroicBrowser(config)

            assertFailsWith<IllegalArgumentException> {
                browser.act("bs_1", BrowseStep.Click(BrowseTarget.Point(10, 20)))
            }
            assertFailsWith<IllegalArgumentException> {
                browser.act("bs_1", BrowseStep.Hover(BrowseTarget.Ref("e1")))
            }
            assertFailsWith<IllegalArgumentException> {
                browser.act("bs_1", BrowseStep.Click(BrowseTarget.Ref("e1"), count = 2))
            }

            assertFailsWith<IllegalArgumentException> {
                browser.check(BrowseStep.Scroll(ScrollDirection.RIGHT))
            }
            assertFailsWith<IllegalArgumentException> { browser.check(BrowseStep.Wait()) }
            assertFailsWith<IllegalArgumentException> { browser.check(BrowseStep.Click(BrowseTarget.Ref("submit"))) }
            assertFailsWith<IllegalArgumentException> { browser.check(WebStep.Navigate("file:///etc/hosts")) }
            browser.check(BrowseStep.Click(BrowseTarget.Ref("e1")))

            assertTrue(server.requests.isEmpty())
            assertFalse(BrowseTarget.Kind.POINT in browser.abilities.targets)
        }
    }

    // --- browser.use, over a fake backend ---

    @Test
    fun `no browser backend configured, no tool offered`() {
        val config = WebConfig()
        assertTrue(BrowserTools(config, listOf(FakeBackend())).tools.value.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            BrowserTools(WebConfig(browserBackend = "other"), listOf(FakeBackend()))
        }
        assertFailsWith<IllegalArgumentException> {
            val desktop = FakeBackend(surface = BrowseSurface.DESKTOP)
            BrowserTools(WebConfig(browserBackend = "fake"), listOf(desktop))
        }
    }

    @Test
    fun `an ended session is reopened for a navigation, and other steps are told to navigate first`() = runBlocking {
        val (tool, backend) = tool()
        val context = testToolContext()
        tool.execute(args("action" to "navigate", "url" to "https://example.com/"), context)

        backend.goneOnce = true
        val click = tool.execute(args("action" to "click", "ref" to "e1"), context)
        backend.goneOnce = true
        val navigate = tool.execute(args("action" to "navigate", "url" to "https://example.com/b"), context)

        assertTrue(click.isError)
        assertContains((click.content.single() as TextPart).text, "navigate again")
        assertFalse(navigate.isError, "$navigate")
        assertEquals(listOf("s1", "s2", "s3"), backend.opened)
    }

    @Test
    fun `reading steps are a network read, acting steps an external action`() = runBlocking {
        val (tool, _) = tool()
        val context = testToolContext()
        val navigate = tool.review(args("action" to "navigate", "url" to "https://Example.com/x"), context)
        val click = tool.review(args("action" to "click", "ref" to "e1"), context)
        val evaluate = tool.review(args("action" to "evaluate", "expression" to "1"), context)

        assertEquals(ToolRisk.NETWORK_READ to "example.com", navigate.risk to navigate.host)
        assertEquals(ToolRisk.EXTERNAL_ACTION, click.risk)
        assertEquals(ToolRisk.EXTERNAL_ACTION, evaluate.risk)
        assertTrue(navigate.untrusted)
    }

    @Test
    fun `Pleochroic's limits shape the schema, and a step it does not know is refused`() = runBlocking {
        val config = WebConfig(browserBackend = PLEOCHROIC_WEB_ID)
        val browser = PleochroicBrowser(config)
        val tool = BrowserTools(config, listOf(browser)).tools.value.single() as ReviewedTool
        val properties = tool.definition.parameters["properties"]!!.jsonObject

        assertFalse("button" in properties || "count" in properties || "x" in properties)
        assertEquals("""["up","down"]""", properties["direction"]!!.jsonObject["enum"].toString())
        assertContains(tool.definition.description, "billed while open")
        val unknown = object : BrowseStep {
            override val kind = BrowseStep.Kind.CLICK
        }
        assertFailsWith<IllegalArgumentException> { browser.check(unknown) }
        assertEquals(MouseButton.LEFT, browser.abilities.buttons.single())
    }
}
