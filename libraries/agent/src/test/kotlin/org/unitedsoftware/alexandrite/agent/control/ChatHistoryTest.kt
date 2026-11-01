package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ChatHistory
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHistoryTest {
    private val clock = TestClock()
    private val store = MemoryStore(clock)
    private val model = ScriptedModel()
    private val coder = AgentId("coder")

    private fun harness(): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {"default": true}}}}
        }
        """.trimIndent(),
        store,
    ) { channel().model(model) }

    /** Runs a turn that answers [text] with [answer] at [chat], the clock a minute on. */
    private suspend fun PluginHarness.Running.turn(chat: ChatAddress, text: String, answer: String) {
        model.reply { text(answer) }
        clock.now = clock.now.plusSeconds(60)
        (channel().receive(text, chat) as Admission.Accepted).ticket.outcome()
    }

    @Test
    fun `the chats of an agent show their current conversations, the latest active first`() {
        harness().execute {
            val first = channel().chat("first")
            val second = channel().chat("second")
            turn(first, "Hi", "One.")
            val firstStarted = clock.now
            turn(second, "Hi", "Two.")
            val secondStarted = clock.now
            turn(first, "Again", "Three.")

            val chats = get<ChatHistory>().chats(coder)

            assertEquals(listOf(first, second), chats.map { it.chat })
            assertEquals(listOf(coder), chats.map { it.agent }.distinct())
            assertEquals(listOf(firstStarted, secondStarted), chats.map { it.startedAt })
            assertEquals(listOf(clock.now, secondStarted), chats.map { it.lastActivity })
            val current = chats.map { store.conversations.current(AgentChatKey(coder, it.chat)).id }
            assertEquals(current, chats.map { it.conversation })
            assertEquals(emptyList(), get<ChatHistory>().chats(AgentId("other")))
        }
    }

    @Test
    fun `the entries of a chat come in pages after an entry`() {
        harness().execute {
            val chat = channel().chat()
            turn(chat, "One", "1.")
            turn(chat, "Two", "2.")
            turn(chat, "Three", "3.")
            val history = get<ChatHistory>()
            val conversation = store.conversations.current(AgentChatKey(coder, chat)).id
            val stored = store.transcripts.entries(conversation)

            val first = history.entries(chat, null, 4)
            val last = first.entries.last().record!!.id
            val rest = history.entries(chat, last, 4)

            assertEquals(conversation to conversation, first.conversation to rest.conversation)
            assertEquals(stored, first.entries + rest.entries)
            assertEquals(4 to 2, first.entries.size to rest.entries.size)
            assertTrue(first.more)
            assertFalse(rest.more)
            assertEquals(emptyList(), history.entries(chat, stored.last().record!!.id, 4).entries)
            assertEquals(stored, history.entries(chat, null, 6, coder).entries)
            assertFalse(history.entries(chat, null, 6).more)
            assertFailsWith<IllegalArgumentException> { history.entries(chat, null, 0) }
        }
    }

    @Test
    fun `a chat without a conversation has no entries and keeps none`() {
        harness().execute {
            val page = get<ChatHistory>().entries(channel().chat(), EntryId(1), 10)

            assertNull(page.conversation)
            assertEquals(emptyList(), page.entries)
            assertFalse(page.more)
            assertEquals(emptyList(), store.conversations.chats(coder))
            assertEquals(emptyList(), get<ChatHistory>().chats(coder))
        }
    }

    @Test
    fun `the entries after a sealed conversation are those of its successor`() {
        harness().execute {
            val chat = channel().chat()
            turn(chat, "Old", "Old.")
            val history = get<ChatHistory>()
            val old = history.entries(chat, null, 10)
            get<ConversationControl>().newConversation(chat, null)
            turn(chat, "New", "New.")

            val page = history.entries(chat, old.entries.last().record!!.id, 10)

            val successor = store.conversations.current(AgentChatKey(coder, chat)).id
            assertEquals(successor, page.conversation)
            assertEquals(store.transcripts.entries(successor), page.entries)
            assertEquals(listOf(successor), history.chats(coder).map { it.conversation })
        }
    }

    @Test
    fun `a chat that no agent serves is refused`() {
        harness().execute {
            val chat = ChatAddress.parse("test:other:chat")

            assertFailsWith<ControlRefusedException> { get<ChatHistory>().entries(chat, null, 10) }
            assertNull(get<ChatHistory>().entries(chat, null, 10, coder).conversation)
        }
    }
}
