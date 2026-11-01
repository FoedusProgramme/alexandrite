package org.unitedsoftware.alexandrite.store.sqlite

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.Termination
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatStates
import org.unitedsoftware.alexandrite.sdk.chat.agentState
import org.unitedsoftware.alexandrite.sdk.chat.state
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testChat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class SqliteStorePluginTest {
    @TempDir
    lateinit var directory: Path

    private val store: Path
        get() = directory.resolve("plugins/alexandrite-store-sqlite").resolve(StoreDatabase.FILE_NAME)

    private class Probe : PluginIndex {
        override val info: PluginInfo =
            PluginInfo("probe", "Probe", "test", "", AlexandriteSdk.API_VERSION, emptyList(), javaClass.name)
        override val configRoot: String = PluginIds.thirdPartyRoot("probe")

        override fun bindings(): List<Binding<*>> = emptyList()

        override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
    }

    private fun harness(): PluginHarness.Builder =
        PluginHarness.builder(AlexandriteStoreSqliteIndex()).plugin(driverIndex).plugin(Probe()).dataRoot(directory)

    private fun run(block: suspend PluginHarness.Running.() -> Unit): Termination =
        runBlocking { harness().build().run(block) }

    @Test
    fun `the store opens in the plugin's data directory and binds every store port`() {
        run {
            assertEquals(store, get<StoreDatabase>().file)
            assertTrue(Files.isRegularFile(store))
            assertIs<SqliteConversationStore>(get<ConversationStore>())
            assertIs<SqliteTranscriptStore>(get<TranscriptStore>())
            assertIs<SqliteMediaStore>(get<MediaStore>())
            assertIs<SqliteChatStateStore>(get<ChatStateStore>())
        }
    }

    @Test
    fun `another plugin's chat states outlive the run`() {
        val chat = testChat()
        val key = AgentChatKey(AgentId.MAIN, chat)

        run {
            val states = get<ChatStates>("probe")
            states.state("mode", "plain").set(chat, "rich")
            states.agentState("model", "default").set(key, "large")
        }
        run {
            val states = get<ChatStates>("probe")
            assertEquals("rich", states.state("mode", "plain").get(chat))
            assertEquals("large", states.agentState("model", "default").get(key))
            assertEquals("plain", states.state("mode", "plain").get(testChat("other")))
        }
    }

    @Test
    fun `a second store fails the start at CONFIG, naming both plugins`() {
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking { harness().store(MemoryStore()).build().run { fail("the block ran") } }
        }

        assertEquals(StartStage.CONFIG, error.stage)
        assertEquals(listOf(RuntimeProblemKind.DUPLICATE_STORE), error.problems.map { it.kind })
        assertContains(error.problems.single().message, "plugin 'alexandrite-store-sqlite' (root 'stores.sqlite')")
        assertContains(error.problems.single().message, "plugin 'testkit' (root 'plugins.testkit')")
    }

    @Test
    fun `a store without a driver fails the start at CONFIG, naming the engines that bind one`() {
        val error = assertFailsWith<RuntimeStartException> {
            runBlocking {
                PluginHarness.builder(AlexandriteStoreSqliteIndex()).dataRoot(directory).build().run {
                    fail("the block ran")
                }
            }
        }

        assertEquals(StartStage.CONFIG, error.stage)
        val problem = error.problems.single()
        assertEquals(
            RuntimeProblemKind.MISSING_REQUIREMENT to "alexandrite-store-sqlite",
            problem.kind to problem.plugin,
        )
        assertContains(problem.message, "needs a SqliteDriver")
        assertContains(problem.message, "alexandrite-engine-sqlite-xerial")
        assertFalse(Files.exists(store))
    }

    @Test
    fun `a store of a newer version fails the start`() {
        Files.createDirectories(store.parent)
        connect(store).use { it.runSql("PRAGMA user_version = 99") }

        val error = assertFailsWith<RuntimeStartException> { run { fail("the block ran") } }

        assertEquals(StartStage.START, error.stage)
    }
}
