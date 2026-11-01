package org.unitedsoftware.alexandrite.provider.anthropiccompatible

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesFlavor
import org.unitedsoftware.alexandrite.provider.common.read
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSearchTest {
    private val ref = ModelRef(EndpointId("test"), "selected-model")

    private fun endpoint(server: FakeModelServer, enabled: Boolean = false): MessagesEndpoint {
        val config = EndpointSerializer.read(
            """
            {"baseUrl": "${server.baseUrl}", "models": {"${ref.model}": {}}, "discover": false, "nativeSearch": $enabled}
            """.trimIndent(),
        )
        return MessagesEndpoint(
            ref.endpoint,
            config.settings,
            testHttpEngine(),
            MessagesFlavor.STANDARD,
            config.options,
        )
    }

    @Test
    fun `native search is disabled unless explicitly enabled and sends no request`() = runBlocking {
        FakeModelServer().use { server ->
            endpoint(server).use { endpoint ->
                assertFalse(endpoint.models().single().webSearch)
                assertFailsWith<UnsupportedOperationException> { endpoint.webSearch(ref.model, WebQuery("query", 2)) }
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `enabled native search sends selected model and extracts result citations`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"stop_reason":"end_turn","content":[
                {"type":"web_search_tool_result","content":[
                    {"type":"web_search_result","title":"First","url":"https://example.com/1","encrypted_content":"secret"},
                    {"type":"web_search_result","title":"Second","url":"https://example.com/2"}]},
                {"type":"text","text":"Answer","citations":[
                    {"type":"web_search_result_location","url":"https://example.com/1","cited_text":"source excerpt"}]}]}""",
                    )
                },
            )
            endpoint(server, true).use { endpoint ->
                assertTrue(endpoint.models().single().webSearch)
                val results = endpoint.webSearch(ref.model, WebQuery("a query", 1))
                assertEquals(1, results.size)
                assertEquals("First", results.single().title)
                assertEquals("source excerpt", results.single().snippet)
            }
            val request = server.requests.single()
            assertEquals("/v1/messages", request.path)
            val json = request.json()
            assertEquals("selected-model", json["model"]!!.jsonPrimitive.content)
            assertEquals("false", json["stream"]!!.jsonPrimitive.content)
            val tool = (json["tools"] as JsonArray).single().jsonObject
            assertEquals("web_search_20250305", tool["type"]!!.jsonPrimitive.content)
            assertEquals("web_search", tool["name"]!!.jsonPrimitive.content)
            assertEquals(
                "a query",
                (json["messages"] as JsonArray).single().jsonObject["content"]!!.jsonPrimitive.content,
            )
            server.assertFinished()
        }
    }

    @Test
    fun `HTTP success carrying search error fails without exposing provider payload`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"content":[{"type":"web_search_tool_result","content":{
                "type":"web_search_tool_result_error","error_code":"unavailable","detail":"secret"}}]}""",
                    )
                },
            )
            endpoint(server, true).use { endpoint ->
                val error = assertFailsWith<ModelException> { endpoint.webSearch(ref.model, WebQuery("query", 2)) }
                assertFalse("secret" in error.toString())
            }
            server.assertFinished()
        }
    }

    @Test
    fun `pause turn continuation preserves encrypted server content`() = runBlocking {
        FakeModelServer().use { server ->
            val content = """[{"type":"web_search_tool_result","content":[
                {"type":"web_search_result","title":"First","url":"https://example.com/1","encrypted_content":"opaque"}]}]"""
            server.enqueue(fakeResponse { body("""{"stop_reason":"pause_turn","content":$content}""") })
            server.enqueue(
                fakeResponse {
                    body("""{"stop_reason":"end_turn","content":[{"type":"text","text":"Done"}]}""")
                },
            )
            endpoint(server, true).use { assertEquals(1, it.webSearch(ref.model, WebQuery("query", 2)).size) }
            val history = server.requests.last().json()["messages"] as JsonArray
            assertTrue("opaque" in history.last().toString())
            assertEquals(2, server.requests.size)
            server.assertFinished()
        }
    }

    @Test
    fun `empty search results are successful`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body("""{"stop_reason":"end_turn","content":[{"type":"web_search_tool_result","content":[]}]}""")
                },
            )
            endpoint(server, true).use { assertTrue(it.webSearch(ref.model, WebQuery("query", 2)).isEmpty()) }
            server.assertFinished()
        }
    }

    @Test
    fun `paused search has a bounded continuation budget`() = runBlocking {
        FakeModelServer().use { server ->
            repeat(3) {
                server.enqueue(fakeResponse { body("""{"stop_reason":"pause_turn","content":[]}""") })
            }
            endpoint(server, true).use {
                assertFailsWith<ModelException> { it.webSearch(ref.model, WebQuery("query", 2)) }
            }
            assertEquals(3, server.requests.size)
            server.assertFinished()
        }
    }

    @Test
    fun `HTTP failure and malformed responses do not expose provider content`() = runBlocking {
        val responses = listOf(
            fakeResponse(400) {
                body("""{"type":"error","error":{"type":"invalid_request_error","message":"disabled"}}""")
            },
            fakeResponse { body("{secret-not-json") },
        )
        for (response in responses) {
            FakeModelServer().use { server ->
                server.enqueue(response)
                endpoint(server, true).use {
                    val error = assertFailsWith<ModelException> { it.webSearch(ref.model, WebQuery("query", 2)) }
                    assertFalse("secret-not-json" in error.toString())
                }
                server.assertFinished()
            }
        }
    }
}
