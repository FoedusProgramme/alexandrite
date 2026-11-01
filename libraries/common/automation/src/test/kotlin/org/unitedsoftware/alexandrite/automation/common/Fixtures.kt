package org.unitedsoftware.alexandrite.automation.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val ORIGIN: Instant = Instant.parse("2026-10-18T10:00:00Z")

val UTC: ZoneId = ZoneOffset.UTC
val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

/** Virtual time that starts at [origin]. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.virtualTime(origin: Instant = ORIGIN): TimeSource =
    TimeSource.delaying { origin.plusMillis(testScheduler.currentTime) }

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.now(origin: Instant = ORIGIN): Instant = origin.plusMillis(testScheduler.currentTime)

fun local(text: String): LocalDateTime = LocalDateTime.parse(text)

fun instant(text: String): Instant = Instant.parse(text)

fun cron(expression: String): CronExpression = CronExpression.parse(expression)

/** Calls [saved] with each task it saves. */
class MemoryTaskStore<K : Any>(initial: List<Task<K>> = emptyList(), private val saved: (Task<K>) -> Unit = {}) :
    TaskStore<K> {
    val tasks = LinkedHashMap<K, Task<K>>().apply { initial.forEach { put(it.id, it) } }
    val saves = mutableListOf<Task<K>>()

    override suspend fun load(): List<Task<K>> = tasks.values.toList()

    override suspend fun save(task: Task<K>) {
        saves += task
        tasks[task.id] = task
        saved(task)
    }

    override suspend fun remove(id: K) {
        tasks.remove(id)
    }
}

class RecordingEvents : SchedulerEvents<String> {
    val log = mutableListOf<String>()

    override fun cutOff(task: Task<String>, occurrence: Occurrence, crashed: Boolean) {
        log += "cut off ${task.id} ${occurrence.due}" + if (crashed) " crashed" else ""
    }

    override fun missed(task: Task<String>, due: Instant) {
        log += "missed ${task.id} $due"
    }

    override fun skipped(task: Task<String>, due: Instant) {
        log += "skipped ${task.id} $due"
    }

    override fun failed(task: Task<String>, occurrence: Occurrence, error: Exception) {
        log += "failed ${task.id} ${error.message}"
    }
}
