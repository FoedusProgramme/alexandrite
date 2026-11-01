package org.unitedsoftware.alexandrite.automation.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * Fires each task of [store] through [trigger] when it is due, each in its own coroutine, and at most once per
 * occurrence: an occurrence is saved as pending, with the task's next due instant, before it fires.
 *
 * An occurrence fires late when it is at most [missedWindow] late and is reported missed otherwise; of the
 * occurrences a task missed, only the latest fires.
 */
@InternalAutomationApi
public class Scheduler<K : Any>(
    private val store: TaskStore<K>,
    private val trigger: Trigger<K>,
    private val events: SchedulerEvents<K> = object : SchedulerEvents<K> {},
    private val time: TimeSource = TimeSource.system,
    private val missedWindow: Duration = DEFAULT_MISSED_WINDOW,
) {
    init {
        require(!missedWindow.isNegative()) { "The missed window may not be negative." }
    }

    private val mutex = Mutex()
    private val tasks = LinkedHashMap<K, Task<K>>()
    private val running = HashSet<K>()

    @Volatile
    private var snapshot: List<Task<K>> = emptyList()
    private val loaded = CompletableDeferred<Unit>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean()

    /** The tasks, empty until [run] has loaded them. */
    public fun tasks(): List<Task<K>> = snapshot

    /**
     * Saves task [id], due by [schedule] in [zone], in place of any with that id, once [run] has loaded the store.
     *
     * @throws IllegalArgumentException when [schedule] is not due after now
     */
    public suspend fun schedule(id: K, schedule: When, zone: ZoneId): Task<K> {
        loaded.await()
        val task = mutex.withLock {
            val next = requireNotNull(schedule.next(time.now(), zone)) { "$schedule is not due after now." }
            Task(id, schedule, zone, next, tasks[id]?.pending).also { put(it) }
        }
        wake.trySend(Unit)
        return task
    }

    /** Removes task [id] once [run] has loaded the store, answering false when there is none. */
    public suspend fun cancel(id: K): Boolean {
        loaded.await()
        val removed = mutex.withLock { (id in tasks).also { if (it) drop(id) } }
        if (removed) wake.trySend(Unit)
        return removed
    }

    /** Loads the store, then fires tasks until cancelled, which cancels the fires still running. */
    public suspend fun run(): Nothing {
        check(started.compareAndSet(false, true)) { "A scheduler runs only once." }
        coroutineScope {
            val start = try {
                mutex.withLock { load() }
            } catch (e: Throwable) {
                loaded.completeExceptionally(e)
                throw e
            }
            loaded.complete(Unit)
            dispatch(this, start)
            loop(this)
        }
    }

    private class Plan<K : Any> {
        val fires = mutableListOf<Pair<Task<K>, Occurrence>>()
        val notices = mutableListOf<() -> Unit>()
    }

    private suspend fun load(): Plan<K> {
        val plan = Plan<K>()
        val now = time.now()
        for (task in store.load()) tasks[task.id] = task
        snapshot = tasks.values.toList()
        for (task in tasks.values.toList()) {
            val pending = task.pending ?: continue
            val due = pending.occurrence.due
            when {
                !pending.replay -> {
                    settle(task.with(pending = null))
                    plan.notices += { events.cutOff(task, pending.occurrence, crashed = true) }
                }

                lateness(due, now) > missedWindow -> {
                    settle(task.with(pending = null))
                    plan.notices += { events.missed(task, due) }
                }

                else -> {
                    val next = task.next?.let { if (it > now) it else task.schedule.next(now, task.zone) }
                    begin(plan, task.with(next = next), Occurrence(due, lateness(due, now)))
                }
            }
        }
        return plan
    }

    private suspend fun loop(scope: CoroutineScope): Nothing {
        while (true) {
            val (plan, earliest) = mutex.withLock { due() }
            dispatch(scope, plan)
            sleep(earliest)
        }
    }

    private suspend fun due(): Pair<Plan<K>, Instant?> {
        val plan = Plan<K>()
        val now = time.now()
        val due = tasks.values
            .filter { task -> task.next.let { it != null && it <= now } && task.pending?.replay != true }
            .sortedBy { it.next }
        for (task in due) {
            val next = task.next!!
            val following = task.schedule.next(now, task.zone)
            if (task.id in running) {
                put(task.with(next = following))
                plan.notices += { events.skipped(task, next) }
                continue
            }
            val latest = latestDue(task, next, now)
            if (latest == null) {
                settle(task.with(next = following))
                plan.notices += { events.missed(task, next) }
            } else {
                begin(plan, task.with(next = following), Occurrence(latest, lateness(latest, now)))
            }
        }
        val earliest = tasks.values.filter { it.pending?.replay != true }.mapNotNull { it.next }.minOrNull()
        return plan to earliest
    }

    /** The latest occurrence of [task] from [next] to [now] that is within the missed window. */
    private fun latestDue(task: Task<K>, next: Instant, now: Instant): Instant? {
        val windowStart = try {
            now.minus(missedWindow.toJavaDuration())
        } catch (_: DateTimeException) {
            Instant.MIN
        } catch (_: ArithmeticException) {
            Instant.MIN
        }
        var latest = next.takeIf { it >= windowStart }
        var cursor = if (latest != null) next else windowStart.minusNanos(1)
        while (true) {
            val occurrence = task.schedule.next(cursor, task.zone) ?: break
            if (occurrence > now) break
            latest = occurrence
            cursor = occurrence
        }
        return latest
    }

    private suspend fun begin(plan: Plan<K>, task: Task<K>, occurrence: Occurrence) {
        val firing = task.with(pending = Pending(occurrence, replay = false))
        put(firing)
        running += task.id
        plan.fires += firing to occurrence
    }

    private fun dispatch(scope: CoroutineScope, plan: Plan<K>) {
        plan.notices.forEach { it() }
        for ((task, occurrence) in plan.fires) scope.launch { fire(task, occurrence) }
    }

    private suspend fun fire(task: Task<K>, occurrence: Occurrence) {
        val current = mutex.withLock {
            (tasks[task.id]?.pending?.occurrence == occurrence).also { if (!it) running -= task.id }
        }
        if (!current) return
        val outcome = try {
            trigger.fire(task, occurrence)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { mutex.withLock { running -= task.id } }
            throw e
        } catch (e: Exception) {
            events.failed(task, occurrence, e)
            Fired.DONE
        }
        withContext(NonCancellable) {
            mutex.withLock { finish(task.id, occurrence, outcome) }
        }
        if (outcome == Fired.CUT_OFF) events.cutOff(task, occurrence, crashed = false)
        wake.trySend(Unit)
    }

    private suspend fun finish(id: K, occurrence: Occurrence, outcome: Fired) {
        running -= id
        val current = tasks[id]?.takeIf { it.pending?.occurrence == occurrence } ?: return
        if (outcome == Fired.REPLAY) {
            put(current.with(pending = Pending(occurrence, replay = true)))
        } else {
            settle(current.with(pending = null))
        }
    }

    private suspend fun sleep(until: Instant?) = coroutineScope {
        val sleeper = launch { if (until == null) awaitCancellation() else time.sleepUntil(until) }
        select<Unit> {
            wake.onReceive {}
            sleeper.onJoin {}
        }
        sleeper.cancel()
    }

    /** Saves [task], or removes it when it is neither due again nor pending. */
    private suspend fun settle(task: Task<K>) {
        if (task.next == null && task.pending == null) drop(task.id) else put(task)
    }

    private suspend fun put(task: Task<K>) {
        store.save(task)
        tasks[task.id] = task
        snapshot = tasks.values.toList()
    }

    private suspend fun drop(id: K) {
        store.remove(id)
        tasks.remove(id)
        snapshot = tasks.values.toList()
    }

    private fun lateness(due: Instant, now: Instant): Duration =
        java.time.Duration.between(due, now).toKotlinDuration().coerceAtLeast(Duration.ZERO)

    public companion object {
        public val DEFAULT_MISSED_WINDOW: Duration = 24.hours
    }
}
