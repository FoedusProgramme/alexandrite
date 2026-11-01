package org.unitedsoftware.alexandrite.automation.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class BeatsTest {
    private class Recorder : BeatEvents<String> {
        val log = mutableListOf<String>()

        override fun skipped(id: String, due: Instant, why: BeatSkip) {
            log += "$why $id $due"
        }

        override fun failed(id: String, error: Exception) {
            log += "failed $id ${error.message}"
        }
    }

    private fun at(offset: Duration): Instant = ORIGIN.plusMillis(offset.inWholeMilliseconds)

    private fun TestScope.beats(
        plans: List<BeatPlan<String>>,
        events: Recorder = Recorder(),
        beat: suspend (String) -> Unit = {},
    ): MutableList<Pair<String, Instant>> {
        val beats = mutableListOf<Pair<String, Instant>>()
        val runner = Beats(plans, { id ->
            beats += id to now()
            beat(id)
        }, events, virtualTime())
        backgroundScope.launch { runner.run() }
        runCurrent()
        return beats
    }

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun `the first beat comes one interval after the start`() = runTest {
        val beats = beats(listOf(BeatPlan("a", 30.minutes, UTC), BeatPlan("b", 50.minutes, UTC)))

        advance(29.minutes)
        assertEquals(emptyList(), beats)
        advance(2.hours)

        assertEquals(
            listOf("a" to 30, "b" to 50, "a" to 60, "a" to 90, "b" to 100, "a" to 120),
            beats.map { (id, at) -> id to (at.epochSecond - ORIGIN.epochSecond).toInt() / 60 },
        )
    }

    @Test
    fun `a failing beat is reported and the next one comes`() = runTest {
        val events = Recorder()
        val beats = beats(listOf(BeatPlan("a", 30.minutes, UTC)), events) { error("boom") }

        advance(1.hours)

        assertEquals(2, beats.size)
        assertEquals(listOf("failed a boom", "failed a boom"), events.log)
    }

    @Test
    fun `stopping cancels the running beats`() = runTest {
        var cancelled = false
        val runner = Beats(
            listOf(BeatPlan("a", 30.minutes, UTC)),
            {
                try {
                    delay(1.hours)
                } finally {
                    cancelled = true
                }
            },
            time = virtualTime(),
        )
        val job = backgroundScope.launch { runner.run() }
        advance(31.minutes)

        job.cancelAndJoin()

        assertTrue(cancelled)
    }

    @Test
    fun `updated plans beat on their own grid from the start at once, and a removed plan's running beat ends`() =
        runTest {
            val events = Recorder()
            val beats = mutableListOf<Pair<String, Instant>>()
            val runner = Beats(listOf(BeatPlan("a", 30.minutes, UTC), BeatPlan("b", 1.hours, UTC)), { id ->
                beats += id to now()
                if (id == "b") delay(4.hours)
            }, events, virtualTime())
            backgroundScope.launch { runner.run() }
            advance(70.minutes)

            runner.update(listOf(BeatPlan("a", 1.hours, UTC), BeatPlan("c", 40.minutes, UTC)))
            advance(2.hours)
            runner.update(listOf(BeatPlan("a", 1.hours, UTC), BeatPlan("b", 1.hours, UTC)))
            advance(1.hours)

            assertEquals(
                listOf(
                    "a" to 30, "b" to 60, "a" to 60, "c" to 80, "a" to 120, "c" to 120, "c" to 160, "a" to 180,
                    "a" to 240,
                ),
                beats.map { (id, at) -> id to (at.epochSecond - ORIGIN.epochSecond).toInt() / 60 },
            )
            assertEquals(listOf("BUSY b ${at(240.minutes)}"), events.log)
        }

    @Test
    fun `active hours contain their start and not their end`() {
        val day = ActiveHours.parse("08:00-23:00")
        assertTrue(LocalTime.of(8, 0) in day)
        assertTrue(LocalTime.of(22, 59) in day)
        assertFalse(LocalTime.of(23, 0) in day)
        assertFalse(LocalTime.of(7, 59) in day)

        val night = ActiveHours.parse("22:00-06:00")
        assertTrue(LocalTime.of(22, 0) in night)
        assertTrue(LocalTime.of(0, 0) in night)
        assertTrue(LocalTime.of(5, 59) in night)
        assertFalse(LocalTime.of(6, 0) in night)
        assertFalse(LocalTime.of(12, 0) in night)

        assertTrue(LocalTime.of(3, 0) in ActiveHours.parse("00:00-00:00"))
    }

    @Test
    fun `the next active beat is on the grid from the start, past the inactive hours`() {
        val plan = BeatPlan("a", 1.hours, UTC, ActiveHours.parse("13:00-14:00"))

        assertEquals(at(1.hours), plan.dueAfter(ORIGIN, ORIGIN))
        assertEquals(at(2.hours), plan.dueAfter(ORIGIN, at(90.minutes)))
        assertEquals(at(3.hours), plan.nextActive(ORIGIN, at(90.minutes), at(24.hours)))
        assertEquals(null, plan.nextActive(ORIGIN, at(90.minutes), at(150.minutes)))
    }

    @Test
    fun `a beat past the end of time never comes`() {
        val plan = BeatPlan("a", 1.hours, UTC)

        assertEquals(Instant.MAX, plan.dueAfter(ORIGIN, Instant.MAX))
    }

    @Test
    fun `malformed active hours and plans are refused`() {
        for (text in listOf("", "08:00", "8-12", "08:00-25:00", "08:00-12:00-14:00")) {
            assertFailsWith<IllegalArgumentException>(text) { ActiveHours.parse(text) }
        }
        assertFailsWith<IllegalArgumentException> { BeatPlan("a", Duration.ZERO, UTC) }
        assertFailsWith<IllegalArgumentException> {
            Beats(listOf(BeatPlan("a", 1.hours, UTC), BeatPlan("a", 2.hours, UTC)), {})
        }
    }
}
