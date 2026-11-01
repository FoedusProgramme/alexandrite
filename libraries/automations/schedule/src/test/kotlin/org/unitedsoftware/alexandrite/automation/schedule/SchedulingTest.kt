package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.store.RecordCollection
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.RecordingWakeScheduler.Wake
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testChat
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Firing: the turns, their outcomes, restarts, missed occurrences and the chat's notices. */
class SchedulingTest {
    private val records = TestRecords()

    private fun test(block: suspend TestScope.() -> Unit) = runTest { block() }

    private suspend fun TestScope.opened(origin: Instant = ORIGIN, language: LanguageTag? = null): Schedule =
        Schedule(this, records, origin = origin, language = language).open()

    private var failures = 0

    /** Makes the next [count] reads of the records through [flaky] fail. */
    private fun failing(count: Int) {
        failures = count
    }

    private val flaky = { records: Records ->
        object : Records {
            override fun <T : Any> collection(name: String, serializer: KSerializer<T>): RecordCollection<T> {
                val inner = records.collection(name, serializer)
                return object : RecordCollection<T> by inner {
                    override suspend fun list(): List<StoredRecord<T>> =
                        if (failures-- > 0) throw IOException("The disk is gone.") else inner.list()
                }
            }
        }
    }

    @Test
    fun `a task fires on time as a reminder in its chat, and a one-shot one is then gone with its mandate`() = test {
        val schedule = opened()
        val id = schedule.make(mapOf("in" to "30m"))

        schedule.advance(29.minutes)
        assertEquals(emptyList(), schedule.initiator.turns)
        schedule.advance(1.minutes)

        val turn = schedule.initiator.turns.single()
        assertEquals(KEY.chat to KEY.agent, turn.chat to turn.agent)
        assertEquals(TurnKind.REMINDER to ReplyRoute.ToChat(CHAT), turn.kind to turn.route)
        assertEquals(
            "Scheduled task $id, set by Ada on 2026-10-18; due 2026-10-18 18:30 +08:00. Do what it asks; it was " +
                "scheduled earlier, so do not schedule it again.\n\nCheck the build.",
            turn.prompt,
        )
        assertTrue(schedule.control.list(KEY).single().running)
        assertEquals(emptyList(), schedule.initiator.revoked)
        schedule.initiator.tickets.single().complete(TurnOutcome.Completed(null))
        schedule.advance(1.seconds)
        assertEquals(emptyList(), schedule.control.list(KEY))
        assertEquals(listOf(MANDATE), schedule.initiator.revoked)
        assertEquals(null, records.of(PLUGIN).collection<TaskRecord>("tasks").get(id))
    }

    @Test
    fun `a recurring task fires at each occurrence and keeps its mandate`() = test {
        val schedule = opened()
        schedule.make(mapOf("every" to "1h"))

        repeat(3) {
            schedule.advance(1.hours)
            schedule.initiator.tickets.last().complete(TurnOutcome.Failed("the model failed"))
        }
        schedule.advance(1.seconds)

        val dues = schedule.initiator.turns.map { Regex("due ([^.]+)\\.").find(it.prompt)!!.groupValues[1] }
        assertEquals(listOf("19:00", "20:00", "21:00").map { "2026-10-18 $it +08:00" }, dues)
        assertEquals(ORIGIN.plusSeconds(4 * 3600), schedule.control.list(KEY).single().next)
        assertEquals(emptyList(), schedule.initiator.revoked)
    }

    @Test
    fun `a task made in a linked group runs there as the anchor's agent`() = test {
        val schedule = opened()
        schedule.make(mapOf("in" to "10m"), turn(chat = OTHER_CHAT) { key(KEY) })

        schedule.advance(10.minutes)

        val turn = schedule.initiator.turns.single()
        assertEquals(OTHER_CHAT to KEY.agent, turn.chat to turn.agent)
        assertEquals(ReplyRoute.ToChat(OTHER_CHAT), turn.route)
        assertEquals(KEY, schedule.control.list(KEY).single().key)
    }

    @Test
    fun `a task of a slow chat does not hold another chat's`() = test {
        val schedule = opened()
        schedule.make(mapOf("in" to "10m"))
        schedule.make(mapOf("in" to "20m"), turn(chat = OTHER_CHAT))

        schedule.advance(20.minutes)

        assertEquals(listOf(CHAT, OTHER_CHAT), schedule.initiator.turns.map { it.chat })
    }

    // Outcomes and restarts.

    @Test
    fun `a turn the shutdown never started fires again after the restart, and its framing tells how late`() = test {
        val first = opened()
        val id = first.make(mapOf("in" to "10m"))
        first.advance(10.minutes)
        first.initiator.tickets.single().complete(TurnOutcome.ShutDown(replayable = true))
        first.stop()

        val second = opened(origin = ORIGIN.plusSeconds(3600))

        val replayed = second.initiator.turns.single()
        assertContains(
            replayed.prompt,
            "Scheduled task $id, set by Ada on 2026-10-18; due 2026-10-18 18:10 +08:00, running 50 min late.",
        )
        assertEquals(MANDATE, replayed.mandate)
        assertEquals(emptyList(), first.notices + second.notices)
    }

    @Test
    fun `a turn the shutdown cut off is reported to the chat and not run again`() = test {
        val first = opened()
        val id = first.make(mapOf("in" to "10m"))
        first.advance(10.minutes)
        first.initiator.tickets.single().complete(TurnOutcome.ShutDown(replayable = false))
        first.advance(1.seconds)

        val notice = first.channel.sent.single()
        assertEquals(CHAT to MessageKind.NOTICE, notice.chat to notice.message.kind)
        assertEquals(
            "Scheduled task $id was cut off by a restart or a crash before it finished: Check the build.",
            notice.message.text,
        )
        assertEquals(listOf(MANDATE), first.initiator.revoked)
        first.stop()
        assertEquals(emptyList(), opened().initiator.turns)
    }

    @Test
    fun `a crash mid-turn is reported at the next start, and a recurring task goes on`() = test {
        val first = opened()
        val id = first.make(mapOf("every" to "1h"))
        first.advance(1.hours)
        first.stop()

        val second = opened(origin = ORIGIN.plusSeconds(2 * 3600 - 60))

        assertEquals(
            listOf("Scheduled task $id was cut off by a restart or a crash before it finished: Check the build."),
            second.notices,
        )
        assertEquals(emptyList(), second.initiator.turns)
        second.advance(1.minutes)
        assertContains(second.initiator.turns.single().prompt, "due 2026-10-18 20:00 +08:00.")
    }

    @Test
    fun `a one-shot task due while the host was down runs late within the window`() = test {
        val first = opened()
        val id = first.make(mapOf("at" to "2026-10-18T19:00"))
        first.stop()

        val second = opened(origin = ORIGIN.plusSeconds(23 * 3600))

        assertContains(
            second.initiator.turns.single().prompt,
            "Scheduled task $id, set by Ada on 2026-10-18; due 2026-10-18 19:00 +08:00, running 1320 min late.",
        )
        assertEquals(emptyList(), second.notices)
    }

    @Test
    fun `a one-shot task missed beyond the window is reported, never run, and gone with its mandate`() = test {
        val first = opened()
        val id = first.make(mapOf("at" to "2026-10-18T19:00"))
        first.stop()

        val second = opened(origin = ORIGIN.plusSeconds(26 * 3600))

        assertEquals(emptyList(), second.initiator.turns)
        assertEquals(
            listOf(
                "Scheduled task $id was due at 2026-10-18 19:00 +08:00, while the host was down; it was not run: " +
                    "Check the build.",
            ),
            second.notices,
        )
        assertEquals(emptyList(), second.control.list(KEY))
        assertEquals(listOf(MANDATE), second.initiator.revoked)
    }

    @Test
    fun `a refused turn is reported and does not stop the task, also for a chat still unknown after the start`() =
        test {
            val schedule = opened()
            val unknown = schedule.make(mapOf("in" to "10m"))
            val id = schedule.make(mapOf("every" to "1h"))
            schedule.initiator.recording.refuseNext(RefusalReason.UNKNOWN_CHAT)
            schedule.initiator.recording.refuseNext(RefusalReason.QUEUE_FULL, queueCapacity = 5)

            schedule.advance(1.hours)

            assertEquals(
                listOf(
                    "Scheduled task $unknown, due at 2026-10-18 18:10 +08:00, could not run (unknown_chat): " +
                        "Check the build.",
                    "Scheduled task $id, due at 2026-10-18 19:00 +08:00, could not run (queue_full): Check the build.",
                ),
                schedule.notices,
            )
            schedule.advance(1.hours)
            assertEquals(
                listOf(false, false, true),
                schedule.initiator.recording.results.map { it is Admission.Accepted },
            )
        }

    @Test
    fun `a turn refused as the agent shuts down is replayed after the restart`() = test {
        val first = opened()
        first.make(mapOf("in" to "10m"))
        first.scheduling.onClose()
        first.initiator.recording.refuseNext(RefusalReason.SHUTTING_DOWN)

        first.advance(10.minutes)
        first.stop()

        val pending = assertNotNull(first.tasks.all().single().second.pending)
        assertEquals(true, pending.replay)
        assertEquals(1, opened(origin = ORIGIN.plusSeconds(1200)).initiator.turns.size)
    }

    @Test
    fun `a turn refused before the agent opens is tried again until it opens`() = test {
        val schedule = opened()
        schedule.make(mapOf("in" to "10m"))
        repeat(3) { schedule.initiator.recording.refuseNext(RefusalReason.SHUTTING_DOWN) }

        schedule.advance(10.minutes + 5.seconds)

        assertEquals(4, schedule.initiator.turns.size)
        assertEquals(1, schedule.initiator.tickets.size)
    }

    @Test
    fun `a task due at the start waits for its chat's channel and agent to open`() = test {
        val first = opened()
        first.make(mapOf("in" to "10m"))
        first.stop()
        val second = Schedule(this, records, origin = ORIGIN.plusSeconds(3600))
        second.initiator.recording.refuseNext(RefusalReason.UNKNOWN_CHAT)
        second.initiator.recording.refuseNext(RefusalReason.NO_AGENT)

        second.open()
        second.advance(2.seconds)

        assertEquals(1, second.initiator.tickets.size)
        assertEquals(emptyList(), second.notices)
    }

    @Test
    fun `the tools fail at once while the records cannot be read`() = test {
        failing(Int.MAX_VALUE)
        val schedule = Schedule(this, records, wrap = flaky).open()

        val answer = withTimeout(1.minutes) { schedule.call(schedule.list, emptyMap()) }

        assertTrue(answer.isError)
        assertContains(answer.text, "The scheduled tasks cannot be read: java.io.IOException: The disk is gone.")
    }

    @Test
    fun `a schedule whose records fail starts again and fires what they hold`() = test {
        val first = opened()
        val id = first.make(mapOf("in" to "10m"))
        first.stop()
        failing(1)

        val second = Schedule(this, records, wrap = flaky).open()
        assertEquals(emptyList(), second.initiator.turns)
        second.advance(10.minutes)

        assertContains(second.initiator.turns.single().prompt, "Scheduled task $id")
    }

    @Test
    fun `a record whose creation time cannot be read is skipped and the others load`() = test {
        val first = opened()
        val broken = first.make(mapOf("in" to "10m"))
        val kept = first.make(mapOf("in" to "20m"))
        first.stop()
        val collection = records.of(PLUGIN).collection("tasks", TaskRecord.serializer())
        val record = collection.list().single { it.key == broken }.value
        collection.put(broken, record.copy(createdAt = "yesterday"), record.createdBy)

        val second = opened()

        assertEquals(null, second.tasks.live(broken))
        assertNotNull(second.tasks.live(kept))
    }

    @Test
    fun `a task of a chat whose zone differs is told in the zone it was made in`() = test {
        val schedule = opened()
        val utc = turn(chat = testChat("utc")) { zone(java.time.ZoneOffset.UTC) }

        val answer = schedule.call(schedule.create, mapOf("prompt" to "x", "at" to "2026-10-18T12:00"), utc).text

        assertContains(answer, "next run 2026-10-18 12:00 +00:00.")
        schedule.advance(2.hours)
        assertContains(schedule.initiator.turns.single().prompt, "due 2026-10-18 12:00 +00:00.")
    }

    // The host's wakes.

    @Test
    fun `the host is told under the plugin's id when the next task is due, and the wake outlasts the stop`() = test {
        val schedule = opened()
        schedule.make(mapOf("in" to "30m"))
        schedule.make(mapOf("in" to "10m"))

        schedule.stop()

        assertEquals(
            listOf(null, ORIGIN.plusSeconds(1800), ORIGIN.plusSeconds(600)).map { Wake(PLUGIN, it) },
            schedule.wakes.calls,
        )
    }

    @Test
    fun `a start at the wake after the process ended runs the task that came due, and clears the wake`() = test {
        val first = opened()
        val id = first.make(mapOf("in" to "10m"))
        first.stop()

        val second = opened(origin = first.wakes.pending.getValue(PLUGIN).plusSeconds(300))

        assertContains(second.initiator.turns.single().prompt, "Scheduled task $id, set by Ada on 2026-10-18; due ")
        assertContains(second.initiator.turns.single().prompt, "running 5 min late.")
        assertEquals(listOf(Wake(PLUGIN, null)), second.wakes.calls)
    }
}
