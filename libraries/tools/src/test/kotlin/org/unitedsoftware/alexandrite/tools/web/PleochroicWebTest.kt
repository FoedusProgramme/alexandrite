package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PleochroicWebTest {
    @Test
    fun `search sends a bearer-authenticated POST and parses results`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"results": [{"title": "One", "url": "https://example.com/a", "snippet": "a snippet"}],
                            "backend": "brave"}""",
                    )
                },
                "/search",
            )
            val config = WebConfig(pleochroicApiBase = server.baseUrl, pleochroicApiKey = Secret("plc-fixture-key"))
            val search = PleochroicSearch(config, SearchHttp(config))

            val results = search.search(WebQuery("question", 5))

            assertEquals("One" to "https://example.com/a", results.single().title to results.single().url)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("Bearer plc-fixture-key", request.header("authorization"))
            assertEquals(
                JsonPrimitive("question") to JsonPrimitive(5),
                request.json()["query"] to request.json()["count"],
            )
        }
    }

    @Test
    fun `fetch sends the url and format and returns the content`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"url": "https://example.com/page", "status": 200, "contentType": "text/markdown",
                            "title": "Page", "content": "# Page\n\nBody.", "truncated": false}""",
                    )
                },
                "/fetch",
            )
            val config = WebConfig(pleochroicApiBase = server.baseUrl, pleochroicApiKey = Secret("plc-fixture-key"))
            val fetch = PleochroicFetch(config, SearchHttp(config))

            val page = fetch.fetch("https://example.com/page")

            assertEquals("https://example.com/page" to "# Page\n\nBody.", page.url to page.text)
            assertEquals(JsonPrimitive("markdown"), server.requests.single().json()["format"])
        }
    }

    @Test
    fun `not_entitled, quota_exceeded and insufficient_credits answer with clear, distinct messages`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(403) { body("""{"error": {"code": "not_entitled"}}""") })
            server.enqueue(fakeResponse(429) { body("""{"error": {"code": "quota_exceeded"}}""") })
            server.enqueue(fakeResponse(402) { body("""{"error": {"code": "insufficient_credits"}}""") })
            val config = WebConfig(pleochroicApiBase = server.baseUrl, pleochroicApiKey = Secret("plc-fixture-key"))
            val search = PleochroicSearch(config, SearchHttp(config))

            val messages = (1..3).map {
                assertFailsWith<IllegalStateException> { search.search(WebQuery("q", 1)) }.message.orEmpty()
            }

            assertTrue(messages[0].contains("plan"))
            assertTrue(messages[1].contains("quota"))
            assertTrue(messages[2].contains("credits"))
            assertTrue(messages.none { "plc-fixture-key" in it })
        }
    }

    @Test
    fun `an unconfigured backend refuses clearly instead of calling out`() = runBlocking {
        val error =
            assertFailsWith<IllegalArgumentException> {
                PleochroicSearch(WebConfig(), SearchHttp(WebConfig())).search(WebQuery("q", 1))
            }
        assertTrue(error.message.orEmpty().contains("pleochroicApiBase"))
    }
}
