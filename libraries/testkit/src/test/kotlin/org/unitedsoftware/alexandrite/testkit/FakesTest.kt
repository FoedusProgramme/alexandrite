package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AgentSource
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsSnapshot
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.turn.ControlRefusedException
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.JobStatus
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import org.unitedsoftware.alexandrite.sdk.turn.TrustedPerson
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.sdk.turn.TurnStatus
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakesTest {
    @TempDir
    lateinit var directory: Path

    // Turn ports.

    @Test
    fun `a recording submitter accepts submissions as tickets the test ends`() {
        val submitter = RecordingTurnSubmitter()
        val first = Submission.Message(testMessage("one"))
        val second = Submission.Command(testCommand("new"))
        val third = Submission.Message(testMessage("three"))
        submitter.refuseNext(RefusalReason.SHUTTING_DOWN)

        val admissions = listOf(submitter.submit(third), submitter.submit(first), submitter.submit(second))

        assertEquals(Admission.Refused(RefusalReason.SHUTTING_DOWN), admissions[0])
        assertEquals(listOf(third, first, second), submitter.submissions)
        assertEquals(admissions, submitter.results)
        val (one, two) = submitter.tickets
        assertEquals(listOf(TurnId("test-turn-1"), TurnId("test-turn-2")), submitter.tickets.map { it.turn })
        assertTrue(one.complete(TurnOutcome.Completed(null)))
        assertFalse(one.complete(TurnOutcome.Cancelled))
        assertTrue(two.cancel())
        assertFalse(two.cancel())
        assertEquals(TurnOutcome.Completed(null), blocking { one.outcome() })
        assertEquals(TurnOutcome.Cancelled, blocking { two.outcome() })
        assertFalse(one.cancelled)
        assertTrue(two.cancelled && two.ended)
        val ending = RecordingTurnSubmitter(TurnOutcome.ShutDown(replayable = true))
        val admission = assertIs<Admission.Accepted>(ending.submit(Submission.Message(testMessage())))
        assertEquals(TurnOutcome.ShutDown(true), blocking { admission.ticket.outcome() })
    }

    @Test
    fun `a recording initiator records each turn with the plugin that started it`() {
        val initiator = RecordingTurnInitiator("reminders")
        val turn = InitiatedTurn.builder(testChat(), TurnKind.REMINDER, "Water the plants.").build()
        initiator.refuseNext(RefusalReason.NO_AGENT)

        val refused = initiator.initiate(turn)
        val accepted = initiator.initiate(turn)
        initiator.record("other", turn)

        assertEquals(Admission.Refused(RefusalReason.NO_AGENT), refused)
        assertIs<Admission.Accepted>(accepted)
        assertEquals(
            listOf("reminders", "reminders", "other"),
            initiator.initiated.map { it.plugin },
        )
        assertEquals(RecordingTurnInitiator.Initiated("reminders", turn), initiator.initiated.first())
        assertEquals(2, initiator.tickets.size)
        assertEquals(3, blocking { initiator.awaitInitiated(3) }.size)
    }

    @Test
    fun `a recording initiator refuses the kinds that the agent starts itself, as the runtime does`() {
        val initiator = RecordingTurnInitiator("reminders")

        for (kind in listOf(TurnKind.APPROVAL, TurnKind.AGENT_MESSAGE)) {
            val turn = InitiatedTurn.builder(testChat(), kind, "Approved.").build()

            assertFailsWith<IllegalArgumentException> { initiator.initiate(turn) }
        }
        assertEquals(emptyList(), initiator.initiated)
    }

    // Commands and control.

    @Test
    fun `a test command context records the notices its handler sends`() {
        val invocation = testCommand("new", issuer = testUser("ada", isOperator = true))
        val context = testCommandContext(invocation)
        context.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.FORBIDDEN))

        val refused = blocking { context.reply("Started.", Markup.MARKDOWN) }
        val delivered = blocking { context.reply("Again.") }

        assertEquals(TurnKind.COMMAND, context.turn.kind)
        assertEquals(invocation.chat, context.turn.chat)
        assertTrue(context.turn.actorIsOperator)
        assertEquals(Delivery.NotDelivered(DeliveryFailure.FORBIDDEN), refused)
        assertEquals(Delivery.Delivered(listOf(ChannelMessageRef(invocation.chat, "reply-2"))), delivered)
        assertEquals(listOf("Started.", "Again."), context.replies.map { it.text })
        assertTrue(context.replies.all { it.kind == MessageKind.NOTICE && it.replyTo == invocation.trigger })
        assertTrue(context.replies.all { it.vouched })
        assertEquals(Markup.MARKDOWN, context.replies.first().markup)
        assertFailsWith<IllegalArgumentException> { blocking { context.reply(" ") } }
        assertNull(TestCommandContext().replies.firstOrNull())
        val guest = testCommandContext(testCommand("new", issuer = testUser("bob")))
        blocking { guest.reply("Started.") }
        assertEquals(listOf(false), guest.replies.map { it.vouched })
    }

    @Test
    fun `a recording agent records its calls and answers from the statuses`() {
        val control = RecordingAgent()
        val chat = testChat()
        val operator = testUser("ada", isOperator = true)
        val running = TurnStatus.builder(testTurn(), TurnPhase.RUNNING, TEST_TIME).startedAt(TEST_TIME).build()
        val delegated = TurnStatus.builder(testDelegatedTurn(), TurnPhase.QUEUED, TEST_TIME).build()
        val job = JobStatus(RunId("j-1"), chat, AgentId.MAIN, "command.exec", "./gradlew test", TEST_TIME, operator)
        control.statuses = listOf(running, delegated)
        control.jobStatuses = listOf(job)

        val cancelled = control.turns.cancel(chat, operator)
        val elsewhere = control.turns.cancel(testChat("other"), null)
        val turn = control.turns.cancelTurn(TurnId("test-turn"), operator)
        val run = control.turns.cancelRun(RunId("test-run"), tree = true, by = operator)
        val conversations =
            blocking {
                listOf(
                    control.conversations.newConversation(chat, operator),
                    control.conversations.newConversation(chat, null),
                )
            }
        val stopped = control.turns.stopJob(chat, RunId("j-1"), operator)
        val otherJob = control.turns.stopJob(testChat("other"), RunId("j-1"), operator)

        assertEquals(listOf(running, delegated), blocking { control.turns.turns(chat) })
        assertEquals(emptyList(), blocking { control.turns.turns(testChat("other")) })
        assertEquals(listOf(job), control.turns.jobs(chat))
        assertEquals(emptyList(), control.turns.jobs(testChat("other")))
        assertEquals(
            listOf(true, false, true, true, true, false),
            listOf(cancelled, elsewhere, turn, run, stopped, otherJob),
        )
        assertEquals(listOf(ConversationId("conversation-1"), ConversationId("conversation-2")), conversations)
        assertEquals(
            listOf(
                RecordingAgent.Call.Cancel(chat, operator),
                RecordingAgent.Call.Cancel(testChat("other"), null),
                RecordingAgent.Call.CancelTurn(TurnId("test-turn"), operator),
                RecordingAgent.Call.CancelRun(RunId("test-run"), true, operator),
                RecordingAgent.Call.NewConversation(chat, operator, conversations[0]),
                RecordingAgent.Call.NewConversation(chat, null, conversations[1]),
                RecordingAgent.Call.StopJob(chat, RunId("j-1"), operator),
                RecordingAgent.Call.StopJob(testChat("other"), RunId("j-1"), operator),
            ),
            control.calls,
        )
    }

    @Test
    fun `a recording agent keeps settings per agent and chat`() {
        val control = RecordingAgent()
        val chat = testChat()
        val coder = AgentId("coder")
        control.scriptAgent(coder)
        val set = ChatSettingsUpdate.builder().model(TEST_MODEL).reasoning(ReasoningEffort.HIGH).build()
        val switch = ChatSettingsUpdate.builder().agent(coder).language(LanguageTag("de")).build()
        val back = ChatSettingsUpdate.builder().resetAgent().resetReasoning().build()

        val main = blocking { control.settings.update(chat, set, null) }
        val switched = blocking { control.settings.update(chat, switch, null) }
        val returned = blocking { control.settings.update(chat, back, null) }

        assertEquals(
            ChatSettingsSnapshot.builder(AgentId.MAIN).model(TEST_MODEL).reasoning(ReasoningEffort.HIGH).build(),
            main,
        )
        assertEquals(
            ChatSettingsSnapshot.builder(coder).language(LanguageTag("de")).agentSource(AgentSource.SELECTED).build(),
            switched,
        )
        assertEquals(ChatSettingsSnapshot.builder(AgentId.MAIN).model(TEST_MODEL).build(), returned)
        assertEquals(returned, blocking { control.settings.snapshot(chat) })
        assertEquals(
            ChatSettingsSnapshot.builder(AgentId.MAIN).build(),
            blocking {
                control.settings.snapshot(testChat("other"))
            },
        )
        assertEquals(3, control.calls.filterIsInstance<RecordingAgent.Call.UpdateSettings>().size)
    }

    @Test
    fun `a recording agent refuses the calls that the test queues refusals for`() {
        val control = RecordingAgent()
        val chat = testChat()
        val operator = testUser("ada", isOperator = true)

        control.refuseNext("No agent serves $chat.")
        control.refuseNext("Folder /srv is protected.")
        val queued =
            assertFailsWith<ControlRefusedException> {
                blocking { control.conversations.newConversation(chat, operator) }
            }
        val folder =
            assertFailsWith<ControlRefusedException> {
                blocking { control.trust.trustFolder(chat, Path.of("/srv"), null) }
            }
        val conversation = blocking { control.conversations.newConversation(chat, operator) }

        assertEquals("No agent serves $chat.", queued.message)
        assertEquals("Folder /srv is protected.", folder.message)
        assertEquals(ConversationId("conversation-1"), conversation)
        assertEquals(AgentId.MAIN, blocking { control.settings.snapshot(chat) }.agent)
        assertEquals(
            listOf(
                RecordingAgent.Call.NewConversation(chat, operator, null),
                RecordingAgent.Call.TrustFolder(chat, Path.of("/srv"), null),
                RecordingAgent.Call.NewConversation(chat, operator, conversation),
            ),
            control.calls,
        )
        assertEquals(emptyList(), blocking { control.trust.folders(chat) })
    }

    @Test
    fun `a recording agent trusts folders that no trusted folder covers, and people`() {
        val control = RecordingAgent()
        val operator = testUser("ada", isOperator = true)
        val repository = Path.of("/srv/repo")
        val declared = Path.of("/srv/config")
        val chat = testChat()
        control.declareFolder(declared)

        val trusted = blocking {
            listOf(
                control.trust.trustFolder(chat, repository, operator),
                control.trust.trustFolder(chat, Path.of("/srv/repo/sub/../lib"), null),
            )
        }

        assertEquals(listOf(repository, repository), trusted)
        assertEquals(
            listOf(TrustedFolder(declared, true, null, null), TrustedFolder(repository, false, TEST_TIME, operator)),
            blocking { control.trust.folders(chat) },
        )
        val revoked = blocking {
            listOf(declared, Path.of("/srv"), repository, repository).map {
                control.trust.untrustFolder(chat, it, null)
            }
        }
        assertEquals(
            listOf(true to false, false to false, false to true, false to false),
            revoked.map { it.declared to it.removed },
        )
        assertEquals(listOf(declared), blocking { control.trust.folders(chat) }.map { it.path })
        assertEquals(RecordingAgent.Call.TrustFolder(chat, repository, operator), control.calls.first())

        val guest = testUser("bob")
        val person = blocking { control.trust.trustPerson(testChat(), guest, operator) }
        assertEquals(TrustedPerson(guest, TEST_TIME, operator), person)
        assertEquals(listOf(person), blocking { control.trust.people(testChat()) })
        assertEquals(Revocation(true, false), blocking { control.trust.untrustPerson(testChat(), guest, operator) })
        val configured = testUser("cy", isTrusted = true)
        assertEquals(Revocation(false, true), blocking { control.trust.untrustPerson(testChat(), configured, null) })
        assertEquals(emptyList(), blocking { control.trust.people(testChat()) })
    }

    @Test
    fun `a test tool context fails once its call ended, as the agent's does`() {
        val context = testToolContext(workspace = directory.resolve("work"))
        context.touched(Path.of("/srv/notes.md"))

        context.end()

        assertFailsWith<IllegalStateException> { context.turn }
        assertFailsWith<IllegalStateException> { context.touched(Path.of("/srv/other.md")) }
        assertEquals(listOf(Path.of("/srv/notes.md")), context.touchedPaths)
    }

    // Wakes.

    @Test
    fun `a recording wake scheduler keeps each owner's latest wake apart from the others'`() {
        val wakes = RecordingWakeScheduler()
        val nine = Instant.parse("2026-10-18T09:00:00Z")
        val ten = Instant.parse("2026-10-18T10:00:00Z")

        wakes.wakeAt("schedule", ten)
        wakes.wakeAt("other", nine)
        wakes.wakeAt("schedule", nine.plusSeconds(60))
        wakes.wakeAt("other", null)

        assertEquals(mapOf("schedule" to nine.plusSeconds(60)), wakes.pending)
        assertEquals(nine.plusSeconds(60), wakes.earliest)
        assertEquals(RecordingWakeScheduler.Wake("other", null), wakes.calls.last())
        assertEquals(4, runBlocking { wakes.awaitCalls(4) }.size)
    }
}
