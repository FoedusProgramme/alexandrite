package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_API_KEY
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
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
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesResponseTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val models = mapOf(
        "claude-model" to
            ModelConfig(
                reasoningEfforts = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH),
                maxOutputTokens = 8000,
            ),
    )

    @AfterTest
    fun close() {
        server.close()
    }

    private fun request(block: ModelRequest.Builder.() -> Unit = {}): ModelRequest = ModelRequest.builder(
        ModelRef(EndpointId("test"), "claude-model"),
        listOf(testUserEntry(turn, "Hi")),
        0,
        RequestIds(turn.conversation, turn.id, 2),
    ).apply(block).build()

    private fun respond(
        response: FakeResponse,
        request: ModelRequest = request(),
        messages: MessagesSettings = MessagesSettings(),
    ): List<ModelEvent> = runBlocking {
        server.enqueue(response, "/v1/messages")
        messagesEndpoint(server, models = models, discover = false, messages = messages).use { it.events(request) }
    }

    private fun fail(response: FakeResponse): ModelError = assertFailsWith<ModelException> { respond(response) }.error

    private fun completed(events: List<ModelEvent>): ModelEvent.Completed = events.last() as ModelEvent.Completed

    private fun error(status: Int, type: String, message: String): FakeResponse =
        FakeResponse.builder(status).header("request-id", "req_9").body(messagesError(type, message)).build()

    @Test
    fun `the request signs with the API key and version, asks for a stream and the response starts at once`() {
        val request = request { options(ModelOptions.builder().reasoning(ReasoningEffort.MAX).build()) }

        val events = respond(sse(*textEvents(listOf("Hi"))), request, MessagesSettings(betas = listOf("beta-a")))

        val recorded = server.requests.single()
        assertEquals("/v1/messages", recorded.path)
        assertEquals(FIXTURE_API_KEY, recorded.header("x-api-key"))
        assertNull(recorded.header("authorization"))
        assertEquals("2023-06-01", recorded.header("anthropic-version"))
        assertEquals("beta-a", recorded.header("anthropic-beta"))
        assertEquals("text/event-stream", recorded.header("accept"))
        assertEquals("true", recorded.json()["stream"].toString())
        val started = events.first() as ModelEvent.ResponseStarted
        assertEquals("msg_1" to "claude-model", started.responseId to started.model)
        assertEquals(listOf("unsupported_option"), started.warnings.map { it.kind.id })
    }

    @Test
    fun `text streams in deltas, and pings and unknown events pass unnoticed`() {
        val response = sse(
            messageStart(),
            buildJsonObject { put("type", "ping") },
            blockStart(0, textBlock()),
            textDelta(0, "Hel"),
            buildJsonObject { put("type", "future_event") },
            blockDelta(0, "citations_delta", "citation", "x"),
            textDelta(0, "lo."),
            blockStop(0),
            messageDelta("end_turn"),
            messageStop(),
        )

        val events = respond(response)

        assertEquals(listOf("Hel", "lo."), events.filterIsInstance<ModelEvent.TextDelta>().map { it.text })
        assertEquals(listOf(TextPart("Hello.")), completed(events).message.parts)
        assertEquals(FinishKind.END_TURN, completed(events).finish.kind)
    }

    @Test
    fun `thinking is shown and sealed by its signature, redacted thinking by its data`() {
        val redacted = buildJsonObject {
            put("type", "redacted_thinking")
            put("data", "EmwKAhgB")
        }
        val events = respond(
            sse(
                messageStart(),
                blockStart(0, thinkingBlock()),
                thinkingDelta(0, "Two and "),
                thinkingDelta(0, "two."),
                signatureDelta(0, "EqQBCgIYAh"),
                blockStop(0),
                blockStart(1, redacted),
                blockStop(1),
                blockStart(2, thinkingBlock()),
                signatureDelta(2, "EqOmitted"),
                blockStop(2),
                blockStart(3, textBlock()),
                textDelta(3, "Four."),
                blockStop(3),
                messageDelta("end_turn"),
                messageStop(),
            ),
        )

        val parts = completed(events).message.parts
        val (shown, hidden, omitted) = parts.filterIsInstance<ReasoningPart>()
        assertEquals("Two and two." to "EqQBCgIYAh", shown.text to shown.seal?.data)
        assertEquals(SealKind.SIGNATURE to MessagesFlavor.ANTHROPIC, shown.seal?.kind to shown.seal?.dialect)
        assertEquals(ModelRef(EndpointId("test"), "claude-model"), shown.seal?.origin)
        assertEquals(null to SealKind.ENCRYPTED, hidden.text to hidden.seal?.kind)
        assertEquals("EmwKAhgB", hidden.seal?.data)
        assertEquals(null to "EqOmitted", omitted.text to omitted.seal?.data)
        val sealed = events.indexOfFirst { it is ModelEvent.ReasoningSealed }
        val completed = events.indexOfFirst { it is ModelEvent.PartCompleted }
        assertTrue(sealed in 0 until completed)
        assertEquals(TextPart("Four."), parts.last())
    }

    @Test
    fun `tool calls keep the API's ids, map their names back and stream their arguments`() {
        val events = respond(
            sse(
                messageStart(),
                blockStart(0, textBlock()),
                textDelta(0, "Saving."),
                blockStop(0),
                blockStart(1, toolUseBlock("toolu_01A", "notes-add")),
                inputDelta(1, ""),
                inputDelta(1, "{\"text\":"),
                inputDelta(1, "\"milk\"}"),
                blockStop(1),
                blockStart(2, toolUseBlock("toolu_01B", "Weird Name")),
                blockStop(2),
                messageDelta("tool_use"),
                messageStop(),
            ),
            request { tools(listOf(testToolDefinition("notes.add"))) },
        )

        val calls = completed(events).message.parts.filterIsInstance<ToolCallPart>()
        assertEquals(
            listOf("toolu_01A notes.add {\"text\":\"milk\"}", "toolu_01B Weird Name "),
            calls.map { "${it.id} ${it.name} ${it.arguments}" },
        )
        assertEquals(
            listOf("{\"text\":", "\"milk\"}"),
            events.filterIsInstance<ModelEvent.ToolArgumentsDelta>().map { it.fragment },
        )
        assertEquals(FinishKind.TOOL_USE, completed(events).finish.kind)
    }

    @Test
    fun `usage adds the cache to the input and the last report counts`() {
        val events = respond(
            sse(
                messageStart(messagesUsage(input = 20, cacheRead = 1000, cacheWrite = 180, output = 1)),
                blockStart(0, textBlock("Hi")),
                blockStop(0),
                messageDelta("end_turn", outputUsage(80, thinking = 30)),
                messageStop(),
            ),
        )

        val usage = completed(events).usage
        assertEquals(
            listOf(1200L, 1000L, 180L, 80L, 30L, 1200L),
            with(usage) {
                listOf(inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens, reasoningTokens, contextTokens)
            },
        )
        assertEquals(80L, usage.raw?.get("output_tokens")?.toString()?.toLong())
        assertEquals(2, events.count { it is ModelEvent.UsageUpdated })
        assertEquals(listOf(TextPart("Hi")), completed(events).message.parts)
    }

    @Test
    fun `stop reasons map to their kinds and keep the raw value and what else they say`() {
        val raws = listOf(
            "end_turn",
            "max_tokens",
            "stop_sequence",
            "tool_use",
            "pause_turn",
            "refusal",
            "model_context_window_exceeded",
            "mystery",
        )
        val finishes = raws.map { raw -> completed(respond(sse(*textEvents(listOf("x"), raw)))).finish }
        val refusal = buildJsonObject {
            put("type", "message_delta")
            putJsonObject("delta") {
                put("stop_reason", "refusal")
                putJsonObject("stop_details") {
                    put("type", "refusal")
                    put("category", "cyber")
                }
            }
        }
        val sequence = buildJsonObject {
            put("type", "message_delta")
            putJsonObject("delta") {
                put("stop_reason", "stop_sequence")
                put("stop_sequence", "END")
            }
        }

        assertEquals(
            listOf(
                FinishKind.END_TURN,
                FinishKind.MAX_OUTPUT_TOKENS,
                FinishKind.STOP_SEQUENCE,
                FinishKind.TOOL_USE,
                FinishKind.PAUSED,
                FinishKind.REFUSAL,
                FinishKind.CONTEXT_WINDOW_EXCEEDED,
                FinishKind.OTHER,
            ),
            finishes.map { it.kind },
        )
        assertEquals(raws, finishes.map { it.raw })
        assertEquals("cyber", completed(respond(sse(messageStart(), refusal, messageStop()))).finish.detail)
        assertEquals("END", completed(respond(sse(messageStart(), sequence, messageStop()))).finish.detail)
    }

    @Test
    fun `an error event in the stream says what broke and whether output started`() {
        val overloaded = buildJsonObject {
            put("type", "error")
            putJsonObject("error") {
                put("type", "overloaded_error")
                put("message", "Overloaded")
            }
        }

        val early = fail(sse(overloaded))
        val beforeContent = fail(sse(messageStart(), buildJsonObject { put("type", "ping") }, overloaded))
        val late = fail(sse(messageStart(), blockStart(0, textBlock()), textDelta(0, "Hi"), overloaded))

        assertEquals(ModelErrorKind.OVERLOADED to "overloaded_error", early.kind to early.rawType)
        assertTrue(early.retryable && !early.outputStarted)
        assertTrue(beforeContent.retryable && !beforeContent.outputStarted)
        assertTrue(late.outputStarted && "Overloaded" in late.message)
    }

    @Test
    fun `a stream cut off before its first content block has not started`() {
        val cut = fail(sse(messageStart(messagesUsage(input = 20, cacheRead = 0, cacheWrite = 0, output = 1))))

        assertEquals(ModelErrorKind.PROTOCOL to false, cut.kind to cut.outputStarted)
    }

    @Test
    fun `the start and the usage of message_start come with the first content`() {
        val events = respond(sse(*textEvents(listOf("Hi"))))

        assertEquals(
            listOf(ModelEvent.ResponseStarted::class, ModelEvent.UsageUpdated::class, ModelEvent.TextDelta::class),
            events.take(3).map { it::class },
        )
    }

    @Test
    fun `error bodies make the status precise and an echoed key never reaches the error`() {
        val cases = listOf(
            error(529, "overloaded_error", "Overloaded") to ModelErrorKind.OVERLOADED,
            error(400, "invalid_request_error", "prompt is too long: 250000 tokens > 200000 maximum") to
                ModelErrorKind.CONTEXT_WINDOW_EXCEEDED,
            error(400, "invalid_request_error", "max_tokens: field required") to ModelErrorKind.INVALID_REQUEST,
            error(404, "not_found_error", "model: claude-nope") to ModelErrorKind.MODEL_NOT_FOUND,
            error(402, "billing_error", "Your credit balance is too low.") to ModelErrorKind.QUOTA_EXHAUSTED,
            error(401, "authentication_error", "invalid x-api-key $FIXTURE_API_KEY") to ModelErrorKind.AUTHENTICATION,
            error(500, "api_error", "Internal server error") to ModelErrorKind.SERVER_ERROR,
            error(504, "timeout_error", "Request timed out") to ModelErrorKind.TIMEOUT,
        )

        for ((response, kind) in cases) {
            val error = fail(response)
            assertEquals(kind, error.kind, error.message)
            assertEquals("req_9", error.requestId)
            assertFalse(FIXTURE_API_KEY in error.message)
            assertFalse(error.outputStarted)
        }
    }

    @Test
    fun `a response that is not streamed is answered as one message`() {
        val message = buildJsonObject {
            put("id", "msg_9")
            put("type", "message")
            put("role", "assistant")
            put("model", "claude-model")
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "thinking")
                        put("thinking", "Hm.")
                        put("signature", "sig")
                    },
                )
                add(textBlock("Whole."))
                add(
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", "toolu_9")
                        put("name", "notes-add")
                        putJsonObject("input") { put("text", "milk") }
                    },
                )
            }
            put("stop_reason", "tool_use")
            put("usage", messagesUsage(5, 0, 0, 3))
        }

        val events = respond(fakeResponse { body(message.toString()) })

        val parts = completed(events).message.parts
        assertEquals("Hm." to "sig", (parts[0] as ReasoningPart).let { it.text to it.seal?.data })
        assertEquals(TextPart("Whole."), parts[1])
        val call = parts[2] as ToolCallPart
        assertEquals(ToolCallId("toolu_9") to "{\"text\":\"milk\"}", call.id to call.arguments)
        assertEquals(5L to FinishKind.TOOL_USE, completed(events).usage.inputTokens to completed(events).finish.kind)
        assertEquals("msg_9", (events.first() as ModelEvent.ResponseStarted).responseId)
    }

    @Test
    fun `a block of a type this version does not know is kept for its dialect`() {
        val block = buildJsonObject {
            put("type", "server_tool_use")
            put("id", "srvtoolu_1")
            put("name", "web_search")
            putJsonObject("input") {}
        }

        val events = respond(
            sse(
                messageStart(),
                blockStart(0, block),
                inputDelta(0, "{\"query\":\"x\"}"),
                blockStop(0),
                messageDelta("end_turn"),
                messageStop(),
            ),
        )

        val part = completed(events).message.parts.single() as OpaquePart
        assertEquals(MessagesFlavor.ANTHROPIC to "server_tool_use", part.dialect to part.kind)
        assertEquals(JsonObject(block + ("input" to buildJsonObject { put("query", "x") })), part.json)
    }

    @Test
    fun `a stream that ends without a stop reason or with a stray delta breaks the protocol`() {
        val unfinished = fail(sse(messageStart(), blockStart(0, textBlock()), textDelta(0, "Hi"), blockStop(0)))
        val stray = fail(sse(messageStart(), textDelta(3, "Hi")))

        assertEquals(ModelErrorKind.PROTOCOL to true, unfinished.kind to unfinished.outputStarted)
        assertEquals(ModelErrorKind.PROTOCOL, stray.kind)
    }

    @Test
    fun `a stream that stays open after message_stop completes`() {
        val events = respond(sse(*textEvents(listOf("Hi"))).hanging())

        assertEquals(listOf(TextPart("Hi")), completed(events).message.parts)
    }
}
