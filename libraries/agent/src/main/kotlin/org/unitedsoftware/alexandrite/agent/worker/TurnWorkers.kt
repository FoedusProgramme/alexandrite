package org.unitedsoftware.alexandrite.agent.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.cancel.TurnShutdown
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.turn.FollowUp
import org.unitedsoftware.alexandrite.agent.turn.FollowUps
import org.unitedsoftware.alexandrite.agent.turn.TurnPermit
import org.unitedsoftware.alexandrite.agent.turn.TurnPlan
import org.unitedsoftware.alexandrite.agent.turn.TurnRunner
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.sdk.turn.TurnStatus
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/** A job of a worker's queue. */
internal sealed interface WorkerJob {
    class Turn(val plan: TurnPlan, val ticket: AgentTicket, val capacity: Capacity) : WorkerJob

    /** A change to its key's state, such as a rotation, which runs in its place in the queue. */
    class Control(val effect: suspend () -> Unit) : WorkerJob {
        /** Completes exceptionally when [effect] failed or the agent shut down before it ran. */
        val done: CompletableDeferred<Unit> = CompletableDeferred()
    }
}

/** The key whose worker runs the turn of the coroutine. */
internal class RunningTurn(val workerKey: AgentChatKey) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunningTurn>
}

/** The key whose worker runs the change of the coroutine. */
internal class RunningControl(val workerKey: AgentChatKey) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunningControl>
}

/** One FIFO worker per agent and chat key, created on demand and removed once idle, whose turns share permits. */
@Singleton
internal class TurnWorkers(
    settings: AgentSettings,
    private val lock: SubmitLock,
    private val store: ConversationStore,
    private val runner: TurnRunner,
    private val clock: Clock,
) {
    private val perKey = settings.queue.perKey
    private val permits = Semaphore(settings.maxConcurrentTurns)
    private val workers = HashMap<AgentChatKey, Worker>()

    @Volatile
    private var open = false

    val accepting: Boolean get() = open

    val keys: Set<AgentChatKey> get() = lock.locked { workers.keys.toSet() }

    fun open() {
        lock.locked { open = true }
    }

    fun close() {
        lock.locked { open = false }
    }

    /** The counted turns waiting at [key]. */
    fun waiting(key: AgentChatKey): Int = lock.locked {
        workers[key]?.queue.orEmpty().count { it is WorkerJob.Turn && it.capacity == Capacity.COUNTED }
    }

    /** Queues [turn] at [key] unless the agent is closed or the key's queue, with [alsoWaiting] turns, is full. */
    fun submit(key: AgentChatKey, turn: WorkerJob.Turn, alsoWaiting: Int = 0): Admission = lock.locked {
        when {
            !open -> Admission.Refused(RefusalReason.SHUTTING_DOWN)

            turn.capacity == Capacity.COUNTED && waiting(key) + alsoWaiting >= perKey ->
                Admission.Refused(RefusalReason.QUEUE_FULL, perKey)

            else -> {
                enqueue(key, turn)
                Admission.Accepted(turn.ticket)
            }
        }
    }

    /** Queues [job] at [key] behind the turns there, or throws [IllegalStateException] once the agent is closed. */
    fun control(key: AgentChatKey, job: WorkerJob.Control) {
        lock.locked {
            check(open) { "The agent is not running." }
            enqueue(key, job)
        }
    }

    /** Queues [job] at [key] whatever the queue holds. */
    fun enqueue(key: AgentChatKey, job: WorkerJob) {
        lock.locked {
            val worker = workers.getOrPut(key) {
                val created = Worker(key)
                val loop = lock.launch { work(created) }
                loop.invokeOnCompletion { error -> error?.let { stopped(created, it) } }
                created.loop = loop
                created
            }
            worker.queue.addLast(job)
            if (job is WorkerJob.Turn) worker.arrivals.update { it + 1 }
        }
    }

    /**
     * Takes the messages that [principal] sent in [chat] and queued at [key] since [since] off its queue, in arrival
     * order, up to the first change of the key's state.
     */
    fun takeFollowUps(
        key: AgentChatKey,
        principal: UserAddress,
        chat: ChatAddress,
        since: Instant,
    ): List<WorkerJob.Turn> = lock.locked {
        val queue = workers[key]?.queue ?: return@locked emptyList()
        val taken = queue.followUps(principal, chat, since).toList()
        queue.removeAll { job -> taken.any { it === job } }
        taken
    }

    /**
     * Takes the results of the jobs that turns from [chat] started in [conversation] and that wait at [key] off its
     * queue, in queue order, up to the first change of the key's state.
     */
    fun takeJobResults(key: AgentChatKey, chat: ChatAddress, conversation: ConversationId): List<WorkerJob.Turn> =
        lock.locked {
            val queue = workers[key]?.queue ?: return@locked emptyList()
            val taken = queue.asSequence()
                .takeWhile { it is WorkerJob.Turn }
                .filterIsInstance<WorkerJob.Turn>()
                .filter { it.plan.origin == chat && it.plan.jobConversation == conversation }
                .toList()
            queue.removeAll { job -> taken.any { it === job } }
            taken
        }

    /** Whether [takeFollowUps] would return a message now. */
    fun hasFollowUp(key: AgentChatKey, principal: UserAddress, chat: ChatAddress, since: Instant): Boolean =
        lock.locked { workers[key]?.queue?.followUps(principal, chat, since)?.any() == true }

    /** Returns once [takeFollowUps] would return a message. */
    suspend fun awaitFollowUp(key: AgentChatKey, principal: UserAddress, chat: ChatAddress, since: Instant) {
        val arrivals = lock.locked { workers[key]?.arrivals } ?: awaitCancellation()
        arrivals.first { hasFollowUp(key, principal, chat, since) }
    }

    /** Puts [jobs], which the turn running at [key] took and did not absorb, back at the front of its queue. */
    fun restoreFollowUps(key: AgentChatKey, jobs: List<WorkerJob.Turn>) {
        lock.locked {
            val queue = workers[key]?.queue?.takeIf { open }
            if (queue == null) {
                jobs.forEach { it.ticket.complete(TurnOutcome.ShutDown(replayable = true)) }
            } else {
                jobs.asReversed().forEach(queue::addFirst)
                workers[key]?.arrivals?.update { it + 1 }
            }
        }
    }

    /**
     * The running and queued turns that came from [chat], or from every chat when it is null, each in the conversation
     * its runner read, else in the one its key holds now.
     */
    suspend fun statuses(chat: ChatAddress?): List<TurnStatus> {
        val listed = lock.locked { workers.values.flatMap { it.listed(chat) } }
        val read = HashMap<Pair<AgentChatKey, Boolean>, ConversationId?>()
        return listed.mapNotNull { turn ->
            val plan = turn.plan
            val heartbeat = plan.kind == TurnKind.HEARTBEAT
            val conversation = turn.conversation
                ?: read.getOrPut(plan.key to heartbeat) { conversation(plan.key, heartbeat) }
                ?: return@mapNotNull null
            status(plan, conversation, turn.startedAt, turn.finishing)
        }.sortedBy { it.queuedAt }
    }

    /** Cancels the running turns of [agent] that came from [chat], and returns whether there was one. */
    fun cancelRunning(chat: ChatAddress, agent: AgentId, cause: CancellationException): Boolean = lock.locked {
        val running = workers.values.filter { it.key.agent == agent }.mapNotNull { it.current }
            .filter { it.startedAt != null && !it.ended && it.turn?.plan?.origin == chat }
        running.forEach { it.cancel(cause) }
        running.isNotEmpty()
    }

    /** Drops [turn] while it is queued or cancels it once taken, and returns whether a worker held it. */
    fun cancel(turn: TurnId, cause: CancellationException): Boolean = lock.locked {
        for (worker in workers.values) {
            val queued = worker.queue.filterIsInstance<WorkerJob.Turn>().firstOrNull { it.plan.id == turn }
            if (queued != null) {
                worker.queue.remove(queued)
                queued.ticket.complete(notStarted(cause))
                return@locked true
            }
            val current = worker.current?.takeIf { it.turn?.plan?.id == turn }
            if (current != null) {
                if (current.ended) return@locked false
                current.cancel(cause)
                return@locked true
            }
        }
        false
    }

    /**
     * Ends the turns that have not started, gives the running ones [grace] to finish, then cancels them and waits
     * [cancelJoin] for them.
     */
    suspend fun drain(grace: Duration, cancelJoin: Duration) {
        val loops = lock.locked {
            open = false
            for (worker in workers.values) {
                val queued = worker.queue.filterIsInstance<WorkerJob.Turn>()
                worker.queue.removeAll(queued)
                queued.forEach { it.ticket.complete(TurnOutcome.ShutDown(replayable = true)) }
                worker.current?.takeIf { it.turn != null && it.startedAt == null }?.cancel(TurnShutdown())
            }
            workers.values.mapNotNull { it.loop }
        }
        withTimeoutOrNull(grace) { loops.joinAll() }
        val running = lock.locked {
            workers.values.mapNotNull { it.current }.filter { it.turn != null }.onEach { it.cancel(TurnShutdown()) }
        }
        withTimeoutOrNull(cancelJoin) { running.mapNotNull { it.child }.joinAll() }
        lock.locked {
            for (current in running) {
                val turn = current.turn ?: continue
                if (turn.ticket.complete(TurnOutcome.ShutDown(replayable = false))) {
                    logger.warn(
                        "Turn {} of {} did not end within {} of its cancellation: it is abandoned",
                        turn.plan.id,
                        turn.plan.key,
                        cancelJoin,
                    )
                }
            }
            for (worker in workers.values) {
                worker.queue.filterIsInstance<WorkerJob.Control>().forEach {
                    it.done.completeExceptionally(TurnShutdown())
                }
                worker.queue.clear()
            }
        }
    }

    private suspend fun work(worker: Worker) {
        while (true) {
            val current = lock.locked { take(worker) } ?: return
            when (val job = current.job) {
                is WorkerJob.Control -> control(worker, job)
                is WorkerJob.Turn -> current.child?.join()
            }
            lock.locked { worker.current = null }
        }
    }

    /** The next job of [worker], null when it has none and is removed. */
    private fun take(worker: Worker): Current? {
        var job = worker.queue.removeFirstOrNull()
        while (job is WorkerJob.Turn && !open) {
            job.ticket.complete(TurnOutcome.ShutDown(replayable = true))
            job = worker.queue.removeFirstOrNull()
        }
        if (job == null) {
            workers.remove(worker.key)
            return null
        }
        val current = Current(job)
        worker.current = current
        if (job is WorkerJob.Turn) current.child = launch(worker, current, job)
        return current
    }

    private fun launch(worker: Worker, current: Current, turn: WorkerJob.Turn): Job {
        val child = lock.launch(RunningTurn(turn.plan.key)) { execute(worker, current, turn) }
        child.invokeOnCompletion { error -> error?.let { turn.ticket.complete(interrupted(current, it)) } }
        return child
    }

    private suspend fun execute(worker: Worker, current: Current, turn: WorkerJob.Turn) {
        val permit = Permit()
        val outcome = try {
            permit.take()
            try {
                lock.locked {
                    current.cause?.let { throw it }
                    current.startedAt = clock.instant()
                }
                val decided = { lock.locked { current.decided = true } }
                val resolved = { conversation: ConversationId ->
                    lock.locked {
                        current.conversation = conversation
                        when (turn.plan.kind) {
                            TurnKind.HEARTBEAT -> worker.heartbeatBase = conversation
                            TurnKind.JOB -> Unit
                            else -> worker.conversation = conversation
                        }
                    }
                }
                runner.run(turn.plan, QueuedFollowUps(turn.plan.key), permit, resolved, decided).also { decided() }
            } finally {
                permit.give()
            }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            if (e is CancellationException && !currentCoroutineContext().isActive) {
                turn.ticket.complete(interrupted(current, e))
                throw e
            }
            failed(turn, e)
        }
        turn.ticket.complete(outcome)
    }

    private fun failed(turn: WorkerJob.Turn, error: Throwable): TurnOutcome {
        logger.error("Turn {} of {} failed", turn.plan.id, turn.plan.key, error)
        return TurnOutcome.Failed(error.toString())
    }

    /** How a turn whose coroutine ended with [error] ends. */
    private fun interrupted(current: Current, error: Throwable): TurnOutcome = lock.locked {
        when {
            error !is CancellationException -> TurnOutcome.Failed(error.toString())
            current.cause is TurnCancelled -> TurnOutcome.Cancelled
            else -> TurnOutcome.ShutDown(replayable = current.startedAt == null)
        }
    }

    private suspend fun control(worker: Worker, job: WorkerJob.Control) {
        try {
            withContext(RunningControl(worker.key)) { job.effect() }
            job.done.complete(Unit)
        } catch (e: Throwable) {
            job.done.completeExceptionally(e)
            if (e is VirtualMachineError) throw e
            currentCoroutineContext().ensureActive()
            if (e !is Exception) logger.error("A change of {} failed", worker.key, e)
        } finally {
            lock.locked { worker.conversation = null }
        }
    }

    /** Removes [worker], whose loop ended with [error], and ends the jobs still queued there. */
    private fun stopped(worker: Worker, error: Throwable) {
        lock.locked {
            if (workers[worker.key] === worker) workers.remove(worker.key)
            val outcome = if (error is CancellationException) {
                TurnOutcome.ShutDown(replayable = true)
            } else {
                logger.error("The worker of {} stopped", worker.key, error)
                TurnOutcome.Failed("The worker of its chat stopped: $error")
            }
            for (job in worker.queue) {
                when (job) {
                    is WorkerJob.Turn -> job.ticket.complete(outcome)
                    is WorkerJob.Control -> job.done.completeExceptionally(error)
                }
            }
            worker.queue.clear()
        }
    }

    /** The follow-ups queued at [key] for the turn running there. */
    private inner class QueuedFollowUps(private val key: AgentChatKey) : FollowUps {
        private val taken = HashMap<TurnId, WorkerJob.Turn>()

        override fun take(principal: UserAddress, chat: ChatAddress, since: Instant): List<FollowUp> =
            takeFollowUps(key, principal, chat, since).map { job ->
                taken[job.plan.id] = job
                FollowUp(job.plan) { job.ticket.complete(it) }
            }

        override fun restore(followUps: List<FollowUp>) {
            restoreFollowUps(key, followUps.mapNotNull { taken.remove(it.plan.id) })
        }

        override fun queued(principal: UserAddress, chat: ChatAddress, since: Instant): Boolean =
            hasFollowUp(key, principal, chat, since)

        override suspend fun await(principal: UserAddress, chat: ChatAddress, since: Instant) {
            awaitFollowUp(key, principal, chat, since)
        }

        override fun takeJobs(chat: ChatAddress, conversation: ConversationId): List<FollowUp> =
            takeJobResults(key, chat, conversation).map { job ->
                taken[job.plan.id] = job
                FollowUp(job.plan) { job.ticket.complete(it) }
            }
    }

    /** The permit of one turn, which it holds while it runs and gives up while it waits for a person. */
    private inner class Permit : TurnPermit {
        private var held = false

        /** How many of the turn's calls wait without the permit. */
        private var away = 0

        suspend fun take() {
            permits.acquire()
            synchronized(this) { held = true }
        }

        fun give() {
            synchronized(this) {
                if (!held) return
                held = false
            }
            permits.release()
        }

        override suspend fun <T> released(block: suspend () -> T): T {
            if (synchronized(this) { away++ == 0 }) give()
            try {
                return block()
            } finally {
                if (synchronized(this) { --away == 0 } && currentCoroutineContext().isActive) take()
            }
        }
    }

    /** The current conversation of [key], or its heartbeat base when [heartbeat], null when it cannot be read. */
    private suspend fun conversation(key: AgentChatKey, heartbeat: Boolean): ConversationId? = try {
        (if (heartbeat) store.heartbeatBase(key) else store.current(key)).id
    } catch (e: Throwable) {
        if (e is VirtualMachineError) throw e
        currentCoroutineContext().ensureActive()
        logger.warn("Cannot read the conversation of {} that its queued turns show: {}", key, e.toString())
        null
    }
}

private class Worker(val key: AgentChatKey) {
    val queue = ArrayDeque<WorkerJob>()

    /** The number of turns queued so far. */
    val arrivals = MutableStateFlow(0L)

    var current: Current? = null
    var loop: Job? = null

    /** The current conversation that the last runner read, null before then and after each change of the key. */
    var conversation: ConversationId? = null

    /** The conversation of the key's heartbeat turns, null until a runner read it. */
    var heartbeatBase: ConversationId? = null

    /** The running and queued turns that came from [chat], or from every chat when it is null. */
    fun listed(chat: ChatAddress?): List<Listed> {
        val running = current?.let { current ->
            current.turn?.let { Listed(it.plan, current.startedAt, current.conversation, current.ended) }
        }
        val queued = queue.filterIsInstance<WorkerJob.Turn>().map { turn ->
            val known = when (turn.plan.kind) {
                TurnKind.HEARTBEAT -> heartbeatBase
                TurnKind.JOB -> turn.plan.jobConversation
                else -> conversation
            }
            Listed(turn.plan, null, known, finishing = false)
        }
        return (listOfNotNull(running) + queued).filter { chat == null || it.plan.origin == chat }
    }
}

/** A turn that a worker runs or queues, with the conversation it runs in when the worker knows it. */
private class Listed(
    val plan: TurnPlan,
    val startedAt: Instant?,
    val conversation: ConversationId?,
    val finishing: Boolean,
)

/** The job a worker took from its queue. */
private class Current(val job: WorkerJob) {
    var child: Job? = null
    var startedAt: Instant? = null
    var cause: CancellationException? = null

    /** Whether the runner decided the turn's outcome, after which a cancel no longer stops it. */
    var decided = false

    /** The conversation that the runner of the turn read, null before then. */
    var conversation: ConversationId? = null

    val turn: WorkerJob.Turn? get() = job as? WorkerJob.Turn

    val ended: Boolean get() = decided || turn?.ticket?.ended == true

    fun cancel(cause: CancellationException) {
        if (this.cause == null) this.cause = cause
        child?.cancel(cause)
    }
}

/** The follow-ups of [principal] in [chat] queued since [since], up to the first change of the key's state. */
private fun ArrayDeque<WorkerJob>.followUps(principal: UserAddress, chat: ChatAddress, since: Instant) = asSequence()
    // A turn that makes a call for its operator runs as its own, and the messages after it wait for it.
    .takeWhile { it is WorkerJob.Turn && it.plan.operatorCall == null }
    .filterIsInstance<WorkerJob.Turn>()
    .filter { it.plan.isFollowUp(principal, chat, since) }

/** The conversation of the job whose result this turn answers, null for any other turn. */
private val TurnPlan.jobConversation: ConversationId?
    get() = (input as? TurnSource.FromJob)?.job?.job?.resultConversation

/** Whether this turn answers a message that [principal] sent in [chat] and queued since [since]. */
private fun TurnPlan.isFollowUp(principal: UserAddress, chat: ChatAddress, since: Instant): Boolean =
    kind == TurnKind.MESSAGE &&
        input is TurnSource.FromMessage &&
        actor?.address == principal &&
        origin == chat &&
        !queuedAt.isBefore(since)

private fun status(plan: TurnPlan, conversation: ConversationId, startedAt: Instant?, finishing: Boolean): TurnStatus {
    val turn = TurnInfo.builder(plan.id, plan.origin, conversation, plan.kind)
        .actor(plan.actor)
        .agent(plan.key.agent)
        .replyTarget(plan.replyTarget)
        .key(plan.key)
        .build()
    val phase = when {
        startedAt == null -> TurnPhase.QUEUED
        finishing -> TurnPhase.FINISHING
        else -> TurnPhase.RUNNING
    }
    return TurnStatus.builder(turn, phase, plan.queuedAt).startedAt(startedAt).build()
}

private val logger = LoggerFactory.getLogger(TurnWorkers::class.java)
