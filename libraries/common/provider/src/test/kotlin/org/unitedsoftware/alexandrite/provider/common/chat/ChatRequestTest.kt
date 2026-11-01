package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.model.WarningKind
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.ProviderData
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRequestTest {
    private val turn = testTurn()
    private val model = ModelRef(EndpointId("test"), "chat-model")
    private val info = ModelInfo.builder("chat-model", ChatFlavor.OPENAI_CHAT)
        .nativeTools(true)
        .parallelToolCalls(true)
        .inputMedia(setOf(MediaKind.IMAGE))
        .reasoningEfforts(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        .streaming(true)
        .build()
    private val call = ToolCallPart(ToolCallId("call_1"), "notes.add", "")
    private val context = TurnContextItem("recall", "Recalled: the user likes tea.", Trust.TRUSTED)

    /** A flavor whose every request part writes its own fields. */
    private val custom = ChatFlavor(
        Dialect("custom"),
        reasoning = object : ChatReasoning() {
            override fun request(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
                body.put("thinking", effort.id)
                return null
            }

            override fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {
                message.put("thoughts", parts.mapNotNull { it.seal?.data }.joinToString())
            }
        },
        tools = object : ChatTools() {
            override val acceptsParallelToolCalls: Boolean = false

            override fun toolChoice(request: ModelRequest, body: JsonObjectBuilder): Warning? =
                Warning(WarningKind.UNSUPPORTED_OPTION, "No choice here.")
        },
        caching = object : ChatCaching() {
            override fun key(key: String, body: JsonObjectBuilder) {
                body.put("session", key)
            }
        },
    )

    private fun user(text: String) = testUserEntry(turn, text)

    private fun request(
        history: List<TranscriptEntry>,
        round: Int = 0,
        block: ModelRequest.Builder.() -> Unit = {},
    ): ModelRequest = ModelRequest.builder(model, history, 0, RequestIds(turn.conversation, turn.id, round))
        .apply(block)
        .build()

    private fun chat(
        request: ModelRequest,
        flavor: ChatFlavor = ChatFlavor.STANDARD,
        info: ModelInfo = this.info,
        cacheKey: Boolean = false,
    ): ChatRequest = ChatRequest(request, info, flavor, cacheKey)

    private fun body(request: ModelRequest, flavor: ChatFlavor = ChatFlavor.STANDARD): JsonObject =
        chat(request, flavor).body

    private fun JsonObject.messages(): List<JsonObject> = this["messages"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    @Test
    fun `instructions become one system message and entries keep their order`() {
        val history = listOf(
            SummaryEntry(null, "Earlier: we planned a trip.", EntryId(3)),
            user("Hello"),
            NoticeEntry(null, "Something failed.", NoticeKind.FAILED),
            AssistantEntry(null, listOf(TextPart("Hi.")), model),
            user("And?"),
            user("Anyone?"),
        )
        val sections = listOf(PromptSection("role", "You help.", true), PromptSection("time", "It is noon.", false))

        val messages = body(request(history) { instructions(sections) }).messages()

        assertEquals(
            listOf(
                "system" to "You help.\n\nIt is noon.",
                "user" to "Earlier: we planned a trip.\n\nHello",
                "assistant" to "Hi.",
                "user" to "And?\n\nAnyone?",
            ),
            messages.map { it.text("role") to it.text("content") },
        )
    }

    @Test
    fun `a flavor gives each call back the vendor fields it kept of it, in its own dialect alone`() {
        val dialect = Dialect("vendor")
        val vendor = ChatFlavor(
            dialect,
            tools = object : ChatTools() {
                override fun writeCall(call: ToolCallPart, kept: JsonObject, element: JsonObjectBuilder) {
                    kept["extra_content"]?.let { element.put("extra_content", it) }
                }
            },
        )
        val kept = buildJsonObject { putJsonObject("extra_content") { put("signature", "sig-1") } }
        val signed = ToolCallPart(call.id, call.name, "{}", ProviderData.EMPTY.with(dialect, kept))
        val history = listOf(
            user("Note milk."),
            AssistantEntry(null, listOf(signed), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded),
        )
        val tools: ModelRequest.Builder.() -> Unit = { tools(listOf(testToolDefinition("notes.add"))) }

        val sent = body(request(history, block = tools), vendor).messages()[1]["tool_calls"]!!.jsonArray.single()
        val other = body(request(history, block = tools)).messages()[1]["tool_calls"]!!.jsonArray.single()

        assertEquals(kept["extra_content"], sent.jsonObject["extra_content"])
        assertNull(other.jsonObject["extra_content"])
    }

    @Test
    fun `a tool round goes out as calls, results and a follow-up after them`() {
        val history = listOf(
            user("Note milk."),
            AssistantEntry(null, listOf(TextPart("Saving."), call), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded),
            user("Also eggs."),
        )

        val messages = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()

        val assistant = messages[1]
        assertEquals("Saving.", assistant.text("content"))
        val sent = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", sent.text("id"))
        assertEquals("notes-add", sent["function"]!!.jsonObject.text("name"))
        assertEquals("{}", sent["function"]!!.jsonObject.text("arguments"))
        val result = messages[2]
        assertEquals(
            listOf("tool", "call_1", "Saved."),
            listOf("role", "tool_call_id", "content").map {
                result.text(it)
            },
        )
        assertEquals("user" to "Also eggs.", messages[3].text("role") to messages[3].text("content"))
    }

    @Test
    fun `turn context goes after everything persisted and never into an earlier message`() {
        val first = listOf(user("Plan the trip."))
        val round0 = body(request(first) { turnContext(listOf(context)) }).messages()
        val afterTools = first + AssistantEntry(null, listOf(call), model) +
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded)
        val tools = listOf(testToolDefinition("notes.add"))
        val build = { body(request(afterTools, round = 1) { turnContext(listOf(context)).tools(tools) }) }
        val round1 = build().messages()
        val nextTurn = afterTools + AssistantEntry(null, listOf(TextPart("Done.")), model) + user("Thanks.")
        val next = body(request(nextTurn) { tools(tools) }).messages()

        assertEquals("Plan the trip.\n\nRecalled: the user likes tea.", round0.single().text("content"))
        assertEquals("Plan the trip.", round1.first().text("content"))
        assertEquals(
            listOf("user", "Recalled: the user likes tea."),
            listOf("role", "content").map {
                round1.last().text(it)
            },
        )
        assertEquals(round1.dropLast(1), next.take(round1.size - 1))
        assertFalse(next.any { "Recalled" in it.toString() })
        assertEquals(build().toString(), build().toString())
    }

    @Test
    fun `a request that offers no tools carries its tool rounds as text`() {
        val history = listOf(
            user("Note milk."),
            AssistantEntry(null, listOf(TextPart("Saving."), call), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved.")), ToolOutcome.Failed),
            user("Also eggs."),
        )

        val messages = body(request(history)).messages()

        assertEquals(
            listOf(
                "user" to "Note milk.",
                "assistant" to "Saving.\n\n[Called tool notes-add with {}]",
                "user" to "[Result of tool notes-add, which failed]\nSaved.\n\nAlso eggs.",
            ),
            messages.map { it.text("role") to it.text("content") },
        )
        assertNull(messages[1]["tool_calls"])
    }

    @Test
    fun `a call whose result is gone and a result whose call is gone go out as text`() {
        val lost = ToolCallPart(ToolCallId("call_2"), "notes.add", "{}")
        val read = listOf(TextPart("Read."))
        val orphan = ToolResultEntry(null, ToolCallId("call_3"), "files.read", read, ToolOutcome.Succeeded)
        val history = listOf(
            user("Note milk and eggs."),
            AssistantEntry(null, listOf(TextPart("Saving."), call, lost), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded),
            orphan,
            user("Thanks."),
        )

        val messages = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()

        assertEquals(listOf("user", "assistant", "tool", "user"), messages.map { it.text("role") })
        assertEquals("Saving.\n\n[Called tool notes-add with {}]", messages[1].text("content"))
        assertEquals(listOf("call_1"), messages[1]["tool_calls"]!!.jsonArray.map { it.jsonObject.text("id") })
        assertEquals("[Result of tool files-read]\nRead.\n\nThanks.", messages[3].text("content"))
    }

    @Test
    fun `ids and names the API does not take go out in one form for a call and its result, each its own`() {
        val ids = listOf("functions.fs-read:0", "call 1", "a".repeat(41), "X1")
        val calls = ids.map { ToolCallPart(ToolCallId(it), "Weird Name", "{}") }
        val history = listOf(user("Go."), AssistantEntry(null, calls, model)) +
            calls.map { ToolResultEntry(null, it.id, it.name, listOf(TextPart("Done.")), ToolOutcome.Succeeded) }

        val messages = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()

        val sent = messages[1]["tool_calls"]!!.jsonArray.map { it.jsonObject }
        val wireIds = sent.map { it.text("id")!! }
        assertEquals(wireIds, messages.drop(2).map { it.text("tool_call_id") })
        assertEquals("functions.fs-read:0", wireIds.first())
        assertEquals(4, wireIds.distinct().size)
        assertTrue(wireIds.drop(1).all { Regex("X[0-9a-f]{32}").matches(it) }, "$wireIds")
        val name = sent.map { it["function"]!!.jsonObject.text("name")!! }.distinct().single()
        assertTrue(Regex("X[0-9a-f]{32}").matches(name), name)
    }

    @Test
    fun `images go inline as data URLs to a model that takes them and as placeholders to others`() {
        val image = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2, 3)), "dot.png")
        val parts = listOf(ContextPart("framing", "From Ada:"), TextPart("Look."), image)
        val entry = UserEntry(null, parts, user("x").origin)
        val plain = info.toBuilder().inputMedia(emptySet()).build()

        val seen = body(request(listOf(entry))).messages().single()["content"]!!.jsonArray.map { it.jsonObject }
        val blind = chat(request(listOf(entry)), info = plain)
        val unseen = blind.body.messages().single().text("content")

        assertEquals(listOf("text", "text", "image_url"), seen.map { it.text("type") })
        assertEquals("data:image/png;base64,AQID", seen[2]["image_url"]!!.jsonObject.text("url"))
        assertEquals("From Ada:\n\nLook.\n\n[image dot.png not shown: the model cannot take it]", unseen)
        assertEquals(listOf("unsupported_media"), blind.warnings.map { it.kind.id })
    }

    @Test
    fun `tools, tool choice and options map onto their fields`() {
        val options = ModelOptions.builder().maxOutputTokens(512).temperature(0.2).topP(0.9)
            .stopSequences(listOf("END")).parallelToolCalls(false).build()
        val tools = listOf(testToolDefinition("notes.add"), testToolDefinition("files.read"))

        val body = body(
            request(listOf(user("Hi"))) { tools(tools).toolChoice(ToolChoice.Named("files.read")).options(options) },
        )

        val names = body["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject.text("name") }
        assertEquals(listOf("notes-add", "files-read"), names)
        assertEquals("files-read", body["tool_choice"]!!.jsonObject["function"]!!.jsonObject.text("name"))
        assertEquals("false", body.text("parallel_tool_calls"))
        assertEquals(listOf("512", "0.2", "0.9"), listOf("max_tokens", "temperature", "top_p").map { body.text(it) })
        assertEquals(JsonArray(listOf(JsonPrimitive("END"))), body["stop"])
        assertEquals("true", body.text("stream"))
        assertEquals("true", body["stream_options"]!!.jsonObject.text("include_usage"))
    }

    @Test
    fun `automatic tool choice is left to the backend and no tools mean no tool fields`() {
        val auto = body(request(listOf(user("Hi"))) { tools(listOf(testToolDefinition("notes.add"))) })
        val required = body(
            request(listOf(user("Hi"))) {
                tools(listOf(testToolDefinition("notes.add"))).toolChoice(ToolChoice.Required)
            },
        )
        val none = body(request(listOf(user("Hi"))))

        assertNull(auto["tool_choice"])
        assertEquals("required", required.text("tool_choice"))
        assertEquals(listOf("model", "messages", "stream", "stream_options"), none.keys.toList())
    }

    @Test
    fun `the standard API asks for an effort the model takes and warns about one it does not`() {
        fun effort(effort: ReasoningEffort): ChatRequest =
            chat(request(listOf(user("Hi"))) { options(ModelOptions.builder().reasoning(effort).build()) })

        assertEquals("high", effort(ReasoningEffort.HIGH).body.text("reasoning_effort"))
        val refused = effort(ReasoningEffort.MAX)
        assertNull(refused.body["reasoning_effort"])
        assertEquals(listOf("unsupported_option"), refused.warnings.map { it.kind.id })
    }

    @Test
    fun `the cache key goes out only when configured`() {
        val keyed = request(listOf(user("Hi"))) { cacheKey("chat-42") }

        assertNull(chat(keyed).body["prompt_cache_key"])
        assertEquals("chat-42", chat(keyed, cacheKey = true).body.text("prompt_cache_key"))
    }

    @Test
    fun `the standard API sends no reasoning back`() {
        val seal = ReasoningSeal(model, ChatFlavor.OPENAI_CHAT, SealKind.PLAIN, "Think.")
        val history = listOf(
            user("Hi"),
            AssistantEntry(null, listOf(ReasoningPart("Think.", null, seal), TextPart("Hello.")), model),
            user("Again"),
        )

        assertEquals(listOf("role", "content"), body(request(history)).messages()[1].keys.toList())
    }

    @Test
    fun `a flavor's parts write the reasoning, tool choice, cache key and replay fields`() {
        val seal = ReasoningSeal(model, Dialect("custom"), SealKind.PLAIN, "Think.")
        val history = listOf(
            user("Hi"),
            AssistantEntry(null, listOf(ReasoningPart("Think.", null, seal), TextPart("Hello.")), model),
            user("Again"),
        )
        val options = ModelOptions.builder().reasoning(ReasoningEffort.LOW).parallelToolCalls(true).build()

        val chat = chat(
            request(history) {
                tools(listOf(testToolDefinition("notes.add"))).toolChoice(ToolChoice.Required).options(options)
                    .cacheKey("chat-42")
            },
            custom,
            cacheKey = true,
        )

        assertEquals("low", chat.body.text("thinking"))
        assertNull(chat.body["reasoning_effort"])
        assertNull(chat.body["tool_choice"])
        assertNull(chat.body["parallel_tool_calls"])
        assertEquals("chat-42" to null, chat.body.text("session") to chat.body["prompt_cache_key"])
        assertEquals("Think.", chat.body.messages()[1].text("thoughts"))
        assertEquals(listOf("No choice here."), chat.warnings.map { it.message })
    }

    @Test
    fun `a backend without the parallel field cannot be asked to call tools one at a time`() {
        val options = ModelOptions.builder().parallelToolCalls(false).build()

        val chat = chat(
            request(listOf(user("Hi"))) { tools(listOf(testToolDefinition("notes.add"))).options(options) },
            custom,
        )

        assertNull(chat.body["parallel_tool_calls"])
        assertTrue(chat.warnings.any { "cannot keep it from calling tools in parallel" in it.message })
    }
}
