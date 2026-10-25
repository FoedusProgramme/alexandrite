package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.turn.FollowUp
import org.unitedsoftware.alexandrite.agent.turn.TurnPlan
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.agent.worker.AgentTicket
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.sdk.turn.TurnStatus
import java.time.Instant

/** The sub-agent runs that go on now. */
@Singleton
internal class DelegatedRuns {
    private val lock = Any()
    private val live = LinkedHashMap<RunId, LiveRun>()

    /** The profile of each run that ended lately, the oldest first. */
    private val gone = LinkedHashMap<RunId, AgentId>()

    fun started(run: LiveRun) {
        synchronized(lock) { live[run.delegation.run] = run }
    }

    /** Whether [run] goes on now. */
    fun isLive(run: RunId): Boolean = synchronized(lock) { run in live }

    fun ended(run: RunId) {
        synchronized(lock) {
            val ended = live.remove(run) ?: return@synchronized
            gone[run] = ended.delegation.profile.id
            if (gone.size > MAX_GONE) gone.remove(gone.keys.first())
        }
    }

    /** The profile of [run], which ended lately, null when it did not or ended too long ago to be known. */
    fun profileOf(run: RunId): AgentId? = synchronized(lock) { gone[run] }

    /**
     * Queues [message], which [turn] stands for, for the run [run] of [message]'s chat, which takes it in after its
     * current calls; an operator or the person the run works for may send it.
     */
    fun post(turn: TurnId, run: RunId, message: IncomingMessage, queuedAt: Instant): Admission {
        val target = synchronized(lock) {
            live[run]
                ?: return Admission.Refused(if (run in gone) RefusalReason.RUN_ENDED else RefusalReason.UNKNOWN_RUN)
        }
        val above = target.delegation.parent.turn
        if (above.chat != message.chat) return Admission.Refused(RefusalReason.UNKNOWN_RUN)
        val sender = message.sender
        if (!sender.isOperator && sender.address != above.actor?.address) {
            return Admission.Refused(RefusalReason.NOT_PERMITTED)
        }
        val ticket = AgentTicket(turn) { target.inbox.withdraw(it) }
        val plan = TurnPlan(
            id = turn,
            key = above.key,
            origin = message.chat,
            kind = TurnKind.MESSAGE,
            input = TurnSource.FromMessage(message),
            actor = sender,
            replyTarget = ReplyTarget.CALLER,
            trigger = message.ref,
            queuedAt = queuedAt,
        )
        val followUp = FollowUp(plan) { ticket.complete(it) }
        if (!target.inbox.post(followUp)) return Admission.Refused(RefusalReason.RUN_ENDED)
        return Admission.Accepted(ticket)
    }

    /** Cancels [run] with [cause], and every run below it when [tree], and returns whether [run] went on. */
    fun cancel(run: RunId, tree: Boolean, cause: CancellationException): Boolean {
        val cancelled = synchronized(lock) {
            val target = live[run] ?: return false
            // Parents first: a run cancelled before the run above it would hand that one a result to go on with
            listOf(target) + if (tree) below(target) else emptyList()
        }
        val by = (cause as? TurnCancelled)?.by
        cancelled.forEach {
            if (by != null) it.delegation.stoppedBy = by
            it.job.cancel(cause)
        }
        return true
    }

    /** Cancels the run whose turn is [turn] with [cause], and returns whether one went on. */
    fun cancelTurn(turn: TurnId, cause: CancellationException): Boolean {
        val run = synchronized(lock) { live.values.firstOrNull { it.turn == turn } } ?: return false
        run.job.cancel(cause)
        return true
    }

    /** The turns of the runs that go on below a turn from [chat], or from every chat when it is null. */
    fun statuses(chat: ChatAddress?): List<TurnStatus> = synchronized(lock) {
        live.values.mapNotNull { run ->
            val turn = run.delegation.turn?.takeIf { chat == null || it.chat == chat } ?: return@mapNotNull null
            TurnStatus.builder(turn, TurnPhase.RUNNING, run.startedAt).startedAt(run.startedAt).build()
        }
    }

    /** The runs below [run], while the caller holds [lock]. */
    private fun below(run: LiveRun): List<LiveRun> {
        val children = live.values.filter { it.delegation.lineage.parentTurn == run.turn }
        return children + children.flatMap(::below)
    }
}

/** The run of [delegation], whose turn [turn] runs as [job] since [startedAt] and takes its follow-ups from [inbox]. */
internal class LiveRun(
    val delegation: Delegation,
    val turn: TurnId,
    val job: Job,
    val startedAt: Instant,
    val inbox: RunInbox,
)

/** How many ended runs [DelegatedRuns] remembers, to refuse a message to one as ended rather than unknown. */
private const val MAX_GONE = 1024
