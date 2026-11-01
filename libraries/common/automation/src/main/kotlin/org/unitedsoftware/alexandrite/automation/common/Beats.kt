package org.unitedsoftware.alexandrite.automation.common

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** The part of the day beats run in, from [start] up to [end], wrapping midnight when [end] comes first. */
@InternalAutomationApi
@Poko
public class ActiveHours(public val start: LocalTime, public val end: LocalTime) {
    /** Equal ends make the whole day. */
    public operator fun contains(time: LocalTime): Boolean = when {
        start < end -> time >= start && time < end
        start > end -> time >= start || time < end
        else -> true
    }

    public companion object {
        /** Reads `HH:mm-HH:mm`, such as `22:00-06:00`. */
        public fun parse(text: String): ActiveHours {
            val ends = text.split('-').map { it.trim() }
            require(ends.size == 2) { "Active hours are written as HH:mm-HH:mm, not '$text'." }
            return try {
                ActiveHours(LocalTime.parse(ends[0]), LocalTime.parse(ends[1]))
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("Active hours are written as HH:mm-HH:mm, not '$text'.", e)
            }
        }
    }
}

/** Beat [id] every [every] from the start, only within [activeHours] read in [zone] when there are any. */
@InternalAutomationApi
@Poko
public class BeatPlan<K : Any>(
    public val id: K,
    public val every: Duration,
    public val zone: ZoneId,
    public val activeHours: ActiveHours? = null,
) {
    init {
        require(every.isPositive()) { "A beat interval must be positive, not $every." }
    }

    /** The first beat after [now] of the beats every [every] from [start], [Instant.MAX] past the end of time. */
    public fun dueAfter(start: Instant, now: Instant): Instant =
        stepAfter(start, every.toJavaDuration(), now) ?: Instant.MAX

    /** Whether a beat due at [due] falls within the active hours. */
    public fun isActive(due: Instant): Boolean = activeHours == null || LocalTime.ofInstant(due, zone) in activeHours

    /** The first beat after [now] from [start] that falls within the active hours, null when none comes by [until]. */
    public fun nextActive(start: Instant, now: Instant, until: Instant): Instant? =
        generateSequence(dueAfter(start, now)) { it.plus(every.toJavaDuration()) }
            .takeWhile { it <= until }
            .firstOrNull(::isActive)
}

@InternalAutomationApi
public fun interface Beater<K : Any> {
    public suspend fun beat(id: K)
}

@InternalAutomationApi
public enum class BeatSkip {
    /** It fell outside the active hours. */
    INACTIVE,

    /** The previous beat still ran. */
    BUSY,
}

/** What [Beats] reports, in calls that must return quickly. */
@InternalAutomationApi
public interface BeatEvents<K : Any> {
    public fun skipped(id: K, due: Instant, why: BeatSkip) {}

    /** [Beater.beat] threw [error]. */
    public fun failed(id: K, error: Exception) {}
}

/** Beats each plan of [plans] through [beater], each beat in its own coroutine. */
@InternalAutomationApi
public class Beats<K : Any>(
    plans: List<BeatPlan<K>>,
    private val beater: Beater<K>,
    private val events: BeatEvents<K> = object : BeatEvents<K> {},
    private val time: TimeSource = TimeSource.system,
) {
    private val plans = MutableStateFlow(distinct(plans))

    /** Beats [plans] in place of the plans so far, each on its grid from the start, letting running beats end. */
    public fun update(plans: List<BeatPlan<K>>) {
        this.plans.value = distinct(plans)
    }

    /** Beats from [start] until cancelled, which cancels the beats still running. */
    public suspend fun run(start: Instant = time.now()): Nothing = coroutineScope {
        val loops = HashMap<K, Pair<BeatPlan<K>, Job>>()
        val running = ConcurrentHashMap<K, Job>()
        plans.collect { plans ->
            val ids = plans.map { it.id }.toSet()
            for (id in loops.keys.filter { it !in ids }) loops.remove(id)?.second?.cancelAndJoin()
            for (plan in plans) {
                val loop = loops[plan.id]
                if (loop?.first == plan) continue
                loop?.second?.cancelAndJoin()
                loops[plan.id] = plan to launch { beat(this@coroutineScope, plan, start, running) }
            }
        }
    }

    private suspend fun beat(
        scope: CoroutineScope,
        plan: BeatPlan<K>,
        start: Instant,
        running: MutableMap<K, Job>,
    ): Nothing {
        var due = plan.dueAfter(start, time.now())
        while (true) {
            time.sleepUntil(due)
            when {
                !plan.isActive(due) -> events.skipped(plan.id, due, BeatSkip.INACTIVE)

                running[plan.id]?.isActive == true -> events.skipped(plan.id, due, BeatSkip.BUSY)

                else -> running[plan.id] = scope.launch {
                    try {
                        beater.beat(plan.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        events.failed(plan.id, e)
                    }
                }
            }
            due = plan.dueAfter(start, time.now())
        }
    }
}

private fun <K : Any> distinct(plans: List<BeatPlan<K>>): List<BeatPlan<K>> = plans.also {
    require(plans.map { it.id }.toSet().size == plans.size) { "Beat ids must be distinct." }
}
