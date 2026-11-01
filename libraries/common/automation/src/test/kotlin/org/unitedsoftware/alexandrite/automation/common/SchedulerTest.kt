package org.unitedsoftware.alexandrite.automation.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerTest {
    private class Fire(val id: String, val due: Instant, val late: Duration, val at: Instant) {
        override fun toString() = "$id due $due late $late at $at"
    }

    private class Harness(
        val scope: TestScope,
        val store: MemoryTaskStore<String> = MemoryTaskStore(),
        val window: Duration = Scheduler.DEFAULT_MISSED_WINDOW,
        val outcome: suspend (Task<String>, Occurrence) -> Fired = { _, _ -> Fired.DONE },
    ) {
        val fires = mutableListOf<Fire>()
        val events = RecordingEvents()
        val scheduler = Scheduler(
            store,
            { task, occurrence ->
                fires += Fire(task.id, occurrence.due, occurrence.late, scope.now())
                outcome(task, occurrence)
            },
            events,
            scope.virtualTime(),
            window,
        )
        lateinit var job: Job

        fun start(): Harness {
            job = scope.backgroundScope.launch { scheduler.run() }
            scope.runCurrent()
            return this
        }
    }

    private fun TestScope.harness(
        store: MemoryTaskStore<String> = MemoryTaskStore(),
        window: Duration = Scheduler.DEFAULT_MISSED_WINDOW,
        outcome: suspend (Task<String>, Occurrence) -> Fired = { _, _ -> Fired.DONE },
    ) = Harness(this, store, window, outcome).start()

    private fun at(offset: Duration): Instant = ORIGIN.plusMillis(offset.inWholeMilliseconds)

    private fun once(offset: Duration) = When.Once(at(offset).atZone(UTC).toLocalDateTime())

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    // Firing.

    @Test
    fun `tasks fire in due order, then a one-shot task is gone`() = runTest {
        val harness = harness()
        harness.scheduler.schedule("b", once(20.minutes), UTC)
        harness.scheduler.schedule("a", once(10.minutes), UTC)
        harness.scheduler.schedule("c", once(20.minutes), UTC)

        advance(30.minutes)

        assertEquals(
            listOf("a" to at(10.minutes), "b" to at(20.minutes), "c" to at(20.minutes)),
            harness.fires.map { it.id to it.at },
        )
        assertTrue(harness.fires.all { it.late == Duration.ZERO })
        assertTrue(harness.store.tasks.isEmpty())
        assertTrue(harness.scheduler.tasks().isEmpty())
    }

    @Test
    fun `the occurrence is saved as pending with the next due instant in one save before it fires`() = runTest {
        val store = MemoryTaskStore<String>()
        val seen = mutableListOf<Task<String>>()
        val harness = harness(store) { task, _ ->
            seen += store.tasks.getValue(task.id)
            Fired.DONE
        }
        harness.scheduler.schedule("r", When.Every(10.minutes, ORIGIN), UTC)
        val savesBefore = store.saves.size

        advance(10.minutes)

        val atFire = seen.single()
        assertEquals(Pending(Occurrence(at(10.minutes), Duration.ZERO), replay = false), atFire.pending)
        assertEquals(at(20.minutes), atFire.next)
        assertEquals(savesBefore + 2, store.saves.size)
        assertEquals(atFire, store.saves[savesBefore])
    }

    @Test
    fun `an occurrence that comes while the previous fire runs is skipped`() = runTest {
        val harness = harness { _, _ ->
            delay(12.minutes)
            Fired.DONE
        }
        harness.scheduler.schedule("r", When.Every(5.minutes, ORIGIN), UTC)

        advance(21.minutes)

        assertEquals(listOf(at(5.minutes), at(20.minutes)), harness.fires.map { it.at })
        assertEquals(listOf("skipped r ${at(10.minutes)}", "skipped r ${at(15.minutes)}"), harness.events.log)
    }

    @Test
    fun `a fire that throws counts as done and is reported`() = runTest {
        val harness = harness { _, _ -> error("boom") }
        harness.scheduler.schedule("r", When.Every(10.minutes, ORIGIN), UTC)

        advance(25.minutes)

        assertEquals(2, harness.fires.size)
        assertEquals(listOf("failed r boom", "failed r boom"), harness.events.log)
        assertNull(harness.store.tasks.getValue("r").pending)
    }

    @Test
    fun `a cut-off fire clears its occurrence and is reported`() = runTest {
        val harness = harness { _, _ -> Fired.CUT_OFF }
        harness.scheduler.schedule("r", When.Every(10.minutes, ORIGIN), UTC)

        advance(10.minutes)

        assertEquals(listOf("cut off r ${at(10.minutes)}"), harness.events.log)
        assertNull(harness.store.tasks.getValue("r").pending)
    }

    // Adding and cancelling.

    @Test
    fun `adding a task wakes the loop early, also when it had no task`() = runTest {
        val harness = harness()
        advance(1.minutes)
        harness.scheduler.schedule("first", once(2.minutes), UTC)
        advance(1.minutes)
        harness.scheduler.schedule("late", once(1.hours), UTC)
        advance(1.minutes)

        harness.scheduler.schedule("soon", once(5.minutes), UTC)
        advance(5.minutes)

        assertEquals(listOf("first" to at(2.minutes), "soon" to at(5.minutes)), harness.fires.map { it.id to it.at })
    }

    @Test
    fun `cancelling removes the task before it fires`() = runTest {
        val harness = harness()
        harness.scheduler.schedule("t", once(5.minutes), UTC)
        harness.scheduler.schedule("u", once(10.minutes), UTC)

        assertTrue(harness.scheduler.cancel("t"))
        assertFalse(harness.scheduler.cancel("t"))
        advance(15.minutes)

        assertEquals(listOf("u" to at(10.minutes)), harness.fires.map { it.id to it.at })
        assertTrue(harness.store.tasks.isEmpty())
    }

    @Test
    fun `a task cancelled as its occurrence is dispatched does not fire`() = runTest {
        lateinit var harness: Harness
        val cancelled = CompletableDeferred<Boolean>()
        val store = MemoryTaskStore<String> { task ->
            if (task.pending != null) launch { cancelled.complete(harness.scheduler.cancel(task.id)) }
        }
        harness = harness(store)
        harness.scheduler.schedule("t", once(5.minutes), UTC)

        advance(10.minutes)

        assertTrue(cancelled.await())
        assertEquals(emptyList(), harness.fires)
        assertTrue(store.tasks.isEmpty())
    }

    @Test
    fun `changing a task moves its next occurrence`() = runTest {
        val harness = harness()
        harness.scheduler.schedule("t", once(5.minutes), UTC)
        harness.scheduler.schedule("t", once(8.minutes), UTC)

        advance(10.minutes)

        assertEquals(listOf(at(8.minutes)), harness.fires.map { it.at })
    }

    @Test
    fun `a schedule never due after now is refused`() = runTest {
        val harness = harness()

        assertFailsWith<IllegalArgumentException> { harness.scheduler.schedule("t", once((-1).minutes), UTC) }
        assertFailsWith<IllegalArgumentException> {
            harness.scheduler.schedule("t", When.Cron(cron("0 0 30 2 *")), UTC)
        }
        assertTrue(harness.store.tasks.isEmpty())
    }

    @Test
    fun `the task list follows the store`() = runTest {
        val store = MemoryTaskStore(listOf(Task("kept", once(1.hours), UTC, at(1.hours), null)))
        val harness = harness(store)

        harness.scheduler.schedule("added", once(2.hours), UTC)

        assertEquals(listOf("kept", "added"), harness.scheduler.tasks().map { it.id })
    }

    // Restarts.

    @Test
    fun `a crashed one-shot task is cut off and removed`() = runTest {
        val occurrence = Occurrence(at((-1).minutes), Duration.ZERO)
        val store = MemoryTaskStore(listOf(Task("t", once((-1).minutes), UTC, null, Pending(occurrence, false))))

        val harness = harness(store)

        assertEquals(listOf("cut off t ${at((-1).minutes)} crashed"), harness.events.log)
        assertEquals(emptyList(), harness.fires)
        assertTrue(store.tasks.isEmpty())
    }

    @Test
    fun `a replay older than the window is reported missed`() = runTest {
        val occurrence = Occurrence(at((-25).hours), Duration.ZERO)
        val store = MemoryTaskStore(listOf(Task("t", once((-25).hours), UTC, null, Pending(occurrence, true))))

        val harness = harness(store)

        assertEquals(emptyList(), harness.fires)
        assertEquals(listOf("missed t ${at((-25).hours)}"), harness.events.log)
        assertTrue(store.tasks.isEmpty())
    }

    // Missed occurrences.

    @Test
    fun `a one-shot task missed beyond the window is reported and removed`() = runTest {
        for ((missed, window) in listOf((-25).hours to Scheduler.DEFAULT_MISSED_WINDOW, (-2).hours to 1.hours)) {
            val store = MemoryTaskStore(listOf(Task("t", once(missed), UTC, at(missed), null)))

            val harness = harness(store, window = window)

            assertEquals(emptyList(), harness.fires)
            assertEquals(listOf("missed t ${at(missed)}"), harness.events.log)
            assertTrue(store.tasks.isEmpty())
        }
    }

    @Test
    fun `a recurring task that missed many occurrences runs once late, then from the next one`() = runTest {
        val every = When.Every(1.hours, at((-72).hours + 50.minutes))
        val store = MemoryTaskStore(listOf(Task("r", every, UTC, at((-48).hours + 50.minutes), null)))

        val harness = harness(store)
        assertEquals(
            listOf(Triple(at((-10).minutes), 10.minutes, ORIGIN)),
            harness.fires.map {
                Triple(it.due, it.late, it.at)
            },
        )
        assertEquals(at(50.minutes), store.tasks.getValue("r").next)

        advance(2.hours)

        assertEquals(listOf(ORIGIN, at(50.minutes), at(110.minutes)), harness.fires.map { it.at })
        assertTrue(harness.fires.drop(1).all { it.late == Duration.ZERO })
        assertEquals(emptyList(), harness.events.log)
    }

    @Test
    fun `a recurring task whose latest miss is beyond the window is reported and skips ahead`() = runTest {
        val every = When.Every(48.hours, at((-30).hours))
        val store = MemoryTaskStore(listOf(Task("r", every, UTC, at((-30).hours), null)))

        val harness = harness(store)

        assertEquals(emptyList(), harness.fires)
        assertEquals(listOf("missed r ${at((-30).hours)}"), harness.events.log)
        assertEquals(at(18.hours), store.tasks.getValue("r").next)
        advance(18.hours)
        assertEquals(listOf(at(18.hours)), harness.fires.map { it.at })
    }

    @Test
    fun `a late cron run carries how late it is`() = runTest {
        val store = MemoryTaskStore(
            listOf(Task("c", When.Cron(cron("0 9 * * *")), UTC, instant("2026-10-17T09:00:00Z"), null)),
        )

        val harness = harness(store)

        assertEquals(
            listOf(instant("2026-10-18T09:00:00Z") to 1.hours),
            harness.fires.map { it.due to it.late },
        )
        assertEquals(instant("2026-10-19T09:00:00Z"), store.tasks.getValue("c").next)
    }

    @Test
    fun `a stop cancels the fires that run`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val harness = harness { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        harness.scheduler.schedule("t", once(1.minutes), UTC)
        advance(1.minutes)

        harness.job.cancelAndJoin()

        assertTrue(cancelled.isCompleted)
    }
}
