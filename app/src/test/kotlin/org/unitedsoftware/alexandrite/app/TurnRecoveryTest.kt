package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.store.TurnRecord
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptRules
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class TurnRecoveryTest {
    @TempDir
    lateinit var directory: Path

    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val turn = TurnId("t-1")
    private val model = ModelRef(EndpointId("scripted"), "test-model")
    private val call = ToolCallPart(ToolCallId("call-1"), "fs.read", "{}")

    private fun run(plugin: PluginIndex, vararg others: PluginIndex, block: suspend PluginHarness.Running.() -> Unit) {
        val harness = others.fold(PluginHarness.builder(plugin)) { builder, other -> builder.plugin(other) }
        runBlocking { withTimeout(10.seconds) { harness.dataRoot(directory).build().run(block) } }
    }

    /** Runs [block] once the agent started on the SQLite store. */
    private fun started(block: suspend PluginHarness.Running.() -> Unit) {
        run(AlexandriteAgentIndex(), AlexandriteStoreSqliteIndex(), AlexandriteEngineSqliteXerialIndex(), block = block)
    }

    private suspend fun PluginHarness.Running.state(): Pair<List<TranscriptEntry>, TurnRecord?> =
        get<TranscriptStore>().turnEntries(turn) to get<ConversationStore>().turn(turn)

    @Test
    fun `the agent ends a turn that a stopped run left in the SQLite store, and only once`() {
        run(AlexandriteStoreSqliteIndex(), AlexandriteEngineSqliteXerialIndex()) {
            val conversation = get<ConversationStore>().current(key).id
            val info = TurnInfo.builder(turn, key.chat, conversation, TurnKind.MESSAGE)
                .actor(testUser())
                .agent(key.agent)
                .key(key)
                .build()
            get<ConversationStore>().startTurn(info)
            val origin = UserOrigin.FromChat(testUser(), ChannelMessageRef(key.chat, "in-1"), Instant.EPOCH, null, null)
            get<TranscriptStore>().append(
                turn,
                listOf(
                    UserEntry(null, listOf(TextPart("Read it.")), origin),
                    AssistantEntry(null, listOf(call), model),
                ),
            )
        }
        lateinit var first: Pair<List<TranscriptEntry>, TurnRecord?>

        started {
            first = state()
            assertEquals(
                emptyList(),
                TranscriptRules.check(get<TranscriptStore>().entries(get<ConversationStore>().current(key).id)),
            )
        }
        started { assertEquals(first, state()) }

        val (entries, record) = first
        val result = entries[2] as ToolResultEntry
        val notice = entries[3] as NoticeEntry
        assertEquals(4, entries.size)
        assertEquals(
            listOf(call.id, ToolOutcome.Cancelled, "Cancelled (outcome unknown)"),
            listOf(result.callId, result.outcome, (result.content.single() as TextPart).text),
        )
        assertEquals(
            NoticeKind.INTERRUPTED to "The agent stopped before it finished answering this message.",
            notice.kind to notice.text,
        )
        assertEquals(TurnEndKind.SHUT_DOWN, record?.end)
    }
}
