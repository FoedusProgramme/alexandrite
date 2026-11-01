package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.OpaquePart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.assertStreamContract
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class ResponsesResponseTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val model = ModelRef(EndpointId("test"), "gpt-model")
    private val models = mapOf(
        "gpt-model" to
            ModelConfig(nativeTools = true, parallelToolCalls = true, reasoningEfforts = setOf(ReasoningEffort.LOW)),
    )
    private val tools = listOf(testToolDefinition("notes.add"), testToolDefinition("files.read"))

    @AfterTest
    fun close() {
        server.close()
    }

    private fun request(block: ModelRequest.Builder.() -> Unit = {}): ModelRequest = ModelRequest.builder(
        model,
        listOf(testUserEntry(turn, "Hi")),
        0,
        RequestIds(turn.conversation, turn.id, 2),
    ).apply(block).build()

    private fun respond(response: FakeResponse, request: ModelRequest = request()): List<ModelEvent> = runBlocking {
        server.enqueue(response, "/v1/responses")
        val events = responsesEndpoint(server, models = models, discover = false).use { it.events(request) }
        assertStreamContract(request, events)
        events
    }

    private fun fail(response: FakeResponse): ModelError = assertFailsWith<ModelException> { respond(response) }.error

    private fun completed(events: List<ModelEvent>): ModelEvent.Completed = events.last() as ModelEvent.Completed

    private fun error(status: Int, type: String, code: String?, message: String): FakeResponse =
        FakeResponse.builder(status).header("x-request-id", "req_9").body(responsesError(type, code, message)).build()

    // Text and the response's frame.

    @Test
    fun `a text reply streams its deltas, names the response and reports its usage`() {
        val events = respond(sse(*textEvents(listOf("Hel", "lo."), completed(responsesUsage(30, 20, 7, 3)))))

        val started = events.first() as ModelEvent.ResponseStarted
        assertEquals("resp_1" to "gpt-model", started.responseId to started.model)
        assertEquals(listOf("Hel", "lo."), events.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        val done = completed(events)
        assertEquals(listOf(TextPart("Hello.")), done.message.parts)
        assertEquals(FinishKind.END_TURN to "completed", done.finish.kind to done.finish.raw)
        val usage = done.usage
        assertEquals(
            listOf(30L, 20L, 7L, 3L, 30L),
            listOf(
                usage.inputTokens,
                usage.cacheReadTokens,
                usage.outputTokens,
                usage.reasoningTokens,
                usage.contextTokens,
            ),
        )
        assertEquals(1, events.filterIsInstance<ModelEvent.UsageUpdated>().size)
    }

    @Test
    fun `a stream is read to its end after the response completes, so a proxy sees it taken whole`() {
        val events = textEvents(listOf("Hi."), completed(responsesUsage(3, 0, 1, 0)))
        val trailing = fakeResponse {
            events.forEach { event(it.toString(), it["type"]!!.jsonPrimitive.content) }
            pause(400.milliseconds)
            comment("keep-alive")
        }

        val started = TimeSource.Monotonic.markNow()
        val streamed = respond(trailing)

        assertIs<ModelEvent.Completed>(streamed.last())
        assertTrue(started.elapsedNow() >= 350.milliseconds, "${started.elapsedNow()}")
    }

    @Test
    fun `events it does not know or need are ignored`() {
        val ignored = listOf(
            responseEvent("response.in_progress", mapOf("response" to responseObject())),
            responseEvent("response.content_part.added", mapOf("output_index" to 0, "content_index" to 0)),
            responseEvent("response.output_text.done", mapOf("output_index" to 0, "text" to "Hi.")),
            responseEvent("response.output_text.annotation.added", mapOf("output_index" to 0)),
            responseEvent("response.web_search_call.searching", mapOf("output_index" to 0)),
            responseEvent("response.something_new", mapOf("output_index" to 7)),
        )

        val events = respond(
            sse(
                created(),
                itemAdded(0, messageItem("msg_0")),
                textDelta(0, "Hi."),
                *ignored.toTypedArray(),
                itemDone(0, messageItem("msg_0", "Hi.")),
                completed(),
            ),
        )

        assertEquals(listOf(TextPart("Hi.")), completed(events).message.parts)
    }

    @Test
    fun `items that come whole and deltas without an added item still make their parts`() {
        val events = respond(
            sse(
                created(),
                textDelta(0, "Lazy"),
                itemDone(0, messageItem("msg_0", "Lazy")),
                itemDone(1, messageItem("msg_1", "Whole")),
                itemDone(2, functionCallItem("fc_2", "call_c", "notes-add", "{\"a\":1}")),
                completed(),
            ),
            request { tools(tools) },
        )

        val parts = completed(events).message.parts
        assertEquals(
            listOf(TextPart("Lazy"), TextPart("Whole"), ToolCallPart(ToolCallId("call_c"), "notes.add", "{\"a\":1}")),
            parts,
        )
        assertEquals(FinishKind.TOOL_USE, completed(events).finish.kind)
    }

    @Test
    fun `a response sent as one JSON object makes the same events`() {
        val whole = responseObject(
            "completed",
            listOf(messageItem("msg_0", "Hi."), functionCallItem("fc_1", "call_a", "files-read", "{}")),
            responsesUsage(5, 0, 2, 0),
        )

        val events = respond(fakeResponse { body(whole.toString()) }, request { tools(tools) })

        val done = completed(events)
        assertEquals(
            listOf(TextPart("Hi."), ToolCallPart(ToolCallId("call_a"), "files.read", "{}")),
            done.message.parts,
        )
        assertEquals(5L, done.usage.inputTokens)
    }

    @Test
    fun `an item of a type it does not know is kept opaque in its dialect`() {
        val item = buildJsonObject {
            put("id", "ws_0")
            put("type", "web_search_call")
            put("status", "completed")
        }

        val events = respond(sse(created(), itemAdded(0, item), itemDone(0, item), completed()))

        assertEquals(
            listOf(OpaquePart(ResponsesFlavor.OPENAI_RESPONSES, "web_search_call", item)),
            completed(events).message.parts,
        )
    }

    // Reasoning.

    @Test
    fun `reasoning summaries stream apart from the text and the encrypted reasoning is sealed`() {
        val events = respond(
            sse(
                created(),
                itemAdded(0, reasoningItem("rs_0")),
                responseEvent(
                    "response.reasoning_summary_part.added",
                    mapOf("output_index" to 0, "summary_index" to 0),
                ),
                summaryDelta(0, 0, "First "),
                summaryDelta(0, 0, "part."),
                summaryDelta(0, 1, "Second."),
                itemDone(0, reasoningItem("rs_0", "enc-0", listOf("First part.", "Second."))),
                itemAdded(1, messageItem("msg_1")),
                textDelta(1, "Answer."),
                itemDone(1, messageItem("msg_1", "Answer.")),
                completed(),
            ),
        )

        val summaries = events.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.summary }
        assertEquals(listOf("First ", "part.", "\n\nSecond."), summaries)
        val reasoning = completed(events).message.parts.first() as ReasoningPart
        assertEquals(null to "First part.\n\nSecond.", reasoning.text to reasoning.summary)
        val seal = reasoning.seal!!
        assertEquals(
            listOf(model.toString(), "openai-responses", "encrypted", "enc-0"),
            listOf("${seal.origin}", "${seal.dialect}", "${seal.kind}", seal.data),
        )
        assertEquals(seal, events.filterIsInstance<ModelEvent.ReasoningSealed>().single().seal)
    }

    @Test
    fun `a sealed reasoning item goes back byte for byte on the next request`() {
        val encrypted = "gAAAAB+/=\u00e9\u2028\"quoted\"\\slash\n"
        val original = reasoningItem("rs_0", encrypted, listOf("Plan."))
        val first = respond(
            sse(
                created(),
                itemAdded(0, reasoningItem("rs_0", "partial")),
                itemDone(0, original),
                itemDone(1, functionCallItem("fc_1", "call_a", "notes-add", "{}")),
                completed(),
            ),
            request { tools(tools) },
        )
        val assistant = completed(first).message
        assertEquals(SealKind.ENCRYPTED, (assistant.parts.first() as ReasoningPart).seal!!.kind)
        val history = request().history + assistant +
            ToolResultEntry(null, ToolCallId("call_a"), "notes.add", listOf(TextPart("Done.")), ToolOutcome.Succeeded)

        respond(sse(*textEvents(listOf("Ok."))), request { tools(tools).history(history) })

        val sent = server.requests.last().inputItems().first { it["type"] == JsonPrimitive("reasoning") }
        assertEquals(JsonPrimitive(encrypted), sent["encrypted_content"])
        assertEquals(original["summary"], sent["summary"])
        assertEquals(setOf("type", "summary", "encrypted_content"), sent.keys)
    }

    @Test
    fun `reasoning text streams as text and an incomplete reason is its finish`() {
        val events = respond(
            sse(
                created(),
                itemAdded(0, reasoningItem("rs_0")),
                reasoningTextDelta(0, "Thinking"),
                itemDone(0, reasoningItem("rs_0", content = listOf("Thinking"))),
                incomplete("max_output_tokens"),
            ),
        )

        val done = completed(events)
        assertEquals(listOf(ReasoningPart("Thinking", null, null)), done.message.parts)
        assertEquals(FinishKind.MAX_OUTPUT_TOKENS to "max_output_tokens", done.finish.kind to done.finish.raw)
    }

    // Tool calls.

    @Test
    fun `parallel calls stream their arguments by output index, interleaved`() {
        val events = respond(
            sse(
                created(),
                itemAdded(0, functionCallItem("fc_0", "call_a", "notes-add")),
                itemAdded(1, functionCallItem("fc_1", "call_b", "files-read")),
                argumentsDelta(1, "{\"path\":"),
                argumentsDelta(0, "{\"text\":"),
                argumentsDelta(1, "\"a.txt\"}"),
                argumentsDelta(0, "\"milk\"}"),
                itemDone(1, functionCallItem("fc_1", "call_b", "files-read", "{\"path\":\"a.txt\"}")),
                itemDone(0, functionCallItem("fc_0", "call_a", "notes-add", "{\"text\":\"milk\"}")),
                completed(),
            ),
            request { tools(tools) },
        )

        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>()
        assertEquals(listOf(0 to "call_a", 1 to "call_b"), started.map { it.index to it.id.value })
        val calls = completed(events).message.parts.filterIsInstance<ToolCallPart>()
        assertEquals(
            listOf("call_a notes.add {\"text\":\"milk\"}", "call_b files.read {\"path\":\"a.txt\"}"),
            calls.map { "${it.id} ${it.name} ${it.arguments}" },
        )
        assertEquals(FinishKind.TOOL_USE, completed(events).finish.kind)
    }

    @Test
    fun `a call the backend gives no id gets the request's id of its part`() {
        val item = buildJsonObject {
            put("type", "function_call")
            put("name", "notes-add")
            put("arguments", "{}")
        }

        val events =
            respond(sse(created(), itemAdded(0, item), itemDone(0, item), completed()), request { tools(tools) })

        val call = completed(events).message.parts.single() as ToolCallPart
        assertEquals(request().ids.callId(0), call.id)
    }

    // Refusals and failures.

    @Test
    fun `a refusal ends the response as one, its text kept as the detail`() {
        val refused = buildJsonObject {
            put("id", "msg_0")
            put("type", "message")
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "refusal")
                        put("refusal", "I cannot help.")
                    },
                )
            }
        }
        val delta = responseEvent(
            "response.refusal.delta",
            mapOf("output_index" to 0, "content_index" to 0, "delta" to "I cannot help."),
        )

        val done =
            completed(
                respond(sse(created(), itemAdded(0, messageItem("msg_0")), delta, itemDone(0, refused), completed())),
            )

        assertEquals(FinishKind.REFUSAL, done.finish.kind)
        assertEquals("I cannot help.", done.finish.detail)
        assertTrue(done.message.blank)
    }

    @Test
    fun `an error event fails the stream by its code`() {
        val event = responseEvent(
            "error",
            mapOf("code" to "rate_limit_exceeded", "message" to "Slow down.", "param" to null, "sequence_number" to 1),
        )

        val error = fail(sse(created(), event))

        assertEquals(ModelErrorKind.RATE_LIMITED, error.kind)
        assertTrue(!error.outputStarted && error.retryable)
    }

    @Test
    fun `a failed response after output fails as a server error that output started`() {
        val failure = JsonObject(
            responseObject("failed") +
                (
                    "error" to buildJsonObject {
                        put("code", "server_error")
                        put("message", "The model failed.")
                    }
                    ),
        )

        val error =
            fail(
                sse(
                    created(),
                    itemAdded(0, messageItem("msg_0")),
                    textDelta(0, "Hal"),
                    responseEvent("response.failed", mapOf("response" to failure)),
                ),
            )

        assertEquals(ModelErrorKind.SERVER_ERROR, error.kind)
        assertTrue(error.outputStarted)
        assertTrue("The model failed." in error.message)
    }

    @Test
    fun `a stream that ends before the response completes is a protocol failure`() {
        val error = fail(sse(created(), itemAdded(0, messageItem("msg_0")), textDelta(0, "Hal")))

        assertEquals(ModelErrorKind.PROTOCOL, error.kind)
        assertTrue(error.outputStarted)
    }

    @Test
    fun `HTTP failures map onto their kinds`() {
        val cases = listOf(
            error(400, "invalid_request_error", "context_length_exceeded", "Your input exceeds the context window.") to
                ModelErrorKind.CONTEXT_WINDOW_EXCEEDED,
            error(401, "invalid_request_error", "invalid_api_key", "Incorrect API key provided.") to
                ModelErrorKind.AUTHENTICATION,
            error(429, "insufficient_quota", "insufficient_quota", "You exceeded your current quota.") to
                ModelErrorKind.QUOTA_EXHAUSTED,
            error(429, "requests", "rate_limit_exceeded", "Rate limit reached.") to ModelErrorKind.RATE_LIMITED,
            error(404, "invalid_request_error", "model_not_found", "The model does not exist.") to
                ModelErrorKind.MODEL_NOT_FOUND,
            error(400, "invalid_request_error", "invalid_image_format", "Bad image.") to ModelErrorKind.INVALID_REQUEST,
            error(503, "server_error", "server_is_overloaded", "Overloaded.") to ModelErrorKind.OVERLOADED,
            error(500, "server_error", null, "The server had an error.") to ModelErrorKind.SERVER_ERROR,
        )

        for ((response, kind) in cases) {
            val error = fail(response)
            assertEquals(kind, error.kind, "$response")
            assertEquals("req_9", error.requestId)
        }
    }
}
