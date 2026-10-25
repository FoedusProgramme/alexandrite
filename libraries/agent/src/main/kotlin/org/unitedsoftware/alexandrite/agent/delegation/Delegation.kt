package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import org.unitedsoftware.alexandrite.agent.job.EndedJob
import org.unitedsoftware.alexandrite.agent.job.JobInbox
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.agent.job.jobPlan
import org.unitedsoftware.alexandrite.agent.tool.DelegatingTurn
import org.unitedsoftware.alexandrite.agent.tool.RunChain
import org.unitedsoftware.alexandrite.agent.tool.ToolGlob
import org.unitedsoftware.alexandrite.agent.tool.Toolset
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.agent.turn.FollowUp
import org.unitedsoftware.alexandrite.agent.turn.FollowUps
import org.unitedsoftware.alexandrite.agent.turn.TurnPermit
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import java.time.Clock
import java.time.Instant

/** The name of the tool that delegates a task to a sub-agent. */
internal const val DELEGATE_TOOL: String = "agent.delegate"

/** How deep a tree of sub-agent runs may grow below its root turn. */
internal const val MAX_DEPTH: Int = 3

/** A sub-agent run of [profile] on [task] in [conversation], which a call of [parent]'s turn started as [run]. */
internal class Delegation(
    val run: RunId,
    /** The run as a channel shows it. */
    val shown: DelegatedRun,
    val lineage: TurnLineage,
    val conversation: ConversationInfo,
    val profile: AgentProfile,
    val task: String,
    val parent: DelegatingTurn,
    val chain: RunChain,
    val background: Boolean = false,
    /** Why the run is tainted by the earlier run it goes on from, null when there is none or it is clean. */
    val resumed: String? = null,
    /** Hands each untrusted content the run takes in to the call that delegated it, as it comes. */
    private val report: (String) -> Unit = {},
) {
    /**
     * The tools of [all] that the run offers: those its profile allows that the turn above may call, without
     * [DELEGATE_TOOL] at the last depth, which the run may still call.
     */
    fun tools(all: List<Tool>): Toolset {
        val allow = profile.toolsAllow.map(ToolGlob::of)
        val deny = profile.toolsDeny.map(ToolGlob::of)
        val chosen = all.filter { tool ->
            val name = tool.definition.name
            name in parent.callable && allow.any { it.matches(name) } && deny.none { it.matches(name) }
        }
        val last = lineage.depth >= MAX_DEPTH
        val offered = chosen.filterNot { last && it.definition.name == DELEGATE_TOOL }
        val withheld = (chosen - offered.toSet()).mapTo(mutableSetOf()) { it.definition.name }
        return Toolset(offered, all - offered.toSet(), withheld)
    }

    /** The run's turn, null until it started. */
    @Volatile
    var turn: TurnInfo? = null
        private set

    /** The taint of the run's turn, null until it started. */
    @Volatile
    var taint: TurnTaint? = null
        private set

    /** The person who stopped the run, null while nobody did. */
    @Volatile
    var stoppedBy: ChatUser? = null

    /** Counts the taint of the earlier run that the run goes on from as its own in [taint], the taint of its turn. */
    fun opened(taint: TurnTaint) {
        resumed?.let(taint::note)
    }

    /** Notes that the run's turn [turn] started with [taint], whose untrusted content goes to the delegating call. */
    fun started(turn: TurnInfo, taint: TurnTaint) {
        this.taint = taint
        this.turn = turn
        taint.onNoted(report)
    }
}

/**
 * The follow-ups of a sub-agent run in [conversation], its own: the reports of the background runs it started, and the
 * messages that people send the run itself, which no other turn takes.
 */
internal class RunInbox(private val conversation: ConversationId, private val jobs: Jobs, private val clock: Clock) :
    FollowUps,
    JobInbox {
    private val lock = Any()
    private val reports = ArrayDeque<FollowUp>()
    private val sent = ArrayDeque<FollowUp>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private var closed = false

    override fun offer(job: EndedJob): Boolean {
        synchronized(lock) {
            if (closed) return false
            reports += FollowUp(jobPlan(job, clock.instant())) {}
        }
        changed.trySend(Unit)
        return true
    }

    override fun ended() {
        changed.trySend(Unit)
    }

    /** Queues [followUp], a message sent to the run, and returns false once the run takes no more. */
    fun post(followUp: FollowUp): Boolean {
        synchronized(lock) {
            if (closed) return false
            sent += followUp
        }
        changed.trySend(Unit)
        return true
    }

    /** Drops the queued message of [turn], and returns whether it was queued. */
    fun withdraw(turn: TurnId): Boolean {
        val followUp = synchronized(lock) {
            sent.firstOrNull { it.plan.id == turn }?.also(sent::remove)
        } ?: return false
        followUp.complete(TurnOutcome.TakenBack)
        return true
    }

    /** Takes no more reports or messages, ends the queued messages as taken back, and returns the reports left. */
    fun close(): List<EndedJob> {
        val (messages, left) = synchronized(lock) {
            closed = true
            val messages = sent.toList().also { sent.clear() }
            messages to reports.map { (it.plan.input as TurnSource.FromJob).job }.also { reports.clear() }
        }
        messages.forEach { it.complete(TurnOutcome.TakenBack) }
        return left
    }

    override fun take(principal: UserAddress, chat: ChatAddress, since: Instant): List<FollowUp> = emptyList()

    override fun takeSent(): List<FollowUp> = synchronized(lock) { sent.toList().also { sent.clear() } }

    override fun restore(followUps: List<FollowUp>) {
        synchronized(lock) {
            followUps.asReversed().forEach {
                if (it.plan.input is TurnSource.FromJob) reports.addFirst(it) else sent.addFirst(it)
            }
        }
    }

    override fun queued(principal: UserAddress, chat: ChatAddress, since: Instant): Boolean = false

    override suspend fun await(principal: UserAddress, chat: ChatAddress, since: Instant) = awaitCancellation()

    override fun takeJobs(chat: ChatAddress, conversation: ConversationId): List<FollowUp> {
        if (conversation != this.conversation) return emptyList()
        return synchronized(lock) { reports.toList().also { reports.clear() } }
    }

    override suspend fun awaitJobs(conversation: ConversationId): Boolean {
        if (conversation != this.conversation) return false
        while (true) {
            if (synchronized(lock) { reports.isNotEmpty() || sent.isNotEmpty() }) return true
            if (!jobs.running(conversation)) return synchronized(lock) { reports.isNotEmpty() || sent.isNotEmpty() }
            changed.receive()
        }
    }
}

/** A permit of the pool of background runs, which its run gives up while it waits for runs it started. */
internal class PoolPermit(private val pool: Semaphore) : TurnPermit {
    private var held = false

    suspend fun acquire() {
        pool.acquire()
        held = true
    }

    fun release() {
        if (!held) return
        held = false
        pool.release()
    }

    override suspend fun <T> released(block: suspend () -> T): T {
        release()
        val result = block()
        acquire()
        return result
    }
}

/** The permit of a sub-agent run, which runs on the permit of its root turn. */
internal object RootPermit : TurnPermit {
    override suspend fun <T> released(block: suspend () -> T): T = block()
}
