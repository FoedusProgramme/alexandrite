package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotMessages
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.testkit.testTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a chat is told of a turn's work while it works.
 *
 * The chat of a platform whose messages cannot be rewritten is told in messages of its own, so a step is not worth a
 * message on its own: the first one is told as it starts, and the ones after it are counted as they end and told
 * together once the interval has passed. What the model says is told once a segment of it is complete.
 */
class OneBotProgressTest {
    private var at = 0L
    private val progress = OneBotProgress(intervalMillis = 8_000) { at }
    private val texts = testTexts(PLUGIN)

    /** What the chat is told as the call [id] starts, or null when it is told nothing. */
    private fun started(
        id: String,
        activity: ToolActivity,
        agent: AgentId? = null,
        title: String = "",
        tool: String = "",
    ): String? = progress.started(ToolCallId(id), activity, agent, tool = tool, title = title)?.let {
        startedMessage(it, texts, null)
    }

    /** What the chat is told of what ended, or null when nothing is told. */
    private fun told(): String? = progress.take()?.let { summaryMessage(it, texts, null) }

    @Test
    fun `the first step of a turn is told as it starts`() {
        assertEquals("Reading 1 file", started("c1", ToolActivity.READ))
    }

    @Test
    fun `a step after the first is not told as it starts`() {
        started("c1", ToolActivity.READ)

        assertNull(started("c2", ToolActivity.SEARCH), "only the first step of a turn is told as it starts")
    }

    @Test
    fun `steps inside the interval wait, and are told together once it passes`() {
        started("c1", ToolActivity.READ)
        progress.ended(ToolCallId("c1"))
        started("c2", ToolActivity.LIST)
        progress.ended(ToolCallId("c2"))

        assertNull(told(), "a step inside the interval is not worth a message")

        at += 8_000
        assertEquals("Read 1 file · Listed 1 directory", told())
    }

    @Test
    fun `nothing waiting says nothing`() {
        assertNull(told(), "a turn that ended nothing has nothing to say")
    }

    @Test
    fun `a call that has not ended is not counted`() {
        started("c1", ToolActivity.READ)

        assertNull(told(), "a call still running is not told as one that ended")
    }

    @Test
    fun `only the calls a summary cannot say get a line of their own`() {
        started("c1", ToolActivity.READ, title = "notes.md")
        started("c2", ToolActivity.READ, AgentId("code-prober"), title = "README.md")
        started("c3", ToolActivity.LIST, title = "src")
        progress.ended(ToolCallId("c1"), org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.FAILED)
        progress.ended(ToolCallId("c2"), org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.FAILED)
        progress.ended(ToolCallId("c3"))
        at += 8_000

        assertEquals(
            "Read 1 file · Listed 1 directory / code-prober · Read 1 file\n" +
                "notes.md — failed\ncode-prober · README.md — failed",
            told(),
        )
    }

    @Test
    fun `the work of each agent stands apart from the work of another`() {
        started("c1", ToolActivity.READ, title = "notes.md")
        started("c2", ToolActivity.READ, AgentId("code-prober"), title = "README.md")
        started("c3", ToolActivity.LIST, AgentId("code-prober"), title = "src")
        started("c4", ToolActivity.READ, AgentId("risk-auditor"), title = "secrets.md")
        listOf("c1", "c2", "c3", "c4").forEach { progress.ended(ToolCallId(it)) }
        at += 8_000

        assertEquals(
            "Read 1 file / code-prober · Read 1 file · Listed 1 directory / risk-auditor · Read 1 file",
            told(),
        )
    }

    @Test
    fun `a call whose kind is unknown is told as work with tools`() {
        started("c1", ToolActivity.of("frobnicate"))
        progress.ended(ToolCallId("c1"))
        at += 8_000

        assertEquals("Used 1 tool", told())
    }

    @Test
    fun `a call that sends a file is told as a file rather than as a tool`() {
        assertEquals("Sending 1 file", started("c1", ToolActivity.SEND, title = "report.pdf", tool = "chat.send_file"))

        started("c2", ToolActivity.SEND, title = "chart.png", tool = "chat.send_file")
        progress.ended(ToolCallId("c2"))
        at += 8_000

        assertEquals("Sent 1 file", told())
    }

    @Test
    fun `a call that ended is told by the interval, without a turn ending it`() {
        started("c1", ToolActivity.READ)
        progress.ended(ToolCallId("c1"))

        assertNull(told(), "the interval has not passed yet")

        at += 8_000

        assertEquals("Read 1 file", told())
    }

    @Test
    fun `each kind of progress has its own switch`() {
        val both = OneBotMessages.capabilities(proactive = true)
        assertTrue(both.streaming)
        assertEquals(setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED), both.events)

        val actions = OneBotMessages.capabilities(proactive = true, streaming = false)
        assertFalse(actions.streaming, "the words of the model are off")
        assertEquals(setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED), actions.events)

        val words = OneBotMessages.capabilities(proactive = true, progress = false)
        assertTrue(words.streaming)
        assertEquals(emptySet(), words.events, "the steps are off")
    }

    @Test
    fun `a call moved to the background is counted as one that ended`() {
        started("c1", ToolActivity.READ, title = "notes.md", tool = "fs.read")
        progress.ended(ToolCallId("c1"), org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.BACKGROUNDED)
        at += 8_000

        assertEquals("Read 1 file\nMoved to the background: fs.read notes.md", told())
    }

    @Test
    fun `a message past its limit keeps the newest lines and counts the rest`() {
        val title = "x".repeat(200)
        for (index in 1..25) {
            started("c$index", ToolActivity.READ, title = title)
            progress.ended(ToolCallId("c$index"), org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.FAILED)
        }
        at += 8_000

        val message = checkNotNull(told()) { "a message was expected" }

        assertTrue(message.startsWith("Read 25 files\n"), "the summary is kept: $message")
        assertTrue(message.contains("earlier calls not shown"), "what was left out is counted: $message")
        assertTrue(message.length <= MAX_ACTIVITY_CHARS, "the message stays within its limit: ${message.length}")
    }

    @Test
    fun `a delegation that moved to the background is told once, in the summary`() {
        started("c1", ToolActivity.OTHER, title = "risk-auditor", tool = "agent.delegate")
        progress.ended(
            ToolCallId("c1"),
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.BACKGROUNDED,
        )
        at += 8_000

        assertEquals("Delegated to risk-auditor", told())
    }

    @Test
    fun `a conversation that keeps working is told less often`() {
        started("c1", ToolActivity.READ)
        progress.ended(ToolCallId("c1"))
        at += 8_000
        assertEquals("Read 1 file", told())

        started("c2", ToolActivity.READ)
        progress.ended(ToolCallId("c2"))
        at += 8_000
        assertNull(told(), "the window after a message is longer than the first")

        at += 8_000
        assertEquals("Read 1 file", told())
    }

    @Test
    fun `a conversation that fell quiet starts over`() {
        started("c1", ToolActivity.READ)
        progress.ended(ToolCallId("c1"))
        at += 8_000
        assertEquals("Read 1 file", told())

        started("c2", ToolActivity.READ)
        progress.ended(ToolCallId("c2"))
        at += 60_000
        assertEquals("Read 1 file", told(), "a long silence is told once the wait has passed")

        started("c3", ToolActivity.READ)
        progress.ended(ToolCallId("c3"))
        at += 8_000
        assertEquals("Read 1 file", told(), "the wait is the first one again after a silence")
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-onebot"
    }
}
