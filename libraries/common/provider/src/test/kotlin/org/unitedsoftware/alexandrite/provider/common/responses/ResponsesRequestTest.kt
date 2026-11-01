package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ProviderData
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
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
import kotlin.test.assertTrue

class ResponsesRequestTest {
    private val turn = testTurn()
    private val model = ModelRef(EndpointId("test"), "gpt-model")
    private val dialect = ResponsesFlavor.OPENAI_RESPONSES
    private val info = ModelInfo.builder("gpt-model", dialect)
        .maxOutputTokens(64000)
        .nativeTools(true)
        .parallelToolCalls(true)
        .inputMedia(setOf(MediaKind.IMAGE))
        .reasoningEfforts(setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH))
        .streaming(true)
        .build()
    private val plain = ModelInfo.builder("plain-model", dialect).nativeTools(true).streaming(true).build()
    private val tools = listOf(testToolDefinition("notes.add"), testToolDefinition("files.read"))
    private val image = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2, 3)), "dot.png")

    private fun request(history: List<TranscriptEntry>, block: ModelRequest.Builder.() -> Unit = {}): ModelRequest =
        ModelRequest.builder(model, history, 0, RequestIds(turn.conversation, turn.id, 0)).apply(block).build()

    private fun built(
        request: ModelRequest,
        info: ModelInfo = this.info,
        settings: ResponsesSettings = ResponsesSettings(),
    ): ResponsesRequest = ResponsesRequest(request, info, ResponsesFlavor.STANDARD, settings)

    private fun JsonObject.input(): List<JsonObject> = this["input"]!!.jsonArray.map { it.jsonObject }

    private fun seal(data: String, origin: ModelRef = model): ReasoningSeal {
        val summary = Json.parseToJsonElement("""[{"type":"summary_text","text":"S"}]""")
        val kept = buildJsonObject { put("summary", summary) }
        return ReasoningSeal(origin, dialect, SealKind.ENCRYPTED, data, ProviderData.EMPTY.with(dialect, kept))
    }

    private fun call(id: String, name: String, arguments: String) = ToolCallPart(ToolCallId(id), name, arguments)

    private fun result(call: ToolCallPart, text: String) =
        ToolResultEntry(null, call.id, call.name, listOf(TextPart(text)), ToolOutcome.Succeeded)

    @Test
    fun `a round trip with reasoning, parallel calls, media and turn context becomes the expected body`() {
        val first = call("call_a", "notes.add", "{\"text\":\"milk\"}")
        val second = call("call_b", "files.read", "")
        val assistant = AssistantEntry(
            null,
            listOf(ReasoningPart(null, "S", seal("enc+/=")), TextPart("Let me look."), first, second),
            model,
        )
        val history = listOf(
            UserEntry(null, listOf(TextPart("What is this?"), image), testUserEntry(turn).origin),
            assistant,
            result(first, "Added."),
            ToolResultEntry(null, second.id, second.name, listOf(TextPart("Text"), image), ToolOutcome.Failed),
            testUserEntry(turn, "And then?"),
        )
        val request = request(history) {
            instructions(listOf(PromptSection("role", "You help.", true), PromptSection("time", "Noon.", false)))
            turnContext(listOf(TurnContextItem("recall", "Recalled: tea.")))
            tools(tools).toolChoice(ToolChoice.Named("files.read"))
            options(
                ModelOptions.builder().reasoning(ReasoningEffort.HIGH).maxOutputTokens(500).parallelToolCalls(false)
                    .build(),
            )
            cacheKey("cache-1")
        }

        val built = built(request)

        val expected = """
            {"model":"gpt-model","instructions":"You help.\n\nNoon.","input":[
              {"type":"message","role":"user","content":[{"type":"input_text","text":"What is this?"},
                {"type":"input_image","image_url":"data:image/png;base64,AQID","detail":"auto"}]},
              {"type":"reasoning","summary":[{"type":"summary_text","text":"S"}],"encrypted_content":"enc+/="},
              {"type":"message","role":"assistant","content":"Let me look."},
              {"type":"function_call","call_id":"call_a","name":"notes-add","arguments":"{\"text\":\"milk\"}"},
              {"type":"function_call","call_id":"call_b","name":"files-read","arguments":"{}"},
              {"type":"function_call_output","call_id":"call_a","output":"Added."},
              {"type":"function_call_output","call_id":"call_b","output":[{"type":"input_text","text":"Text"},
                {"type":"input_image","image_url":"data:image/png;base64,AQID","detail":"auto"}]},
              {"type":"message","role":"user","content":[{"type":"input_text","text":"And then?"},
                {"type":"input_text","text":"Recalled: tea."}]}],
             "tools":[
              {"type":"function","name":"notes-add","description":"A tool of the test.","parameters":{"type":"object"},
                "strict":false},
              {"type":"function","name":"files-read","description":"A tool of the test.","parameters":{"type":"object"},
                "strict":false}],
             "tool_choice":{"type":"function","name":"files-read"},"parallel_tool_calls":false,
             "reasoning":{"effort":"high","summary":"auto"},"include":["reasoning.encrypted_content"],
             "max_output_tokens":500,"prompt_cache_key":"cache-1","store":false,"stream":true}
        """.trimIndent()
        assertEquals(Json.parseToJsonElement(expected), built.body)
        assertEquals(emptyList(), built.warnings)
    }

    @Test
    fun `reasoning goes back only to the model that wrote it`() {
        val other = ModelRef(EndpointId("test"), "other-model")
        val history = listOf(
            testUserEntry(turn, "Hi"),
            AssistantEntry(null, listOf(ReasoningPart(null, null, seal("mine")), TextPart("A")), model),
            testUserEntry(turn, "Again"),
            AssistantEntry(null, listOf(ReasoningPart(null, null, seal("theirs", other)), TextPart("B")), other),
            testUserEntry(turn, "Once more"),
        )

        val input = built(request(history)).body.input()

        val sent = input.filter { it["type"] == JsonPrimitive("reasoning") }.map { it["encrypted_content"] }
        assertEquals(listOf(JsonPrimitive("mine")), sent)
        assertEquals(6, input.size)
    }

    @Test
    fun `calls without results and results without offered tools go as text`() {
        val call = call("call_a", "notes.add", "{}")
        val history = listOf(
            testUserEntry(turn, "Hi"),
            AssistantEntry(null, listOf(TextPart("Sure."), call), model),
            result(call, "Added."),
        )

        val input = built(request(history)).body.input()

        assertEquals(
            listOf("user", "assistant", "user"),
            input.map { (it["role"] as JsonPrimitive).content },
        )
        assertEquals(JsonPrimitive("Sure.\n\n[Called tool notes-add with {}]"), input[1]["content"])
        assertTrue("[Result of tool notes-add]" in input[2].toString())
    }

    @Test
    fun `options a model does not take are left out with a warning`() {
        val options = ModelOptions.builder()
            .reasoning(ReasoningEffort.MEDIUM)
            .temperature(0.5)
            .topP(0.9)
            .stopSequences(listOf("END"))
            .maxOutputTokens(100000)
            .build()

        val built = built(request(listOf(testUserEntry(turn, "Hi"))) { options(options) })

        val body = built.body
        assertEquals(64000, (body["max_output_tokens"] as JsonPrimitive).content.toInt())
        assertFalse("temperature" in body || "top_p" in body || "stop" in body)
        assertEquals(buildJsonObject { put("summary", "auto") }, body["reasoning"])
        assertEquals(5, built.warnings.size, "${built.warnings}")
    }

    @Test
    fun `a model that does not reason is asked for no reasoning and takes sampling options`() {
        val options = ModelOptions.builder().reasoning(ReasoningEffort.HIGH).temperature(0.5).maxOutputTokens(4).build()
        val history = listOf(UserEntry(null, listOf(TextPart("Look"), image), testUserEntry(turn).origin))

        val built = built(request(history) { options(options) }, plain)

        val body = built.body
        assertFalse("reasoning" in body || "include" in body)
        assertEquals(JsonPrimitive(0.5), body["temperature"])
        assertEquals(JsonPrimitive(16), body["max_output_tokens"])
        assertTrue("not shown: the model cannot take it" in body.toString())
        assertEquals(
            listOf("unsupported_media", "unsupported_option", "unsupported_option"),
            built.warnings.map { it.kind.id }.sorted(),
        )
    }

    @Test
    fun `effort NONE turns reasoning off without a summary and lets sampling options through`() {
        val options = ModelOptions.builder().reasoning(ReasoningEffort.NONE).temperature(0.2).build()

        val body = built(request(listOf(testUserEntry(turn, "Hi"))) { options(options) }).body

        assertEquals(buildJsonObject { put("effort", "none") }, body["reasoning"])
        assertEquals(JsonPrimitive(0.2), body["temperature"])
    }

    @Test
    fun `settings leave out the cache key and the summary`() {
        val settings = ResponsesSettings(promptCacheKey = false, reasoningSummary = null)

        val body = built(request(listOf(testUserEntry(turn, "Hi"))) { cacheKey("k") }, settings = settings).body

        assertFalse("prompt_cache_key" in body || "reasoning" in body)
        assertTrue("include" in body)
    }

    @Test
    fun `a parallel call option the model cannot honour is warned about`() {
        val single = info.toBuilder().parallelToolCalls(false).build()
        val options = ModelOptions.builder().parallelToolCalls(true).build()

        val built = built(request(listOf(testUserEntry(turn, "Hi"))) { tools(tools).options(options) }, single)

        assertFalse("parallel_tool_calls" in built.body)
        assertEquals(listOf("unsupported_option"), built.warnings.map { it.kind.id })
    }
}
