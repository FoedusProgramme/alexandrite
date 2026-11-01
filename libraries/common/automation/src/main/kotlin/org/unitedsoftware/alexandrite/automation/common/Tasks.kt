package org.unitedsoftware.alexandrite.automation.common

import dev.drewhamilton.poko.Poko
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration

/** One firing of a task. */
@InternalAutomationApi
@Poko
public class Occurrence(
    public val due: Instant,
    /** How long after [due] it fired */
    public val late: Duration,
)

/** An occurrence that fired and has no outcome yet. */
@InternalAutomationApi
@Poko
public class Pending(
    public val occurrence: Occurrence,
    /** Whether it is fired again at the next start */
    public val replay: Boolean,
)

/** A scheduled item, as the [TaskStore] keeps it. */
@InternalAutomationApi
@Poko
public class Task<K : Any>(
    public val id: K,
    public val schedule: When,
    /** The zone [schedule]'s local times are read in */
    public val zone: ZoneId,
    /** The next due instant, null once there is none */
    public val next: Instant?,
    public val pending: Pending?,
)

internal fun <K : Any> Task<K>.with(next: Instant? = this.next, pending: Pending? = this.pending): Task<K> =
    Task(id, schedule, zone, next, pending)

/** What became of a fired occurrence. */
@InternalAutomationApi
public enum class Fired {
    DONE,

    /** The host shut down before anything was shown: fire it again at the next start. */
    REPLAY,

    /** The host shut down after something was shown. */
    CUT_OFF,
}

@InternalAutomationApi
public interface TaskStore<K : Any> {
    public suspend fun load(): List<Task<K>>

    /** Saves [task] in place of any task with its id. */
    public suspend fun save(task: Task<K>)

    public suspend fun remove(id: K)
}

@InternalAutomationApi
public fun interface Trigger<K : Any> {
    /** A cancellation leaves [occurrence] pending, which the next start cuts off. */
    public suspend fun fire(task: Task<K>, occurrence: Occurrence): Fired
}

/** What the [Scheduler] reports, in calls that must return quickly. */
@InternalAutomationApi
public interface SchedulerEvents<K : Any> {
    /** [occurrence] was cut off: [crashed] when it was found pending with no outcome at start. */
    public fun cutOff(task: Task<K>, occurrence: Occurrence, crashed: Boolean) {}

    /** The occurrence due at [due] passed more than the missed window ago; a one-shot task is then removed. */
    public fun missed(task: Task<K>, due: Instant) {}

    /** The occurrence due at [due] was skipped because the previous one still runs. */
    public fun skipped(task: Task<K>, due: Instant) {}

    /** [Trigger.fire] threw [error], which counts as [Fired.DONE]. */
    public fun failed(task: Task<K>, occurrence: Occurrence, error: Exception) {}
}
