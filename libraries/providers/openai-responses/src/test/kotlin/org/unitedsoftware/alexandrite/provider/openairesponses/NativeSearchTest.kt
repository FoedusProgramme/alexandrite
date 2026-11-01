package org.unitedsoftware.alexandrite.provider.openairesponses

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import org.unitedsoftware.alexandrite.provider.common.read
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesEndpoint
import org.unitedsoftware.alexandrite.provider.common.responses.ResponsesFlavor
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSearchTest {
    private val ref = ModelRef(EndpointId("test"), "selected-model")

    private fun endpoint(server: FakeModelServer, enabled: Boolean = false): ResponsesEndpoint {
        val config =
            EndpointSerializer.read(
                """{"baseUrl": "${server.baseUrl}/v1", "discover": false, "nativeSearch": $enabled}""",
            )
        return ResponsesEndpoint(
            ref.endpoint,
            config.settings,
            JdkHttpEngine(),
            ResponsesFlavor.STANDARD,
            config.options,
        )
    }

    @Test
    fun `native search is disabled unless explicitly enabled and sends no request`() = runBlocking {
        FakeModelServer().use { server ->
            endpoint(server).use { endpoint ->
                assertFailsWith<UnsupportedOperationException> { endpoint.webSearch(ref.model, WebQuery("query", 2)) }
            }
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `enabled native search sends the web search tool and reads citations before sources`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"status":"completed","output":[
                {"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search",
                    "sources":[{"type":"url","url":"https://example.com/2"},
                        {"type":"url","url":"https://example.com/1"}]}},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"See the source excerpt.",
                    "annotations":[{"type":"url_citation","url":"https://example.com/1","title":"First",
                        "start_index":4,"end_index":22}]}]}]}""",
                    )
                },
            )
            endpoint(server, true).use { endpoint ->
                val results = endpoint.webSearch(ref.model, WebQuery("a query", 2))
                assertEquals(listOf("First", "https://example.com/2"), results.map { it.title })
                assertEquals("the source excerpt", results.first().snippet)
            }
            val request = server.requests.single()
            assertEquals("/v1/responses", request.path)
            val json = request.json()
            assertEquals("selected-model", json["model"]!!.jsonPrimitive.content)
            assertEquals("a query", json["input"]!!.jsonPrimitive.content)
            assertEquals("false", json["stream"]!!.jsonPrimitive.content)
            val tool = (json["tools"] as JsonArray).single().jsonObject
            assertEquals("web_search", tool["type"]!!.jsonPrimitive.content)
            server.assertFinished()
        }
    }

    @Test
    fun `a failed search or none at all fails without showing the backend's payload`() = runBlocking {
        val bodies = listOf(
            """{"output":[{"type":"web_search_call","id":"ws_1","status":"failed","secret":"payload"}]}""" to
                ModelErrorKind.SERVER_ERROR,
            """{"output":[{"type":"message","content":[{"type":"output_text","text":"secret payload"}]}]}""" to
                ModelErrorKind.PROTOCOL,
            "{secret-not-json" to ModelErrorKind.PROTOCOL,
        )
        for ((body, kind) in bodies) {
            FakeModelServer().use { server ->
                server.enqueue(fakeResponse { body(body) })
                endpoint(server, true).use {
                    val error = assertFailsWith<ModelException> { it.webSearch(ref.model, WebQuery("query", 2)) }
                    assertEquals(kind, error.error.kind)
                    assertFalse("secret" in error.toString())
                }
                server.assertFinished()
            }
        }
    }
}
