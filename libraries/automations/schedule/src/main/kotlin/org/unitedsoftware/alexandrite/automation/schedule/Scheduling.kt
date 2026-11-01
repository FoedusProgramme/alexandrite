package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.automation.common.Fired
import org.unitedsoftware.alexandrite.automation.common.Occurrence
import org.unitedsoftware.alexandrite.automation.common.Scheduler
import org.unitedsoftware.alexandrite.automation.common.SchedulerEvents
import org.unitedsoftware.alexandrite.automation.common.Task
import org.unitedsoftware.alexandrite.automation.common.TimeSource
import org.unitedsoftware.alexandrite.automation.common.Trigger
import org.unitedsoftware.alexandrite.automation.common.When
import org.unitedsoftware.alexandrite.automation.common.restarting
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.runtime.WakeScheduler
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnTicket
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the schedule of [tasks] from the open until the stop, starting it again with a growing pause when it fails,
 * and fires each occurrence as a reminder turn in its task's chat; [wakes] is told when the next task is due.
 */
@Singleton
internal class Scheduling(
    private val config: ScheduleConfig,
    private val tasks: TaskRecords,
    private val initiator: TurnInitiator,
    private val notices: Notices,
    private val scope: PluginScope,
    private val info: PluginInfo,
    private val wakes: WakeScheduler?,
    clock: Clock,
    time: TimeSource?,
) : Lifecycle {
    val time: TimeSource = time ?: TimeSource.of(clock)

    private val closing = MutableStateFlow(false)
    private val trigger = Trigger<String> { task, occurrence -> fire(task, occurrence) }
    private val events = Events()

    @Volatile
    private var current = newScheduler()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var opened: Instant? = null

    /** The scheduler that runs now, or that ran last while it waits to start again. */
    fun scheduler(): Scheduler<String> = current

    override suspend fun onOpen() {
        opened = time.now()
        job = scope.launch { supervise() }
    }

    override suspend fun onClose() {
        closing.value = true
    }

    override fun onStop() {
        job?.cancel()
    }

    private fun newScheduler(): Scheduler<String> =
        Scheduler(tasks, trigger, events, time, config.missedWindowHours.hours)

    private suspend fun supervise() {
        var fresh = true
        restarting(
            time,
            FIRST_PAUSE,
            LAST_PAUSE,
            STEADY,
            failed = { e, pause -> logger.error("The schedule stopped, and starts again in {}", pause, e) },
        ) {
            if (!fresh) current = newScheduler()
            fresh = false
            current.run()
        }
    }

    private suspend fun fire(task: Task<String>, occurrence: Occurrence): Fired {
        val record = tasks.find(task.id)
        if (record == null) {
            logger.warn("Scheduled task {} came due without its record: skipped", task.id)
            return Fired.DONE
        }
        val prompt = framing(task.id, record, occurrence)
        val turn = InitiatedTurn.builder(AgentChatKey(record.key.agent, record.chat), TurnKind.REMINDER, prompt)
            .route(ReplyRoute.ToChat(record.chat))
            .trust(if (record.mandate == null) Trust.UNTRUSTED else Trust.TRUSTED)
            .mandate(record.mandate)
            .build()
        logger.info("Scheduled task {} of {} fires, due {}", task.id, record.key, occurrence.due)
        var tries = 0
        while (true) {
            when (val admission = initiator.initiate(turn)) {
                is Admission.Accepted -> return fired(outcome(admission.ticket))

                is Admission.Refused -> {
                    if (!opening(admission.reason)) {
                        refused(task.id, record, occurrence, admission.reason)
                        return Fired.DONE
                    }
                    if (closing.value || ++tries > OPEN_TRIES) return Fired.REPLAY
                    withTimeoutOrNull(1.seconds) { closing.first { it } }
                }

                else -> {
                    logger.warn("Scheduled task {} was not taken in, as {}: skipped", task.id, admission)
                    return Fired.DONE
                }
            }
        }
    }

    /** Whether [reason] may come from a part of the host that is still opening. */
    private fun opening(reason: RefusalReason): Boolean = when (reason) {
        RefusalReason.SHUTTING_DOWN -> true
        RefusalReason.UNKNOWN_CHAT, RefusalReason.NO_AGENT -> time.now() < (opened ?: return true).plus(STARTING)
        else -> false
    }

    /** The outcome of [ticket], also when the stop cancels the wait just after the agent ended its turn. */
    private suspend fun outcome(ticket: TurnTicket): TurnOutcome = try {
        ticket.outcome()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { withTimeoutOrNull(SETTLE) { ticket.outcome() } } ?: throw e
    }

    private fun fired(outcome: TurnOutcome): Fired = when {
        outcome !is TurnOutcome.ShutDown -> Fired.DONE
        outcome.replayable -> Fired.REPLAY
        else -> Fired.CUT_OFF
    }

    private fun refused(id: String, record: TaskRecord, occurrence: Occurrence, reason: RefusalReason) {
        logger.warn("Scheduled task {} of {} was refused ({}): not run", id, record.key, reason)
        notices.send(record.chat) {
            it.text(
                TextKeys.NOTICE_REFUSED,
                "id" to id,
                "due" to shown(occurrence.due, it.zone),
                "reason" to reason,
                "prompt" to brief(record.prompt),
            )
        }
    }

    private inner class Events : SchedulerEvents<String> {
        override fun cutOff(task: Task<String>, occurrence: Occurrence, crashed: Boolean) {
            val record = tasks.find(task.id) ?: return
            logger.warn("Scheduled task {} of {} was cut off (crashed: {})", task.id, record.key, crashed)
            notices.send(record.chat) {
                it.text(TextKeys.NOTICE_CUT_OFF, "id" to task.id, "prompt" to brief(record.prompt))
            }
        }

        override fun missed(task: Task<String>, due: Instant) {
            val record = tasks.find(task.id) ?: return
            logger.warn("Scheduled task {} of {} missed its run due {}", task.id, record.key, due)
            val key = if (task.schedule is When.Once) TextKeys.NOTICE_MISSED_ONCE else TextKeys.NOTICE_MISSED_RECURRING
            notices.send(record.chat) {
                it.text(key, "id" to task.id, "due" to shown(due, it.zone), "prompt" to brief(record.prompt))
            }
        }

        override fun skipped(task: Task<String>, due: Instant) {
            logger.info("Scheduled task {} skips its run due {}: the previous one still runs", task.id, due)
        }

        override fun failed(task: Task<String>, occurrence: Occurrence, error: Exception) {
            logger.warn("Scheduled task {} failed to fire, due {}", task.id, occurrence.due, error)
        }

        override fun nextDue(at: Instant?) {
            val wakes = wakes ?: return
            try {
                wakes.wakeAt(info.id, at)
            } catch (e: Exception) {
                logger.warn("The host's wake for {} was not set", at ?: "no task", e)
            }
        }
    }

    private companion object {
        val FIRST_PAUSE = 1.seconds
        val LAST_PAUSE = 5.minutes
        val STEADY = 10.minutes
        val SETTLE = 1.seconds

        /** How long after the open a chat's channel or agent may still be missing. */
        val STARTING: java.time.Duration = java.time.Duration.ofMinutes(2)

        /** How many seconds a task waits for the agent to open. */
        const val OPEN_TRIES = 120
    }
}

/** The input of the turn of task [id] for [occurrence]. */
internal fun framing(id: String, record: TaskRecord, occurrence: Occurrence): String {
    val zone = record.zoneId
    val late = occurrence.late.inWholeMinutes.takeIf { it > 0 }?.let { ", running $it min late" }.orEmpty()
    return "Scheduled task $id, set by ${record.creatorName} on ${day(record.createdInstant, zone)}; " +
        "due ${shown(occurrence.due, zone)}$late. Do what it asks; it was scheduled earlier, so do not schedule it " +
        "again.\n\n${record.prompt}"
}

/** [prompt] cut to a line people read at a glance. */
internal fun brief(prompt: String): String {
    val line = prompt.trim().replace(Regex("\\s+"), " ")
    return if (line.length <= BRIEF) line else line.take(BRIEF - 1).trimEnd() + "…"
}

private const val BRIEF = 80

private val logger = LoggerFactory.getLogger(Scheduling::class.java)
