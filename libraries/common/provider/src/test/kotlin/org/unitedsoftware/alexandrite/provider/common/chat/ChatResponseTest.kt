package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.TimeoutConfig
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
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
import kotlin.time.Duration.Companion.seconds

class ChatResponseTest {
    private val server = FakeModelServer()
    private val turn = testTurn()

    /** A flavor whose every response part reads its own way. */
    private val custom = ChatFlavor(
        Dialect("custom"),
        reasoning = object : ChatReasoning() {
            override fun reader(origin: ModelRef): ChatReasoningReader = object : ChatReasoningReader() {
                override fun seal(text: String): ReasoningSeal =
                    ReasoningSeal(origin, Dialect("custom"), SealKind.PLAIN, text.uppercase())
            }
        },
        tools = object : ChatTools() {
            override fun callName(wire: String, offered: List<ToolDefinition>): String = offered.first().name
        },
        caching = object : ChatCaching() {
            override fun cacheReadTokens(usage: JsonObject): Long? = usage.long("hits")

            override fun cacheWriteTokens(usage: JsonObject): Long? = usage.long("writes")
        },
        finishReasons = object : ChatFinishReasons() {
            override fun kind(raw: String): FinishKind = if (raw == "done") FinishKind.END_TURN else super.kind(raw)

            override fun failure(raw: String): ModelErrorKind? = ModelErrorKind.OVERLOADED.takeIf { raw == "busy" }
        },
    )

    /** A flavor that holds back each trailing `!` and finds two in a row malformed. */
    private val guarded = ChatFlavor(
        Dialect("guarded"),
        text = object : ChatText() {
            override fun reader(): ChatTextReader = object : ChatTextReader() {
                private var held = ""

                override fun read(text: String): ChatTextDelta {
                    val all = held + text
                    if ("!!" in all) return ChatTextDelta("", "Two in a row.")
                    held = if (all.endsWith("!")) "!" else ""
                    return ChatTextDelta(all.removeSuffix("!"))
                }

                override fun release(): String = held.also { held = "" }
            }
        },
    )

    @AfterTest
    fun close() {
        server.close()
    }

    private fun request(model: String = "chat-model", block: ModelRequest.Builder.() -> Unit = {}): ModelRequest =
        ModelRequest.builder(
            ModelRef(EndpointId("test"), model),
            listOf(testUserEntry(turn, "Hi")),
            0,
            RequestIds(turn.conversation, turn.id, 2),
        ).apply(block).build()

    private fun respond(
        response: FakeResponse,
        flavor: ChatFlavor = ChatFlavor.STANDARD,
        request: ModelRequest = request(),
    ): List<ModelEvent> = runBlocking {
        server.enqueue(response, "/v1/chat/completions")
        chatEndpoint(server, flavor).use { it.events(request) }
    }

    private fun fail(response: FakeResponse, flavor: ChatFlavor = ChatFlavor.STANDARD): ModelException =
        assertFailsWith<ModelException> { respond(response, flavor) }

    private fun completed(events: List<ModelEvent>): ModelEvent.Completed = events.last() as ModelEvent.Completed

    private fun call(index: Int, id: String? = null, name: String? = null, arguments: String? = null): JsonObject =
        buildJsonObject {
            put("index", index)
            id?.let { put("id", it) }
            putJsonObject("function") {
                name?.let { put("name", it) }
                arguments?.let { put("arguments", it) }
            }
        }

    private fun calls(vararg calls: JsonObject): JsonArray = JsonArray(calls.toList())

    @Test
    fun `the request carries the key, asks for a stream and the response starts with its id and warnings`() {
        val request = request { options(ModelOptions.builder().reasoning(ReasoningEffort.HIGH).build()) }

        val events = respond(chunks(chunk { with("content", "Hi") }, chunk("stop")), request = request)

        val recorded = server.requests.single()
        assertEquals("Bearer $FIXTURE_API_KEY", recorded.header("authorization"))
        assertEquals("text/event-stream", recorded.header("accept"))
        assertEquals("/v1/chat/completions", recorded.path)
        val started = events.first() as ModelEvent.ResponseStarted
        assertEquals("resp-1" to "chat-model", started.responseId to started.model)
        assertEquals(listOf("unsupported_option"), started.warnings.map { it.kind.id })
    }

    @Test
    fun `the key goes in the header the settings name, as it is outside authorization`() {
        server.enqueue(chunks(chunk("stop")), "/v1/chat/completions")

        runBlocking { chatEndpoint(server, ChatFlavor.STANDARD, authHeader = "api-key").use { it.events(request()) } }

        val recorded = server.requests.single()
        assertEquals(FIXTURE_API_KEY, recorded.header("api-key"))
        assertNull(recorded.header("authorization"))
    }

    @Test
    fun `a call without an id gets the request's id and arguments before its name wait for it`() {
        val events = respond(
            chunks(
                chunk { with("tool_calls", calls(call(0, arguments = "{\"te"))) },
                chunk {
                    with(
                        "tool_calls",
                        calls(call(0, name = "notes-add", arguments = "xt\":"), call(0, arguments = "1}")),
                    )
                },
                chunk("tool_calls"),
            ),
            request = request { tools(listOf(testToolDefinition("notes.add"))) },
        )

        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>().single()
        assertEquals(ToolCallId("call-test-turn-2-0") to "notes.add", started.id to started.name)
        val fragments = events.filterIsInstance<ModelEvent.ToolArgumentsDelta>().map { it.fragment }
        assertEquals(listOf("{\"text\":", "1}"), fragments)
        val part = completed(events).message.parts.single() as ToolCallPart
        assertEquals("{\"text\":1}", part.arguments)
    }

    @Test
    fun `calls that name no index are told apart by their ids and names`() {
        fun unindexed(id: String? = null, name: String? = null, arguments: String? = null) =
            JsonObject(call(0, id, name, arguments) - "index")
        val events = respond(
            chunks(
                chunk { with("tool_calls", calls(unindexed("a", "notes-add", "{\"x\":"))) },
                chunk { with("tool_calls", calls(unindexed(arguments = "1}"))) },
                chunk { with("tool_calls", calls(unindexed("b", "notes-add", "{\"y\":2}"))) },
                chunk { with("tool_calls", calls(unindexed(name = "notes-add", arguments = "{}"))) },
                chunk("tool_calls"),
            ),
            request = request { tools(listOf(testToolDefinition("notes.add"))) },
        )

        val parts = completed(events).message.parts.map { it as ToolCallPart }
        assertEquals(
            listOf("a" to "{\"x\":1}", "b" to "{\"y\":2}", "call-test-turn-2-2" to "{}"),
            parts.map { it.id.value to it.arguments },
        )
    }

    @Test
    fun `calls that name no index but repeat their id and name with each fragment stay one call`() {
        fun unindexed(arguments: String) = JsonObject(call(0, "c1", "notes-add", arguments) - "index")
        val events = respond(
            chunks(
                chunk { with("tool_calls", calls(unindexed("{\"te"))) },
                chunk { with("tool_calls", calls(unindexed("xt\":1}"))) },
                chunk("tool_calls"),
            ),
            request = request { tools(listOf(testToolDefinition("notes.add"))) },
        )

        val part = completed(events).message.parts.single() as ToolCallPart
        assertEquals(Triple("c1", "notes.add", "{\"text\":1}"), Triple(part.id.value, part.name, part.arguments))
    }

    @Test
    fun `a flavor that renames tools on the wire gets each call back under its tool's name`() {
        val snake = ChatFlavor(
            Dialect("snake"),
            tools = object : ChatTools() {
                override fun wireName(name: String): String = name.replace('.', '_')
            },
        )
        val reply = chunks(chunk { with("tool_calls", calls(call(0, "c1", "notes_add", "{}"))) }, chunk("tool_calls"))

        val events = respond(reply, snake, request { tools(listOf(testToolDefinition("notes.add"))) })

        assertEquals("notes.add", (completed(events).message.parts.single() as ToolCallPart).name)
    }

    @Test
    fun `a flavor keeps the vendor fields of each call with it`() {
        val vendor = ChatFlavor(
            Dialect("vendor"),
            tools = object : ChatTools() {
                override fun readCall(call: JsonObject): JsonObject? =
                    call.obj("extra_content")?.let { JsonObject(mapOf("extra_content" to it)) }
            },
        )
        val signed = buildJsonObject {
            put("index", 0)
            put("id", "c1")
            putJsonObject("function") {
                put("name", "notes-add")
                put("arguments", "{}")
            }
            putJsonObject("extra_content") { put("signature", "sig-1") }
        }
        val reply = chunks(chunk { with("tool_calls", calls(signed)) }, chunk("tool_calls"))

        val events = respond(reply, vendor, request { tools(listOf(testToolDefinition("notes.add"))) })

        val part = completed(events).message.parts.single() as ToolCallPart
        assertEquals(
            buildJsonObject { putJsonObject("extra_content") { put("signature", "sig-1") } }.toString(),
            part.providerData.entries[Dialect("vendor")].toString(),
        )
    }

    @Test
    fun `a name that names no tool is kept as it came`() {
        val reply = chunks(chunk { with("tool_calls", calls(call(0, "c1", "Weird Name", "{}"))) }, chunk("tool_calls"))

        val events = respond(reply)

        assertEquals("Weird Name", (completed(events).message.parts.single() as ToolCallPart).name)
    }

    @Test
    fun `reasoning in reasoning_content or reasoning is shown, but the standard API seals none of it`() {
        for (field in listOf("reasoning_content", "reasoning")) {
            val reply = chunks(chunk { with(field, "Hm.") }, chunk { with("content", "Yes.") }, chunk("stop"))

            val events = respond(reply)

            val reasoning = completed(events).message.parts.first() as ReasoningPart
            assertEquals("Hm.", reasoning.text, field)
            assertNull(reasoning.seal, field)
            assertTrue(events.none { it is ModelEvent.ReasoningSealed }, field)
        }
    }

    @Test
    fun `a flavor's reader sees each raw delta of a reasoning part and seals the part`() {
        val read = mutableListOf<JsonObject>()
        val flavor = ChatFlavor(
            Dialect("custom"),
            reasoning = object : ChatReasoning() {
                override fun reader(origin: ModelRef): ChatReasoningReader = object : ChatReasoningReader() {
                    private val signatures = StringBuilder()

                    override fun read(delta: JsonObject): ChatReasoningDelta? {
                        read += delta
                        val thought = delta.string("thought") ?: return null
                        delta.string("signature")?.let(signatures::append)
                        return ChatReasoningDelta(thought.ifEmpty { null }, summary = "s")
                    }

                    override fun seal(text: String): ReasoningSeal =
                        ReasoningSeal(origin, Dialect("custom"), SealKind.SIGNATURE, "$signatures")
                }
            },
        )

        val events = respond(
            chunks(
                chunk { with("reasoning", "Let ").with("thought", "ignored") },
                chunk { with("thought", "me.") },
                chunk { with("thought", "").with("signature", "sig") },
                chunk { with("content", "Done.") },
                chunk("stop"),
            ),
            flavor,
        )

        val reasoning = completed(events).message.parts.first() as ReasoningPart
        assertEquals("Let me." to "sss", reasoning.text to reasoning.summary)
        assertEquals("sig", reasoning.seal?.data)
        assertEquals(listOf("Done.", null), read.takeLast(2).map { it.string("content") })
    }

    @Test
    fun `a flavor's parts seal, name calls, end and count the cache their own way`() {
        val usage = buildJsonObject {
            put("prompt_tokens", 100)
            put("hits", 60)
            put("writes", 30)
            put("completion_tokens", 20)
        }

        val events = respond(
            chunks(
                chunk { with("reasoning_content", "think") },
                chunk { with("tool_calls", calls(call(0, "c1", "anything", "{}"))) },
                chunk("done"),
                usageChunk(usage),
            ),
            custom,
            request { tools(listOf(testToolDefinition("notes.add"))) },
        )

        val completed = completed(events)
        val reasoning = completed.message.parts.first() as ReasoningPart
        assertEquals("THINK", reasoning.seal?.data)
        assertEquals("THINK", events.filterIsInstance<ModelEvent.ReasoningSealed>().single().seal.data)
        assertEquals("notes.add", (completed.message.parts[1] as ToolCallPart).name)
        assertEquals(FinishKind.END_TURN to "done", completed.finish.kind to completed.finish.raw)
        assertEquals(60L to 30L, completed.usage.cacheReadTokens to completed.usage.cacheWriteTokens)
        assertEquals(usage, completed.usage.raw)
    }

    @Test
    fun `an end the flavor counts as a failure fails at once`() {
        val error = fail(chunks(chunk { with("content", "Par") }, chunk("busy"), done = false), custom).error

        assertEquals(ModelErrorKind.OVERLOADED, error.kind)
        assertTrue(error.retryable && error.outputStarted)
        assertTrue("'busy'" in error.message)
    }

    @Test
    fun `a flavor's text reader holds text back until it reads on or the part ends`() {
        val events = respond(
            chunks(
                chunk { with("content", "Hi!") },
                chunk { with("content", " there!") },
                chunk { with("tool_calls", calls(call(0, "c1", "notes.add", "{}"))) },
                chunk("tool_calls"),
            ),
            guarded,
        )

        val text = events.filter { it is ModelEvent.TextDelta || (it as? ModelEvent.PartCompleted)?.index == 0 }
        assertEquals(
            listOf(
                ModelEvent.TextDelta(0, "Hi"),
                ModelEvent.TextDelta(0, "! there"),
                ModelEvent.TextDelta(0, "!"),
                ModelEvent.PartCompleted(0, TextPart("Hi! there!")),
            ),
            text,
        )
        assertTrue(events.indexOf(text.last()) < events.indexOfFirst { it is ModelEvent.ToolCallStarted })
        val held = respond(chunks(chunk { with("content", "!") }, chunk("stop")), guarded)
        assertEquals(listOf(TextPart("!")), completed(held).message.parts)
    }

    @Test
    fun `output the text reader finds malformed fails the response, as it started or not`() {
        val late = fail(chunks(chunk { with("content", "Hi!") }, chunk { with("content", "!") }), guarded).error
        val early = fail(chunks(chunk { with("content", "!!") }, chunk("stop")), guarded).error

        assertEquals(ModelErrorKind.MALFORMED_OUTPUT to "Two in a row.", late.kind to late.message)
        assertTrue(late.retryable && late.outputStarted)
        assertEquals(ModelErrorKind.MALFORMED_OUTPUT to false, early.kind to early.outputStarted)
    }

    @Test
    fun `finish reasons map to their kinds and keep the raw value`() {
        val finishes = listOf("stop", "length", "tool_calls", "content_filter", "abort", "error").map { raw ->
            completed(respond(chunks(chunk(raw)))).finish
        }

        val kinds = listOf(FinishKind.END_TURN, FinishKind.MAX_OUTPUT_TOKENS, FinishKind.TOOL_USE, FinishKind.REFUSAL)
        assertEquals(kinds + FinishKind.OTHER + FinishKind.OTHER, finishes.map { it.kind })
        assertEquals("abort", finishes[4].raw)
    }

    @Test
    fun `the standard API counts cached tokens and inconsistent counts are left out`() {
        val consistent = openAiUsage(100, 40, 20, 5)
        val inconsistent = buildJsonObject {
            put("prompt_tokens", 10)
            putJsonObject("prompt_tokens_details") { put("cached_tokens", 40) }
            put("completion_tokens", 5)
            putJsonObject("completion_tokens_details") { put("reasoning_tokens", 9) }
        }

        val good = completed(respond(chunks(chunk("stop"), usageChunk(consistent)))).usage
        val bad = completed(respond(chunks(chunk("stop"), usageChunk(inconsistent)))).usage

        assertEquals(
            listOf(100L, 40L, null, 20L, 5L, 100L),
            with(good) {
                listOf(inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens, reasoningTokens, contextTokens)
            },
        )
        assertEquals(listOf(10L, null, null), with(bad) { listOf(inputTokens, cacheReadTokens, reasoningTokens) })
    }

    @Test
    fun `an error in the stream says what broke and whether output started`() {
        val failure = buildJsonObject {
            putJsonObject("error") {
                put("code", 502)
                put("message", "Provider returned error")
                putJsonObject("metadata") { put("error_type", "provider_unavailable") }
            }
            put("choices", buildJsonArray { add(chunk("error")["choices"]!!.let { (it as JsonArray)[0] }) })
        }

        val early = fail(chunks(failure)).error
        val late = fail(chunks(chunk { with("content", "Hi") }, failure)).error

        assertEquals(ModelErrorKind.SERVER_ERROR to "provider_unavailable", early.kind to early.rawType)
        assertFalse(early.outputStarted)
        assertTrue(late.outputStarted)
        assertTrue("Provider returned error" in late.message)
    }

    @Test
    fun `a stream that ends without a finish reason breaks the protocol`() {
        val error = fail(chunks(chunk { with("content", "Hi") })).error

        assertEquals(ModelErrorKind.PROTOCOL, error.kind)
        assertTrue(error.outputStarted)
    }

    @Test
    fun `null fields that some servers send in every chunk are absent fields`() {
        val response = fakeResponse {
            event(
                """{"id":"r","choices":[{"index":0,"delta":{"content":"Hi","reasoning":null,"tool_calls":null}}],""" +
                    """"usage":null,"error":null}""",
            )
            event("""{"choices":[{"index":0,"delta":{"content":null},"finish_reason":"stop"}],"usage":null}""")
        }

        val completed = completed(respond(response))

        assertEquals(listOf(TextPart("Hi")), completed.message.parts)
        assertEquals(null, completed.usage.inputTokens)
    }

    @Test
    fun `a finished stream completes without its done marker`() {
        val events = respond(chunks(chunk { with("content", "Hi") }, chunk("stop"), done = false))

        assertEquals(listOf(TextPart("Hi")), completed(events).message.parts)
    }

    @Test
    fun `keep-alive comments and blank events pass unnoticed`() {
        val response = fakeResponse {
            comment("keep-alive")
            event(chunk { with("content", "Hi") }.toString())
            comment("OPENROUTER PROCESSING")
            event(chunk("stop").toString())
            event("[DONE]")
        }

        assertEquals(listOf(TextPart("Hi")), completed(respond(response)).message.parts)
    }

    @Test
    fun `a response that is not streamed is answered as one chunk`() {
        val message = buildJsonObject {
            put("id", "resp-9")
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            putJsonObject("message") {
                                put("role", "assistant")
                                put("content", "Whole.")
                                put("tool_calls", calls(JsonObject(call(0, "c1", "notes-add", "{}") - "index")))
                            }
                            put("finish_reason", "tool_calls")
                        },
                    )
                },
            )
            put("usage", openAiUsage(5, 0, 3, 0))
        }

        val events = respond(fakeResponse { body(message.toString()) })

        val parts = completed(events).message.parts
        assertEquals(TextPart("Whole."), parts[0])
        assertEquals("notes.add", (parts[1] as ToolCallPart).name)
        assertEquals(5L, completed(events).usage.inputTokens)
    }

    @Test
    fun `a malformed chunk or an unknown media type breaks the protocol`() {
        assertEquals(ModelErrorKind.PROTOCOL, fail(fakeResponse { event("{not json") }).error.kind)
        val html = FakeResponse.builder(200).header("content-type", "text/html").body("<html>").build()
        assertEquals(ModelErrorKind.PROTOCOL, fail(html).error.kind)
    }

    @Test
    fun `a stream that stalls times out after its output started`() {
        val stalled = fakeResponse {
            event(chunk { with("content", "Hi") }.toString())
            pause(3.seconds)
            event(chunk("stop").toString())
        }
        server.enqueue(stalled)
        val quick = TimeoutConfig(connectSeconds = 1, firstByteSeconds = 1, idleSeconds = 1)
        val settings = EndpointSettings("${server.baseUrl}/v1", Secret(FIXTURE_API_KEY), timeouts = quick)

        val error = ChatEndpoint(EndpointId("test"), settings, testHttpEngine(), ChatFlavor.STANDARD).use { endpoint ->
            assertFailsWith<ModelException> { runBlocking { endpoint.events(request()) } }
        }.error

        assertEquals(ModelErrorKind.TIMEOUT, error.kind)
        assertTrue(error.retryable && error.outputStarted)
    }

    @Test
    fun `a request for another endpoint or for tools a model cannot take never reaches the backend`() {
        val elsewhere =
            ModelRequest.builder(
                ModelRef(EndpointId("other"), "m"),
                listOf(testUserEntry(turn)),
                0,
                request().ids,
            ).build()
        val tools = request("plain") { tools(listOf(testToolDefinition())) }

        val plain = mapOf("plain" to ModelConfig(nativeTools = false))
        val errors = chatEndpoint(server, ChatFlavor.STANDARD, plain).use { endpoint ->
            listOf(elsewhere, tools).map {
                assertFailsWith<ModelException> { runBlocking { endpoint.events(it) } }.error
            }
        }

        assertEquals(listOf(ModelErrorKind.INVALID_REQUEST, ModelErrorKind.UNSUPPORTED), errors.map { it.kind })
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `an assistant entry from this provider goes back as it came`() {
        val events = respond(chunks(chunk { with("content", "Hello.") }, chunk("stop")))
        val message: AssistantEntry = completed(events).message
        val history = listOf(testUserEntry(turn, "Hi"), message, testUserEntry(turn, "Bye"))
        val next = ChatRequest(
            ModelRequest.builder(message.producedBy, history, 2, request().ids).build(),
            ModelInfo.builder("chat-model", ChatFlavor.OPENAI_CHAT).build(),
            ChatFlavor.STANDARD,
            false,
        ).body

        val assistant = (next["messages"] as JsonArray)[1]
        assertEquals("""{"role":"assistant","content":"Hello."}""", assistant.toString())
    }
}
