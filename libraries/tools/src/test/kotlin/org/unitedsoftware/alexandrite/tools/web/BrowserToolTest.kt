package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.web.BrowserOpen
import org.unitedsoftware.alexandrite.sdk.web.BrowserSessionGone
import org.unitedsoftware.alexandrite.sdk.web.BrowserStep
import org.unitedsoftware.alexandrite.sdk.web.BrowserView
import org.unitedsoftware.alexandrite.sdk.web.WebBrowserBackend
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testToolContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A 1×1 PNG, as a screenshot comes back base64-encoded. */
private const val PIXEL =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

class BrowserToolTest {
    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
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

            val id = browser.open(BrowserOpen(120, "en-GB"))
            val view = browser.act(id, BrowserStep("screenshot", JsonObject(emptyMap())))
            browser.close(id)

            assertEquals("bs_1", id)
            assertEquals("https://example.com/" to "Example", view.url to view.title)
            assertEquals(0x89.toByte(), view.screenshot!![0])
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
                assertFailsWith<BrowserSessionGone> {
                    browser.act("bs_old", BrowserStep("snapshot", JsonObject(emptyMap())))
                }
            val refused =
                assertFailsWith<IllegalStateException> {
                    browser.act("bs_1", BrowserStep("navigate", args("url" to "http://10.0.0.1/")))
                }
            browser.close("bs_old") // (already gone: no failure)

            assertContains(gone.message.orEmpty(), "idle")
            assertContains(refused.message.orEmpty(), "refuses to open 10.0.0.1")
            assertFalse("plc-fixture-key" in refused.message.orEmpty())
        }
    }

    // --- browser.use, over a fake backend ---

    private class FakeBackend : WebBrowserBackend {
        override val id = "fake"
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val steps = mutableListOf<Pair<String, BrowserStep>>()
        var goneOnce = false

        override suspend fun open(options: BrowserOpen): String = "s${opened.size + 1}".also { opened += it }

        override suspend fun act(session: String, step: BrowserStep): BrowserView {
            if (goneOnce) {
                goneOnce = false
                throw BrowserSessionGone("closed for idling")
            }
            steps += session to step
            return when (step.action) {
                "screenshot" -> BrowserView(
                    "https://example.com/",
                    "Example",
                    screenshot = java.util.Base64.getDecoder().decode(PIXEL),
                )

                "snapshot" -> BrowserView("https://example.com/", "Example", snapshot = "- link \"More\" [ref=e1]")

                else -> BrowserView(
                    (step.arguments["url"] as? JsonPrimitive)?.content ?: "https://example.com/",
                    "Example",
                )
            }
        }

        override suspend fun close(session: String) {
            closed += session
        }
    }

    private fun tool(backend: FakeBackend = FakeBackend()): Pair<ReviewedTool, FakeBackend> {
        val config = WebConfig(browserBackend = "fake")
        val source = BrowserTools(config, listOf(backend), BrowserSessions(config))
        return source.tools.value.single() as ReviewedTool to backend
    }

    @Test
    fun `no browser backend configured, no tool offered`() {
        val config = WebConfig()
        assertTrue(BrowserTools(config, listOf(FakeBackend()), BrowserSessions(config)).tools.value.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            BrowserTools(WebConfig(browserBackend = "other"), listOf(FakeBackend()), BrowserSessions(config))
        }
    }

    @Test
    fun `one session per conversation, kept across calls and closed on close`() = runBlocking {
        val (tool, backend) = tool()
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
        assertContains((again.content.single() as TextPart).text, "No browser session")
        assertTrue(context.untrustedReasons.isNotEmpty())
    }

    @Test
    fun `a screenshot comes back as an image`() = runBlocking {
        val (tool, _) = tool()
        val result = tool.execute(args("action" to "screenshot"), testToolContext())
        val image = assertIs<MediaPart>(result.content.last())
        assertEquals("image/png" to 1, image.mediaType to image.width)
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
    fun `an unknown action or argument is refused before the backend is asked`() = runBlocking {
        val (tool, backend) = tool()
        val unknown = tool.execute(args("action" to "fly"), testToolContext())
        val extra = tool.execute(args("action" to "snapshot", "cookie" to "x"), testToolContext())
        assertTrue(unknown.isError && extra.isError)
        assertTrue(backend.opened.isEmpty())
    }
}
