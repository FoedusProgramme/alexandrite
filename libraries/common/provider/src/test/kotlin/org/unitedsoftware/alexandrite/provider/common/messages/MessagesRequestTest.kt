package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.OpaquePart
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesRequestTest {
    private val turn = testTurn()
    private val model = ModelRef(EndpointId("test"), "claude-model")
    private val info = ModelInfo.builder("claude-model", MessagesFlavor.ANTHROPIC)
        .maxOutputTokens(64000)
        .nativeTools(true)
        .parallelToolCalls(true)
        .inputMedia(setOf(MediaKind.IMAGE))
        .reasoningEfforts(setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH))
        .streaming(true)
        .build()
    private val call = ToolCallPart(ToolCallId("toolu_1"), "notes.add", "{\"text\":\"milk\"}")
    private val recall = TurnContextItem("recall", "Recalled: the user likes tea.", Trust.TRUSTED)

    private fun user(text: String) = testUserEntry(turn, text)

    private fun request(
        history: List<TranscriptEntry>,
        round: Int = 0,
        block: ModelRequest.Builder.() -> Unit = {},
    ): ModelRequest = ModelRequest.builder(model, history, 0, RequestIds(turn.conversation, turn.id, round))
        .apply(block)
        .build()

    private fun messages(
        request: ModelRequest,
        info: ModelInfo = this.info,
        settings: MessagesSettings = MessagesSettings(),
        mode: (Trust) -> TurnContextMode = { TurnContextMode.TRANSIENT },
    ): MessagesRequest =
        MessagesRequest(request, info, MessagesFlavor.STANDARD, settings, TurnContextSetting.TRANSIENT, mode)

    private fun body(request: ModelRequest, info: ModelInfo = this.info): JsonObject = messages(request, info).body

    private fun JsonObject.messages(): List<JsonObject> = this["messages"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.blocks(): List<JsonObject> = this["content"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.cached(): Boolean = this["cache_control"] != null

    private fun result(text: String, outcome: ToolOutcome = ToolOutcome.Succeeded) =
        ToolResultEntry(null, call.id, call.name, listOf(TextPart(text)), outcome)

    @Test
    fun `system sections become text blocks whose leading stable run ends in a breakpoint`() {
        val sections = listOf(
            PromptSection("role", "You help.", true),
            PromptSection("rules", "Be brief.", true),
            PromptSection("time", "It is noon.", false),
            PromptSection("empty", "", true),
        )

        val body = body(request(listOf(user("Hi"))) { instructions(sections).tools(listOf(testToolDefinition())) })

        val system = body["system"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("You help.", "Be brief.", "It is noon."), system.map { it.text("text") })
        assertEquals(listOf(false, true, false), system.map { it.cached() })
        assertFalse(body["tools"]!!.jsonArray.single().jsonObject.cached())
    }

    @Test
    fun `without a stable section the last tool ends the static prefix`() {
        val sections = listOf(PromptSection("time", "It is noon.", false))
        val tools = listOf(testToolDefinition("notes.add"), testToolDefinition("files.read"))

        val body = body(request(listOf(user("Hi"))) { instructions(sections).tools(tools) })

        val sent = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("notes-add", "files-read"), sent.map { it.text("name") })
        assertEquals(listOf(false, true), sent.map { it.cached() })
        assertEquals(testToolDefinition().parameters, sent.first()["input_schema"])
        assertFalse(body["system"]!!.jsonArray.single().jsonObject.cached())
    }

    @Test
    fun `entries keep their order, consecutive user content merges and notices stay out`() {
        val history = listOf(
            SummaryEntry(null, "Earlier: we planned a trip.", EntryId(3)),
            user("Hello"),
            NoticeEntry(null, "Something failed.", NoticeKind.FAILED),
            AssistantEntry(null, listOf(TextPart("Hi."), TextPart("")), model),
            user("And?"),
            UserEntry(null, listOf(ContextPart("framing", "From Ada:"), TextPart("Anyone?")), user("x").origin),
        )

        val messages = body(request(history)).messages()

        assertEquals(listOf("user", "assistant", "user"), messages.map { it.text("role") })
        assertEquals(
            listOf(
                listOf("Earlier: we planned a trip.", "Hello"),
                listOf("Hi."),
                listOf("And?", "From Ada:", "Anyone?"),
            ),
            messages.map { message -> message.blocks().map { it.text("text") } },
        )
        assertEquals(listOf(true, false, true), messages.map { it.blocks().last().cached() })
    }

    @Test
    fun `a tool round goes out as tool_use and tool_result blocks, a follow-up after the results`() {
        val image = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2, 3)), "dot.png")
        val failed = ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved."), image), ToolOutcome.Failed)
        val history = listOf(
            user("Note milk."),
            AssistantEntry(null, listOf(TextPart("Saving."), call), model),
            failed,
            user("Also eggs."),
        )

        val messages = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()

        val use = messages[1].blocks()[1]
        assertEquals(listOf("tool_use", "toolu_1", "notes-add"), listOf("type", "id", "name").map { use.text(it) })
        assertEquals("""{"text":"milk"}""", use["input"].toString())
        val (result, followUp) = messages[2].blocks()
        assertEquals("toolu_1" to "true", result.text("tool_use_id") to result.text("is_error"))
        val content = result["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image"), content.map { it.text("type") })
        assertEquals("AQID", content[1]["source"]!!.jsonObject.text("data"))
        assertEquals("Also eggs.", followUp.text("text"))
        assertTrue(followUp.cached())
    }

    @Test
    fun `a call whose arguments are no object goes back with empty input`() {
        val broken = ToolCallPart(ToolCallId("toolu_2"), "notes.add", "{not json")
        val result = ToolResultEntry(null, broken.id, "notes.add", listOf(TextPart("Done.")), ToolOutcome.Succeeded)
        val history = listOf(user("Go."), AssistantEntry(null, listOf(broken), model), result)

        val use = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()[1].blocks()
            .single()

        assertEquals("notes-add" to "{}", use.text("name") to use["input"].toString())
    }

    @Test
    fun `ids and names the API does not take go out in one form for a call and its result, each its own`() {
        val ids = listOf("functions.fs-read:0", "functions.fs-read:1", "X0", "toolu_9")
        val calls = ids.map { ToolCallPart(ToolCallId(it), "Weird Name", "{}") }
        val history = listOf(user("Go."), AssistantEntry(null, calls, model)) +
            calls.map { ToolResultEntry(null, it.id, it.name, listOf(TextPart("Done.")), ToolOutcome.Succeeded) }

        val messages = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()

        val uses = messages[1].blocks()
        val results = messages[2].blocks()
        val sent = uses.map { it.text("id")!! }
        assertEquals(sent, results.map { it.text("tool_use_id") })
        assertEquals(4, sent.distinct().size)
        assertEquals("toolu_9", sent.last())
        assertTrue(sent.all { Regex("[A-Za-z0-9_-]+").matches(it) }, "$sent")
        assertTrue(sent.take(3).all { it.startsWith("X") && it.length == 33 }, "$sent")
        val name = uses.map { it.text("name")!! }.distinct().single()
        assertTrue(Regex("X[0-9a-f]{32}").matches(name), name)
    }

    @Test
    fun `a request that offers no tools carries its tool rounds as text`() {
        val image = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2, 3)), "dot.png")
        val history = listOf(
            user("Note milk."),
            AssistantEntry(null, listOf(TextPart("Saving."), call), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved."), image), ToolOutcome.Failed),
            user("Also eggs."),
        )

        val messages = body(request(history)).messages()

        assertFalse("tool_use" in messages.toString() || "tool_result" in messages.toString())
        assertEquals(
            listOf("Saving.", """[Called tool notes-add with {"text":"milk"}]"""),
            messages[1].blocks().map { it.text("text") },
        )
        assertEquals(
            listOf("text", "text", "image", "text"),
            messages[2].blocks().map { it.text("type") },
        )
        assertEquals(
            listOf("[Result of tool notes-add, which failed]", "Saved.", null, "Also eggs."),
            messages[2].blocks().map { it.text("text") },
        )
    }

    @Test
    fun `a call whose result is gone and a result whose call is gone go out as text`() {
        val lost = ToolCallPart(ToolCallId("toolu_2"), "notes.add", "{}")
        val read = listOf(TextPart("Read."))
        val orphan = ToolResultEntry(null, ToolCallId("toolu_3"), "files.read", read, ToolOutcome.Succeeded)
        val history = listOf(
            user("Note milk and eggs."),
            AssistantEntry(null, listOf(TextPart("Saving."), call, lost), model),
            result("Saved."),
            orphan,
            user("Thanks."),
        )

        val messages = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()

        assertEquals(
            listOf(listOf("text"), listOf("text", "tool_use", "text"), listOf("tool_result", "text", "text", "text")),
            messages.map { message -> message.blocks().map { it.text("type") } },
        )
        assertEquals("toolu_1", messages[1].blocks()[1].text("id"))
        assertEquals("[Called tool notes-add with {}]", messages[1].blocks()[2].text("text"))
        assertEquals(
            listOf("[Result of tool files-read]", "Read.", "Thanks."),
            messages[2].blocks().drop(1).map { it.text("text") },
        )
    }

    @Test
    fun `turn context the endpoint cannot render is left out with a warning`() {
        val untrusted = TurnContextItem("quote", "Someone said hi.", Trust.UNTRUSTED)

        val built = messages(
            request(listOf(user("Hi"))) { turnContext(listOf(recall, untrusted)) },
            mode = { if (it == Trust.TRUSTED) TurnContextMode.TRANSIENT else TurnContextMode.NOT_SUPPORTED },
        )

        assertFalse("Someone said hi." in built.body.toString())
        assertTrue("Recalled" in built.body.toString())
        assertTrue(built.warnings.single().message.endsWith("leaves 'quote' out."))
    }

    @Test
    fun `kept turn context goes into a turn-scoped system message after the last user message`() {
        val built = messages(
            request(listOf(user("Hi"))) { turnContext(listOf(recall)) },
            settings = MessagesSettings(turnScopedSystem = true),
            mode = { TurnContextMode.KEPT_UNRENDERED },
        )

        val system = built.body.messages().last()
        assertEquals(
            """{"role":"system","clear_at":"next_user_message","content":"Recalled: the user likes tea."}""",
            "$system",
        )
        assertEquals(system, built.kept)
        assertEquals(setOf("mid-conversation-system-clear-at-2026-08-21"), built.betas)
        assertTrue(built.body.messages().first().blocks().last().cached())
    }

    @Test
    fun `a kept system message goes back before its response, and only where the endpoint takes such messages`() {
        val kept = MessagesTurnContext().message("Recalled.")
        val data = ProviderData.EMPTY.with(MessagesFlavor.ANTHROPIC, JsonObject(mapOf("turnContext" to kept)))
        val history = listOf(user("Hi"), AssistantEntry(null, listOf(TextPart("Hello.")), model, data), user("Bye"))

        val scoped = messages(request(history), settings = MessagesSettings(turnScopedSystem = true))
        val plain = messages(request(history))

        assertEquals(listOf("user", "system", "assistant", "user"), scoped.body.messages().map { it.text("role") })
        assertEquals(kept, scoped.body.messages()[1])
        assertEquals(setOf("mid-conversation-system-clear-at-2026-08-21"), scoped.betas)
        assertEquals(listOf("user", "assistant", "user"), plain.body.messages().map { it.text("role") })
        assertNull(scoped.kept)
    }

    @Test
    fun `thinking goes back unchanged to the model that wrote it and to no other`() {
        val other = ModelRef(EndpointId("test"), "other-model")
        val parts = listOf(
            ReasoningPart(null, null, ReasoningSeal(model, MessagesFlavor.ANTHROPIC, SealKind.SIGNATURE, "sig-1")),
            ReasoningPart("Hm.", null, ReasoningSeal(model, MessagesFlavor.ANTHROPIC, SealKind.SIGNATURE, "sig-2")),
            ReasoningPart(null, null, ReasoningSeal(model, MessagesFlavor.ANTHROPIC, SealKind.ENCRYPTED, "secret")),
            ReasoningPart("Other.", null, ReasoningSeal(other, MessagesFlavor.ANTHROPIC, SealKind.SIGNATURE, "sig-3")),
            ReasoningPart("Chat.", null, ReasoningSeal(model, Dialect("openai-chat"), SealKind.PLAIN, "Chat.")),
            ReasoningPart("Unsealed.", null, null),
            OpaquePart(MessagesFlavor.ANTHROPIC, "server_tool_use", JsonObject(mapOf("type" to JsonPrimitive("x")))),
            OpaquePart(Dialect("other"), "server_tool_use", JsonObject(emptyMap())),
            TextPart("Done."),
        )
        val history = listOf(user("Hi"), AssistantEntry(null, parts, model), user("Again"))

        val blocks = body(request(history)).messages()[1].blocks()

        assertEquals(
            listOf(
                """{"type":"thinking","thinking":"","signature":"sig-1"}""",
                """{"type":"thinking","thinking":"Hm.","signature":"sig-2"}""",
                """{"type":"redacted_thinking","data":"secret"}""",
                """{"type":"x"}""",
                """{"type":"text","text":"Done."}""",
            ),
            blocks.map { "$it" },
        )
    }

    @Test
    fun `earlier turns' thinking goes back only where requests keep turn context`() {
        val earlier = TurnId("turn-1")
        fun record(id: Long, turn: TurnId) = EntryRecord(EntryId(id), this.turn.conversation, turn, Instant.EPOCH)
        val seal = ReasoningSeal(model, MessagesFlavor.ANTHROPIC, SealKind.SIGNATURE, "sig")
        val history = listOf(
            user("Hi").withRecord(record(1, earlier)),
            AssistantEntry(record(2, earlier), listOf(ReasoningPart("Old.", null, seal), TextPart("Hi.")), model),
            user("Note milk.").withRecord(record(3, turn.id)),
            AssistantEntry(record(4, turn.id), listOf(ReasoningPart("New.", null, seal), call), model),
            result("Saved."),
        )
        fun thinking(settings: MessagesSettings, setting: TurnContextSetting): List<String?> =
            MessagesRequest(request(history, 1), info, MessagesFlavor.STANDARD, settings, setting) {
                TurnContextMode.TRANSIENT
            }.body.messages().flatMap { it.blocks() }.filter { it.text("type") == "thinking" }
                .map { it.text("thinking") }

        assertEquals(listOf("New."), thinking(MessagesSettings(), TurnContextSetting.TRANSIENT))
        assertEquals(
            listOf("Old.", "New."),
            thinking(MessagesSettings(turnScopedSystem = true), TurnContextSetting.TRANSIENT),
        )
        assertEquals(listOf("Old.", "New."), thinking(MessagesSettings(), TurnContextSetting.BAKE))
    }

    @Test
    fun `images go inline as base64 to a model that takes them and as placeholders to others`() {
        val png = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2, 3)), "dot.png")
        val bmp = MediaPart(MediaKind.IMAGE, "image/bmp", InlineMedia(byteArrayOf(4)), "old.bmp")
        val entry = UserEntry(null, listOf(TextPart("Look."), png, bmp), user("x").origin)
        val plain = info.toBuilder().inputMedia(emptySet()).build()

        val seen = messages(request(listOf(entry)))
        val blind = messages(request(listOf(entry)), plain)

        val blocks = seen.body.messages().single().blocks()
        assertEquals(listOf("text", "image", "text"), blocks.map { it.text("type") })
        assertEquals(
            """{"type":"base64","media_type":"image/png","data":"AQID"}""",
            "${blocks[1]["source"]}",
        )
        assertEquals("[image old.bmp not shown: the model cannot take it]", blocks[2].text("text"))
        assertEquals(listOf("unsupported_media"), seen.warnings.map { it.kind.id })
        assertEquals(2, blind.warnings.size)
    }

    @Test
    fun `efforts map onto adaptive thinking, NONE onto thinking off, and one the model lacks onto a warning`() {
        fun effort(effort: ReasoningEffort): MessagesRequest =
            messages(request(listOf(user("Hi"))) { options(ModelOptions.builder().reasoning(effort).build()) })

        val high = effort(ReasoningEffort.HIGH).body
        assertEquals("""{"type":"adaptive","display":"summarized"}""", "${high["thinking"]}")
        assertEquals("""{"effort":"high"}""", "${high["output_config"]}")
        assertEquals("""{"type":"disabled"}""", "${effort(ReasoningEffort.NONE).body["thinking"]}")
        val max = effort(ReasoningEffort.MAX)
        assertNull(max.body["thinking"])
        assertNull(max.body["output_config"])
        assertEquals(listOf("unsupported_option"), max.warnings.map { it.kind.id })
        assertNull(body(request(listOf(user("Hi"))))["thinking"])
    }

    @Test
    fun `a forced tool choice goes out with thinking off, and as auto while the model may think`() {
        val tools = listOf(testToolDefinition("notes.add"), testToolDefinition("files.read"))
        fun choice(choice: ToolChoice, effort: ReasoningEffort? = null, info: ModelInfo = this.info) = messages(
            request(listOf(user("Hi"))) {
                tools(tools).toolChoice(choice).options(ModelOptions.builder().reasoning(effort).build())
            },
            info,
        )
        val unthinking = info.toBuilder().reasoningEfforts(emptySet()).build()
        val alwaysThinking = info.toBuilder().reasoningEfforts(setOf(ReasoningEffort.HIGH)).build()

        val open = choice(ToolChoice.Named("files.read")).body
        assertEquals("""{"type":"tool","name":"files-read"}""", "${open["tool_choice"]}")
        assertEquals("""{"type":"disabled"}""", "${open["thinking"]}")
        val thinking = choice(ToolChoice.Required, ReasoningEffort.HIGH)
        assertNull(thinking.body["tool_choice"])
        assertEquals("high", thinking.body["output_config"]!!.jsonObject.text("effort"))
        assertTrue(thinking.warnings.single().message.contains("no forced tool choice"))
        assertNull(choice(ToolChoice.Required, info = alwaysThinking).body["tool_choice"])
        val plain = choice(ToolChoice.Required, info = unthinking).body
        assertEquals("""{"type":"any"}""", "${plain["tool_choice"]}")
        assertNull(plain["thinking"])
        assertEquals("""{"type":"none"}""", "${choice(ToolChoice.None).body["tool_choice"]}")
        assertNull(choice(ToolChoice.Auto).body["tool_choice"])
    }

    @Test
    fun `calls one at a time ride on the tool choice`() {
        val tools = listOf(testToolDefinition("notes.add"))
        fun parallel(parallel: Boolean, choice: ToolChoice = ToolChoice.Auto, info: ModelInfo = this.info) = messages(
            request(listOf(user("Hi"))) {
                tools(tools).toolChoice(choice).options(ModelOptions.builder().parallelToolCalls(parallel).build())
            },
            info,
        )

        assertEquals(
            """{"type":"auto","disable_parallel_tool_use":true}""",
            "${parallel(false).body["tool_choice"]}",
        )
        assertEquals("""{"type":"none"}""", "${parallel(false, ToolChoice.None).body["tool_choice"]}")
        assertNull(parallel(true).body["tool_choice"])
        val single = parallel(true, info = info.toBuilder().parallelToolCalls(false).build())
        assertEquals(listOf("unsupported_option"), single.warnings.map { it.kind.id })
    }

    @Test
    fun `sampling options go out, but temperature and a low top-p not while the model thinks`() {
        fun sampled(effort: ReasoningEffort?, topP: Double) = messages(
            request(listOf(user("Hi"))) {
                options(
                    ModelOptions.builder().temperature(0.2).topP(topP).stopSequences(listOf("END")).reasoning(effort)
                        .build(),
                )
            },
        )

        val plain = sampled(null, 0.9).body
        assertEquals(listOf("0.2", "0.9"), listOf("temperature", "top_p").map { plain.text(it) })
        assertEquals(JsonArray(listOf(JsonPrimitive("END"))), plain["stop_sequences"])
        val thinking = sampled(ReasoningEffort.LOW, 0.9)
        assertNull(thinking.body["temperature"])
        assertNull(thinking.body["top_p"])
        assertEquals(2, thinking.warnings.size)
        assertEquals("0.97", sampled(ReasoningEffort.LOW, 0.97).body.text("top_p"))
        assertEquals("0.2", sampled(ReasoningEffort.NONE, 0.9).body.text("temperature"))
    }

    @Test
    fun `max_tokens is the request's, else the model's limit, else a default, and never above the limit`() {
        fun asked(max: Int?, info: ModelInfo = this.info) =
            messages(request(listOf(user("Hi"))) { options(ModelOptions.builder().maxOutputTokens(max).build()) }, info)
        val unknown = info.toBuilder().maxOutputTokens(null).build()

        assertEquals("1000", asked(1000).body.text("max_tokens"))
        assertEquals("64000", asked(null).body.text("max_tokens"))
        assertEquals("4096", asked(null, unknown).body.text("max_tokens"))
        val clamped = asked(100000)
        assertEquals("64000", clamped.body.text("max_tokens"))
        assertEquals(listOf("unsupported_option"), clamped.warnings.map { it.kind.id })
    }

    @Test
    fun `prompt caching off marks no breakpoint`() {
        val sections = listOf(PromptSection("role", "You help.", true))

        val built = messages(
            request(listOf(user("Hi"))) { instructions(sections) },
            settings = MessagesSettings(promptCaching = false, betas = listOf("some-beta-2026-01-01")),
        )

        assertFalse("cache_control" in built.body.toString())
        assertEquals(setOf("some-beta-2026-01-01"), built.betas)
    }
}
