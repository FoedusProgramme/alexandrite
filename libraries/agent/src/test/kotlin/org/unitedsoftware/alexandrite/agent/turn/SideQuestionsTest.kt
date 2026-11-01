package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import org.unitedsoftware.alexandrite.sdk.turn.SideQuestions
import org.unitedsoftware.alexandrite.sdk.turn.UsagePeriod
import org.unitedsoftware.alexandrite.sdk.turn.UsageReports
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SideQuestionsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val read = recordingTool("fs.read")

    private fun harness(agent: String = ""): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}$agent}}
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(read) }

    private fun PluginHarness.Running.receive(text: String): Admission.Accepted =
        channel().receive(text, sender = channel().user("operator")) as Admission.Accepted

    private suspend fun PluginHarness.Running.ask(question: String, chat: ChatAddress = channel().chat()): String? =
        get<SideQuestions>().ask(chat, question, channel().user("operator"))

    private suspend fun stored(): List<TranscriptEntry> = store.transcripts.entries(store.conversations.current(key).id)

    private fun question(request: ModelRequest): String? =
        ((request.history.last() as? UserEntry)?.parts?.single() as? TextPart)?.text

    @Test
    fun `a side question goes in the request that the next turn starts with, and leaves no trace`() {
        model.reply { text("One.") }.reply { text("A title") }.reply { text("Two.") }

        harness().execute {
            receive("Hi").ticket.outcome()
            val before = stored()

            val answer = ask("Name this conversation.")

            assertEquals("A title", answer)
            assertEquals(before, stored())
            receive("Again").ticket.outcome()
            val (_, side, next) = model.requests
            assertEquals("Name this conversation.", question(side))
            assertEquals(UserOrigin.SystemWritten("side-question"), (side.history.last() as UserEntry).origin)
            assertEquals(next.history.dropLast(1), side.history.dropLast(1))
            assertEquals(before.size to before.size, side.turnStart to next.turnStart)
            assertEquals(
                listOf(next.model, next.instructions, next.tools, next.toolChoice, next.options, next.cacheKey),
                listOf(side.model, side.instructions, side.tools, side.toolChoice, side.options, side.cacheKey),
            )
            assertTrue("fs.read" in side.tools.map { it.name })
            assertEquals(null, store.conversations.turn(side.ids.turn))
            assertEquals(emptyList(), store.conversations.unendedTurns())
            assertEquals(2, channel().replies.size)
            assertEquals(emptyList(), channel().sent)
            assertEquals(before.size + 2, stored().size)
        }
    }

    @Test
    fun `a side question while a turn runs follows what the turn stored up to its running call`() {
        read.hold()
        model.reply { text("One.") }.reply { toolCall("fs.read") }.reply { text("A title") }.reply { text("Done.") }

        harness().execute {
            receive("Hi").ticket.outcome()
            val running = receive("Read it")
            read.awaitCalls(1)
            val stored = stored()

            val answer = ask("Name this conversation.")

            read.release()
            running.ticket.outcome()
            val side = model.requests[2]
            assertEquals("A title", answer)
            assertEquals(stored.dropLast(1), side.history.dropLast(1))
            assertEquals(stored.size - 1, side.turnStart)
        }
    }

    @Test
    fun `a side question whose model calls a tool is asked again without tools`() {
        model.reply { text("One.") }.reply { toolCall("fs.read") }.reply { text("A title") }

        harness().execute {
            receive("Hi").ticket.outcome()

            val answer = ask("Name this conversation.")

            assertEquals("A title", answer)
            assertEquals(listOf(ToolChoice.Auto, ToolChoice.None), model.requests.drop(1).map { it.toolChoice })
            assertEquals(listOf(0, 1), model.requests.drop(1).map { it.ids.round })
            assertEquals(emptyList(), read.calls)
            assertEquals(2, stored().size)
        }
    }

    @Test
    fun `a chat without a conversation gets no answer and keeps none`() {
        harness().execute {
            assertNull(ask("Name this conversation."))

            assertEquals(emptyList(), store.conversations.chats(key.agent))
            assertEquals(emptyList(), model.requests)
        }
    }

    @Test
    fun `a side question is refused where no agent serves, and fails as its model fails`() {
        model.reply { text("One.") }.fail(ModelErrorKind.INVALID_REQUEST)

        harness().execute {
            receive("Hi").ticket.outcome()

            assertFailsWith<ControlRefusedException> { ask("Name it.", ChatAddress.parse("test:other:chat")) }
            val error = assertFailsWith<ModelException> { ask("Name it.") }
            assertEquals(ModelErrorKind.INVALID_REQUEST, error.error.kind)
            assertFailsWith<IllegalArgumentException> { ask(" ") }
            assertEquals(2, stored().size)
        }
    }

    @Test
    fun `a side question counts in the usage of its agent and chat, and is refused past its budget`() {
        val used = Usage.builder().inputTokens(900).outputTokens(100).build()
        model.reply { text("One.") }.reply { text("A title").usage(used) }

        harness(""", "zone": "UTC", "budget": {"daily": {"tokens": 1000}}""").execute {
            receive("Hi").ticket.outcome()

            ask("Name this conversation.")

            val usage = get<UsageReports>().report(channel().chat()).agents.single()
            val today = usage.chat.single { it.period == UsagePeriod.TODAY }.models.single()
            assertEquals(1000L to 2L, today.tokens to today.requests)
            assertFailsWith<ControlRefusedException> { ask("Name it again.") }
            assertEquals(2, model.requests.size)
        }
    }

    @Test
    fun `cancelling a side question cancels its model call`() {
        model.reply { text("One.") }.reply { hang() }

        harness().execute {
            receive("Hi").ticket.outcome()

            val cancelled = coroutineScope {
                val asking = async { ask("Name this conversation.") }
                model.awaitRequests(2)
                asking.cancel()
                runCatching { asking.await() }.exceptionOrNull()
            }

            assertTrue(cancelled is CancellationException)
            assertEquals(2, stored().size)
        }
    }
}
