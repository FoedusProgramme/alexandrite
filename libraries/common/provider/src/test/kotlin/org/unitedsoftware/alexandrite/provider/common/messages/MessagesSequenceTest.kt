package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.RecordedRequest
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The requests of a turn's tool loop and of the turn after it, as one conversation sends them. */
class MessagesSequenceTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val next = TurnId("turn-2")
    private val tool = testToolDefinition("notes.add")
    private val models = mapOf(
        "claude-model" to
            ModelConfig(reasoningEfforts = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH)),
    )
    private val thinking = ModelOptions.builder().reasoning(ReasoningEffort.HIGH).build()
    private val off = ModelOptions.builder().reasoning(ReasoningEffort.NONE).build()
    private var entryId = 1L

    @AfterTest
    fun close() {
        server.close()
    }

    /** A reply that thinks under [signature] when it is not null and then calls [tool] as [call]. */
    private fun calling(call: String, signature: String?): FakeResponse = sse(
        messageStart(),
        *thought(signature, "Call $call."),
        blockStart(1, toolUseBlock(call, "notes-add")),
        inputDelta(1, "{\"text\":\"milk\"}"),
        blockStop(1),
        messageDelta("tool_use"),
        messageStop(),
    )

    /** A reply that thinks under [signature] when it is not null and then answers. */
    private fun answering(signature: String?): FakeResponse = sse(
        messageStart(),
        *thought(signature, "Answer."),
        blockStart(1, textBlock()),
        textDelta(1, "Noted."),
        blockStop(1),
        messageDelta("end_turn"),
        messageStop(),
    )

    /** The thinking block at index 0 that [signature] seals, or an empty text block when it is null. */
    private fun thought(signature: String?, text: String): Array<JsonObject> = if (signature == null) {
        arrayOf(blockStart(0, textBlock()), blockStop(0))
    } else {
        arrayOf(blockStart(0, thinkingBlock()), thinkingDelta(0, text), signatureDelta(0, signature), blockStop(0))
    }

    private class Sent(val request: RecordedRequest, val reply: AssistantEntry, val asked: ModelRequest)

    private fun TranscriptEntry.stored(id: TurnId): TranscriptEntry =
        withRecord(EntryRecord(EntryId(entryId++), turn.conversation, id, Instant.EPOCH))

    /**
     * Rounds 0 to 2 of a turn with [first] as context and [options], whose replies think when [thinks], then the next
     * turn with [second] and [nextOptions].
     */
    private fun conversation(
        endpoint: MessagesEndpoint,
        first: TurnContextItem,
        second: TurnContextItem,
        options: ModelOptions,
        nextOptions: ModelOptions,
        thinks: Boolean,
    ): List<Sent> = runBlocking {
        endpoint.use {
            val history = mutableListOf(testUserEntry(turn, "Note milk.").stored(turn.id))
            val sent = mutableListOf<Sent>()
            for ((round, call) in listOf("toolu_1", "toolu_2").withIndex()) {
                val reply = calling(call, "sig-$round".takeIf { thinks })
                val calls = send(it, history, 0, turn.id, round, first, options, reply)
                val part = calls.reply.parts.filterIsInstance<ToolCallPart>().single()
                val result =
                    ToolResultEntry(null, part.id, part.name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded)
                history += listOf(calls.reply.stored(turn.id), result.stored(turn.id))
                sent += calls
            }
            val answer = send(it, history, 0, turn.id, 2, first, options, answering("sig-2".takeIf { thinks }))
            history += listOf(answer.reply.stored(turn.id), testUserEntry(turn, "Thanks.").stored(next))
            val after = send(it, history, history.lastIndex, next, 0, second, nextOptions, answering(null))
            sent + answer + after
        }
    }

    private suspend fun send(
        endpoint: MessagesEndpoint,
        history: List<TranscriptEntry>,
        turnStart: Int,
        id: TurnId,
        round: Int,
        context: TurnContextItem,
        options: ModelOptions,
        reply: FakeResponse,
    ): Sent {
        server.enqueue(reply, "/v1/messages")
        val request = ModelRequest.builder(
            ModelRef(EndpointId("test"), "claude-model"),
            history.toList(),
            turnStart,
            RequestIds(turn.conversation, id, round),
        ).tools(listOf(tool)).options(options).turnContext(listOf(context)).build()
        val completed = endpoint.events(request).last() as ModelEvent.Completed
        return Sent(server.requests.last(), completed.message, request)
    }

    private fun RecordedRequest.messages(): List<JsonObject> = messageList()

    private fun JsonObject.role(): String = (this["role"] as JsonPrimitive).content

    private fun JsonObject.blocks(): List<JsonObject> = (this["content"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun JsonElement.text(): String? = ((this as? JsonObject)?.get("text") as? JsonPrimitive)?.content

    private fun JsonObject.cached(): Boolean = this["cache_control"] != null

    private fun recall(text: String) = TurnContextItem("recall", text, Trust.TRUSTED)

    /** Each message's role and blocks, without the breakpoints. */
    private fun flat(request: RecordedRequest): List<Pair<String, JsonElement>> =
        request.messages().flatMap { message ->
            message.blocks().ifEmpty { listOf(message) }.map { message.role() to it.uncached() }
        }

    /** The role and blocks up to the request's last breakpoint, without the breakpoints. */
    private fun cachedPrefix(request: RecordedRequest): List<Pair<String, JsonElement>> {
        val blocks = request.messages().flatMap { message -> message.blocks().ifEmpty { listOf(message) } }
        return flat(request).take(blocks.indexOfLast { it.cached() } + 1)
    }

    private fun thinkingBlocks(request: RecordedRequest): List<String> =
        flat(request).map { it.second.toString() }.filter { "\"type\":\"thinking\"" in it }

    private fun assertExtends(requests: List<RecordedRequest>) {
        for ((before, after) in requests.zipWithNext()) {
            val previous = before.messages().map { it.uncached() }
            assertEquals(previous, after.messages().take(previous.size).map { it.uncached() })
        }
    }

    @Test
    fun `a thinking turn pins its context where it began and each request extends the one before`() {
        val endpoint = messagesEndpoint(server, models = models, discover = false)
        assertEquals(TurnContextMode.TRANSIENT, endpoint.turnContextMode("claude-model", thinking, Trust.TRUSTED))

        val sent = conversation(endpoint, recall("Likes tea."), recall("Likes cake."), thinking, thinking, true)

        val (round0, round1, round2, after) = sent.map { it.request }
        assertExtends(listOf(round0, round1, round2))
        for (request in listOf(round0, round1, round2)) {
            val (persisted, context) = request.messages().first().blocks()
            assertEquals("Note milk." to "Likes tea.", persisted.text() to context.text())
            assertTrue(persisted.cached() && !context.cached())
            assertEquals(1, flat(request).count { it.second.text() == "Likes tea." })
        }
        assertEquals(
            listOf(
                """{"type":"thinking","thinking":"Call toolu_1.","signature":"sig-0"}""",
                """{"type":"thinking","thinking":"Call toolu_2.","signature":"sig-1"}""",
            ),
            thinkingBlocks(round2),
        )
        assertEquals(emptyList(), thinkingBlocks(after))
        assertFalse("Likes tea." in after.body)
        val opening = after.messages().first().blocks()
        assertEquals(listOf("Note milk."), opening.map { it.text() })
        assertTrue(opening.single().cached())
        val last = after.messages().last().blocks()
        assertEquals(listOf("Thanks.", "Likes cake."), last.map { it.text() })
        assertTrue(last.first().cached())
        assertTrue(Regex("cache_control").findAll(after.body).count() <= 4)
    }

    @Test
    fun `a turn that cannot think moves its context behind the newest results`() {
        val endpoint = messagesEndpoint(server, models = models, discover = false)

        val sent = conversation(endpoint, recall("Likes tea."), recall("Likes cake."), off, off, false)

        val requests = sent.map { it.request }
        for ((before, after) in requests.zipWithNext()) {
            val cached = cachedPrefix(before)
            assertEquals(cached, flat(after).take(cached.size))
        }
        for ((request, text) in requests.zip(listOf("Likes tea.", "Likes tea.", "Likes tea.", "Likes cake."))) {
            val (role, last) = flat(request).last()
            assertEquals("user" to text, role to last.text())
            assertEquals(flat(request).size - 1, cachedPrefix(request).size)
            assertEquals(1, flat(request).count { it.second.text()?.startsWith("Likes") == true })
        }
        assertEquals(listOf("Note milk."), requests[1].messages().first().blocks().map { it.text() })
        assertEquals(
            listOf("tool_result", "text"),
            requests[1].messages().last().blocks().map { (it["type"] as JsonPrimitive).content },
        )
    }

    @Test
    fun `a turn that thinks followed by one that does not sends none of the earlier thinking`() {
        val endpoint = messagesEndpoint(server, models = models, discover = false)

        val sent = conversation(endpoint, recall("Likes tea."), recall("Likes cake."), thinking, off, true)

        val after = sent.last().request
        assertEquals(emptyList(), thinkingBlocks(after))
        assertFalse("Likes tea." in after.body)
        assertEquals("""{"type":"disabled"}""", after.json()["thinking"].toString())
        assertEquals(listOf("Thanks.", "Likes cake."), after.messages().last().blocks().map { it.text() })
        assertEquals(2, thinkingBlocks(sent[2].request).size)
    }

    @Test
    fun `turn-scoped context makes every request extend the one before, earlier thinking included`() {
        fun turnScoped() = messagesEndpoint(
            server,
            models = models,
            discover = false,
            messages = MessagesSettings(turnScopedSystem = true),
        )
        val endpoint = turnScoped()
        assertEquals(
            TurnContextMode.KEPT_UNRENDERED,
            endpoint.turnContextMode("claude-model", thinking, Trust.TRUSTED),
        )

        val sent = conversation(endpoint, recall("Likes tea."), recall("Likes cake."), thinking, thinking, true)

        val requests = sent.map { it.request }
        assertExtends(requests)
        val after = requests.last()
        assertEquals(
            listOf("user", "system", "assistant", "user", "system", "assistant", "user", "system", "assistant") +
                listOf("user", "system"),
            after.messages().map { it.role() },
        )
        val systems = after.messages().filter { it.role() == "system" }
        val texts = systems.map { (it["content"] as JsonPrimitive).content }
        assertEquals(listOf("Likes tea.", "Likes tea.", "Likes tea.", "Likes cake."), texts)
        assertTrue(systems.all { (it["clear_at"] as JsonPrimitive).content == "next_user_message" })
        assertFalse(systems.any { "cache_control" in it.toString() })
        for (request in requests) {
            val messages = request.messages()
            assertTrue(messages[messages.size - 2].blocks().last().cached())
            assertEquals("mid-conversation-system-clear-at-2026-08-21", request.header("anthropic-beta"))
        }
        assertEquals(3, thinkingBlocks(after).size)
        val kept = sent.first().reply.providerData.entries.getValue(MessagesFlavor.ANTHROPIC)
        assertEquals(requests.first().messages().last(), kept["turnContext"])
        server.enqueue(answering(null), "/v1/messages")
        runBlocking { turnScoped().use { it.events(sent.last().asked) } }
        assertEquals(after.body, server.requests.last().body)
    }

    @Test
    fun `untrusted context on a turn-scoped endpoint is transient only while the model cannot think`() {
        val endpoint = messagesEndpoint(
            server,
            models = models,
            discover = false,
            messages = MessagesSettings(turnScopedSystem = true),
        )

        endpoint.use {
            assertEquals(TurnContextMode.NOT_SUPPORTED, it.turnContextMode("claude-model", thinking, Trust.UNTRUSTED))
            val default = it.turnContextMode("claude-model", ModelOptions.DEFAULT, Trust.UNTRUSTED)
            assertEquals(TurnContextMode.NOT_SUPPORTED, default)
            assertEquals(TurnContextMode.TRANSIENT, it.turnContextMode("claude-model", off, Trust.UNTRUSTED))
            assertEquals(TurnContextMode.TRANSIENT, it.turnContextMode("unlisted-model", thinking, Trust.UNTRUSTED))
        }
        val baking = messagesEndpoint(server, turnContext = TurnContextSetting.BAKE)
        assertEquals(TurnContextMode.NOT_SUPPORTED, baking.use { it.turnContextMode("m", off, Trust.TRUSTED) })
    }
}
