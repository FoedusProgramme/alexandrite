package org.unitedsoftware.alexandrite.store.memory

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testChat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.fail

class MemoryStorePluginTest {
    private val key = AgentChatKey(AgentId.MAIN, testChat())

    private fun harness(): PluginHarness.Builder = PluginHarness.builder(AlexandriteStoreMemoryIndex())

    private fun run(block: suspend PluginHarness.Running.() -> Unit) {
        runBlocking { harness().build().run(block) }
    }

    @Test
    fun `the plugin binds every store port`() {
        run {
            assertIs<MemoryConversationStore>(get<ConversationStore>())
            assertIs<MemoryTranscriptStore>(get<TranscriptStore>())
            assertIs<MemoryMediaStore>(get<MediaStore>())
            assertIs<MemoryChatStateStore>(get<ChatStateStore>())
            assertIs<MemoryRecordStore>(get<RecordStore>())
        }
    }

    @Test
    fun `each runtime starts with an empty store`() {
        var first: String? = null
        run {
            first = get<ConversationStore>().current(key).id.value
            get<RecordStore>().put("probe", "grants", "k", "1", null)
        }
        run {
            assertNotEquals(first, get<ConversationStore>().current(key).id.value)
            assertNull(get<RecordStore>().get("probe", "grants", "k"))
        }
    }

    @Test
    fun `a second store fails the start at CONFIG, naming both plugins`() {
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking { harness().store(MemoryStore()).build().run { fail("the block ran") } }
        }

        assertEquals(StartStage.CONFIG, error.stage)
        assertEquals(listOf(RuntimeProblemKind.DUPLICATE_STORE), error.problems.map { it.kind })
        assertContains(error.problems.single().message, "plugin 'alexandrite-store-memory' (root 'stores.memory')")
    }
}
