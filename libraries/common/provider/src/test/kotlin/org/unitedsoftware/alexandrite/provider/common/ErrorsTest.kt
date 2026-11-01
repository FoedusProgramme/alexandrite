package org.unitedsoftware.alexandrite.provider.common

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_TIMEOUTS
import org.unitedsoftware.alexandrite.provider.common.chat.chatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.events
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorsTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val request = ModelRequest.builder(
        ModelRef(EndpointId("test"), "chat-model"),
        listOf(testUserEntry(turn)),
        0,
        RequestIds(turn.conversation, turn.id, 0),
    ).build()

    @AfterTest
    fun close() {
        server.close()
    }

    private fun error(status: Int, body: String, vararg headers: Pair<String, String>): ModelError {
        val response = FakeResponse.builder(status).body(body)
        headers.forEach { (name, value) -> response.header(name, value) }
        server.enqueue(response.build())
        return assertFailsWith<ModelException> {
            runBlocking { chatEndpoint(server, ChatFlavor.STANDARD).use { it.events(request) } }
        }.error
    }

    @Test
    fun `a 400 that says to use the Responses API adds that the model takes only that API`() {
        val hint = "This model takes only the Responses API (/v1/responses): configure it on an endpoint that " +
            "speaks that API."
        val pointed =
            error(400, """{"error": {"message": "This model is only supported in v1/responses.", "type": "x"}}""")
        val used = error(400, """{"error": {"message": "Use /v1/responses for this model."}}""")
        val other = error(400, """{"error": {"message": "Bad tool schema."}}""")
        val missing = error(404, """{"error": {"message": "Try /v1/responses."}}""")

        assertTrue(pointed.message.endsWith("\"type\": \"x\"}} $hint"), pointed.message)
        assertTrue(used.message.endsWith(hint), used.message)
        assertEquals(ModelErrorKind.INVALID_REQUEST, used.kind)
        assertFalse(hint in other.message, other.message)
        assertFalse(hint in missing.message, missing.message)
    }

    @Test
    fun `statuses map to their kinds, with retry advice`() {
        val cases = mapOf(
            400 to ModelErrorKind.INVALID_REQUEST,
            401 to ModelErrorKind.AUTHENTICATION,
            402 to ModelErrorKind.QUOTA_EXHAUSTED,
            403 to ModelErrorKind.PERMISSION_DENIED,
            404 to ModelErrorKind.MODEL_NOT_FOUND,
            408 to ModelErrorKind.TIMEOUT,
            413 to ModelErrorKind.CONTEXT_WINDOW_EXCEEDED,
            422 to ModelErrorKind.INVALID_REQUEST,
            500 to ModelErrorKind.SERVER_ERROR,
            503 to ModelErrorKind.OVERLOADED,
            504 to ModelErrorKind.TIMEOUT,
            529 to ModelErrorKind.OVERLOADED,
        )

        val errors = cases.keys.associateWith { error(it, "{}") }

        assertEquals(cases, errors.mapValues { it.value.kind })
        assertEquals(setOf(408, 500, 503, 504, 529), errors.filterValues { it.retryable }.keys)
        assertTrue(errors.values.none { it.outputStarted })
        val identified = error(401, "{}", "request-id" to "r-9")
        assertEquals(401 to "r-9", identified.status to identified.requestId)
    }

    @Test
    fun `what the body says makes the kind precise`() {
        val quota = error(429, """{"error":{"message":"You exceeded your quota.","type":"insufficient_quota"}}""")
        val context = error(
            400,
            """{"error":{"message":"This model's maximum context length is 32768 tokens.","type":"BadRequestError"}}""",
        )
        val missing = error(400, """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""")
        val filtered = error(400, """{"error":{"message":"Blocked.","code":"content_filter"}}""")

        assertEquals(ModelErrorKind.QUOTA_EXHAUSTED to false, quota.kind to quota.retryable)
        assertEquals("insufficient_quota", quota.rawType)
        assertEquals(ModelErrorKind.CONTEXT_WINDOW_EXCEEDED, context.kind)
        assertEquals(ModelErrorKind.MODEL_NOT_FOUND, missing.kind)
        assertEquals(ModelErrorKind.CONTENT_FILTERED, filtered.kind)
    }

    @Test
    fun `a backend that cannot be reached is a connection failure`() {
        val port = ServerSocket(0).use { it.localPort }
        val settings = EndpointSettings(baseUrl = "http://127.0.0.1:$port/v1", timeouts = FIXTURE_TIMEOUTS)

        val error = ChatEndpoint(EndpointId("test"), settings, testHttpEngine(), ChatFlavor.STANDARD).use {
            assertFailsWith<ModelException> { runBlocking { it.events(request) } }
        }.error

        assertEquals(ModelErrorKind.CONNECTION, error.kind)
        assertTrue(error.retryable && !error.outputStarted)
    }
}
