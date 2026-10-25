package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.context.ContextRecords
import org.unitedsoftware.alexandrite.agent.delivery.visibleText
import org.unitedsoftware.alexandrite.agent.job.Detached
import org.unitedsoftware.alexandrite.agent.job.JobInboxes
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.agent.messaging.MessageBudget
import org.unitedsoftware.alexandrite.agent.permission.actorBelow
import org.unitedsoftware.alexandrite.agent.profile.Profiles
import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.DelegatedTask
import org.unitedsoftware.alexandrite.agent.tool.DelegatingTurn
import org.unitedsoftware.alexandrite.agent.tool.InTurnResult
import org.unitedsoftware.alexandrite.agent.tool.RunChain
import org.unitedsoftware.alexandrite.agent.tool.shaped
import org.unitedsoftware.alexandrite.agent.turn.HistoryCache
import org.unitedsoftware.alexandrite.agent.turn.TurnPermit
import org.unitedsoftware.alexandrite.agent.turn.TurnPlan
import org.unitedsoftware.alexandrite.agent.turn.TurnRunner
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.agent.usage.Budgets
import org.unitedsoftware.alexandrite.agent.worker.newRunId
import org.unitedsoftware.alexandrite.agent.worker.newTurnId
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ForkPoint
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.turn.AgentRunLifecycle
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import java.time.Clock
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/** Runs each sub-agent run that a call of `agent.delegate` starts, inside that call. */
@Singleton
internal class Delegator(
    private val profiles: Profiles,
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val history: HistoryCache,
    private val contexts: ContextRecords,
    private val runs: DelegatedRuns,
    private val runner: Lazy<TurnRunner>,
    private val clock: Clock,
    private val jobs: Jobs,
    private val channels: ChannelDirectory,
    private val hooks: Hooks,
    private val inboxes: JobInboxes,
    private val runRecords: RunRecords,
    private val budgets: Budgets,
    settings: AgentSettings,
) {
    private val permits = Semaphore(settings.maxBackgroundRuns)

    /**
     * What the model reads once a run of the profile [id] on [task] ended, forked from the history if [fork], or
     * going on from the earlier run [resume] of the same root conversation, whose profile [id] may then omit.
     */
    suspend fun delegate(
        id: String?,
        task: String,
        fork: Boolean,
        context: AgentToolContext,
        background: Boolean = false,
        resume: RunId? = null,
    ): InTurnResult {
        val parent = checkNotNull(context.delegating) { "Call ${context.call} delegates outside a turn." }
        val turn = parent.turn
        val depth = (turn.lineage?.depth ?: 0) + 1
        if (depth > MAX_DEPTH) {
            logger.info("Turn {} of {} may not delegate: it is a run at depth {}", turn.id, turn.key, depth - 1)
            return InTurnResult(DELEGATION_LIMIT_RESULT, ToolOutcome.NotRun(NotRunReason.DELEGATION_LIMIT))
        }
        if (background && budgets.exhausted(turn.agent)) {
            logger.info("Turn {} of {} starts no background run: its agent used up a budget", turn.id, turn.key)
            return InTurnResult(BUDGET_SPENT_RESULT, ToolOutcome.NotRun(NotRunReason.BUDGET))
        }
        val root = turn.lineage?.rootConversation ?: turn.conversation
        val earlier = if (resume == null) {
            null
        } else {
            val record = runRecords.get(resume)?.takeIf { it.root == root }
                ?: return invalid("No run $resume of this conversation to resume.")
            if (conversations.conversation(record.conversation)?.state != ConversationState.SEALED) {
                return invalid("Run $resume has not ended yet; wait for its report.")
            }
            if (id != null && id != record.profile) return invalid("Run $resume ran profile ${record.profile}.")
            record
        }
        val profileId = earlier?.profile ?: checkNotNull(id) { "A delegation names a profile or a run to resume." }
        val profile = profile(profileId) ?: return invalid(unknown(profileId))
        val call = context.call
        val now = clock.instant()
        val run = newRunId("r-")
        val lineage = TurnLineage(
            run = run,
            parentTurn = turn.id,
            parentConversation = turn.conversation,
            parentCall = call,
            rootTurn = turn.lineage?.rootTurn ?: turn.id,
            rootConversation = turn.lineage?.rootConversation ?: turn.conversation,
            depth = depth,
        )
        var resumed: String? = null
        val conversation = withContext(NonCancellable) {
            val from = earlier?.let { lastEntryOf(it.conversation) } ?: forkPoint(turn.conversation, call, fork)
            val created = conversations.createDelegated(turn.key, lineage, from)
            context.taint.known?.let { inherit(created.id, run, turn, it.reason) }
            resumed = earlier?.let { carryTaint(checkNotNull(resume), it.conversation, created.id, run) }
            runRecords.put(run, RunRecord(created.id, root, profile.id.value))
            created
        }
        val shown = DelegatedRun(call, run, profile.id, turn.lineage?.run, task, resume)
        val detachedParent = if (background) BackgroundParent(parent, channels, shown) else null
        val delegation = Delegation(
            run,
            shown,
            lineage,
            conversation,
            profile,
            task,
            detachedParent ?: parent,
            chain(parent, profile, task),
            background,
            resumed,
        ) { reason -> context.report("sub-agent run $run (${profile.id}) took in untrusted content: $reason") }
        val plan = TurnPlan(
            id = newTurnId(now),
            key = turn.key,
            origin = turn.chat,
            kind = TurnKind.DELEGATED,
            input = TurnSource.FromDelegation(delegation),
            actor = actorBelow(turn),
            replyTarget = ReplyTarget.CALLER,
            trigger = null,
            queuedAt = now,
        )
        logger.info(
            "Turn {} of {} delegates run {} to profile '{}' ({}) in conversation {}{}",
            turn.id,
            turn.key,
            run,
            profile.id,
            delegation.chain,
            conversation.id,
            when {
                resume != null -> ", resuming run $resume"
                fork -> ", forked from its history"
                else -> ""
            },
        )
        if (background) {
            val activityReady = CompletableDeferred<Unit>()
            val callPart = ToolCallPart(call, DELEGATE_TOOL, "{}")
            val messageBudget = currentCoroutineContext()[MessageBudget]
            val job = jobs.start(
                turn,
                callPart,
                profile.id.value,
                context,
                Duration.INFINITE,
                run,
                cleanup = {
                    detachedParent?.close(ToolCallStatus.NOT_RUN)
                    if (delegation.turn == null) seal(delegation)
                },
                delegated = conversation.id,
            ) {
                activityReady.await()
                withContext(messageBudget ?: EmptyCoroutineContext) {
                    val permit = PoolPermit(permits)
                    permit.acquire()
                    jobs.stopping(run)?.let { cause ->
                        permit.release()
                        throw cause
                    }
                    var status = ToolCallStatus.FAILED
                    try {
                        checkNotNull(detachedParent).open()
                        val result = complete(delegation, plan, context, permit)
                        if (!result.outcome.isError) status = ToolCallStatus.COMPLETED
                        ToolResult(result.content, isError = result.outcome.isError)
                    } finally {
                        permit.release()
                        withContext(NonCancellable) { detachedParent?.close(status) }
                    }
                }
            }
            if (job is Detached.Kept) {
                withContext(NonCancellable) { seal(delegation) }
                return InTurnResult("No sub-agent was started: ${job.why}.", ToolOutcome.NotRun(job.reason))
            }
            try {
                checkNotNull(detachedParent).prepare()
            } finally {
                activityReady.complete(Unit)
            }
            return InTurnResult(
                "[alexandrite:background] Run $run of profile ${profile.id} started; its report will arrive later.",
                ToolOutcome.Detached(run),
            )
        }
        return complete(delegation, plan, context)
    }

    private suspend fun complete(
        delegation: Delegation,
        plan: TurnPlan,
        context: AgentToolContext,
        permit: TurnPermit = RootPermit,
    ): InTurnResult {
        val outcome = run(delegation, plan, permit)
        val result = result(delegation, plan.id, outcome)
        return result
    }

    /**
     * How the turn of [plan] ended, null when it was cancelled before it started; it takes the reports of the
     * background runs it starts, and those still running when it ends are stopped.
     */
    private suspend fun run(delegation: Delegation, plan: TurnPlan, permit: TurnPermit): TurnOutcome? {
        var outcome: TurnOutcome? = null
        val conversation = delegation.conversation.id
        val inbox = RunInbox(conversation, jobs, clock)
        inboxes.open(conversation, inbox)
        try {
            coroutineScope {
                val job = launch(start = CoroutineStart.LAZY) {
                    outcome = runner.value.run(plan, inbox, permit, resolved = {}, decided = {})
                }
                runs.started(LiveRun(delegation, plan.id, job, plan.queuedAt, inbox))
                hooks.fire(TurnPoints.AGENT_SPAWN, lifecycle(delegation))
                job.join()
            }
        } finally {
            withContext(NonCancellable) {
                jobs.stopUnder(delegation.run)
                inboxes.close(conversation)
                for (report in inbox.close()) {
                    logger.info("The report of job {} is dropped: run {} ended first", report.job.id, delegation.run)
                    jobs.delivered(report.job)
                }
                runs.ended(delegation.run)
                hooks.fire(TurnPoints.AGENT_RUN_ENDED, lifecycle(delegation))
                seal(delegation)
            }
        }
        return outcome
    }

    /** What the model reads of [delegation], whose turn [turn] ended with [outcome], ending with the run's id. */
    private suspend fun result(delegation: Delegation, turn: TurnId, outcome: TurnOutcome?): InTurnResult {
        val result = outcomeOf(delegation, turn, outcome)
        return InTurnResult(result.content + TextPart(runFraming(delegation.run)), result.outcome)
    }

    private suspend fun outcomeOf(delegation: Delegation, turn: TurnId, outcome: TurnOutcome?): InTurnResult {
        val run = "Sub-agent run ${delegation.run} of ${delegation.profile.id}"
        val reply = (outcome as? TurnOutcome.Completed)?.reply?.let(::visibleText).orEmpty()
        val ended = outcome?.let { it::class.simpleName } ?: "Cancelled before it started"
        logger.info("Run {} of profile '{}' ended: {}", delegation.run, delegation.profile.id, ended)
        return when (outcome) {
            is TurnOutcome.Completed if reply.isNotEmpty() ->
                InTurnResult(shaped(listOf(TextPart(reply))), ToolOutcome.Succeeded)

            is TurnOutcome.Completed, TurnOutcome.TakenBack, is TurnOutcome.Failed -> {
                val how = if (outcome is TurnOutcome.Failed) "failed" else "ended without a reply"
                val notice = notice(turn)?.let { ": ${oneLine(it)}" }.orEmpty()
                val kept = "Its conversation ${delegation.conversation.id} keeps what it did."
                InTurnResult(shaped(listOf(TextPart("$run $how$notice. $kept"))), ToolOutcome.Failed)
            }

            else -> stopped(delegation, turn, run) ?: InTurnResult(
                "Cancelled (outcome unknown): $run was stopped before it ended.",
                ToolOutcome.Cancelled,
            )
        }
    }

    /** What the model reads of [delegation], [run], whose turn [turn] a person stopped, null when nobody did. */
    private suspend fun stopped(delegation: Delegation, turn: TurnId, run: String): InTurnResult? {
        val by = delegation.stoppedBy ?: return null
        val said = lastWords(turn)?.let { " What it wrote last: $it" }.orEmpty()
        val kept = "Its conversation ${delegation.conversation.id} keeps what it did."
        val told = Escaper.HEADERS.text("${oneLine(by.displayName)} stopped $run before it ended.$said $kept")
        return InTurnResult("[alexandrite:stopped] $told", ToolOutcome.Cancelled)
    }

    /** The visible text of the last response of the turn [turn] on one line, null when it has none. */
    private suspend fun lastWords(turn: TurnId): String? = try {
        transcripts.turnEntries(turn).filterIsInstance<AssistantEntry>()
            .map(::visibleText)
            .lastOrNull(String::isNotBlank)
            ?.let { oneLine(it).take(MAX_LAST_WORDS) }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Cannot read the responses of the sub-agent turn {}: {}", turn, e.toString())
        null
    }

    private fun invalid(text: String) = InTurnResult(text, ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS))

    /** The profile [id], null when there is none. */
    private suspend fun profile(id: String): AgentProfile? {
        val agent = try {
            AgentId(id)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return profiles.profile(agent)?.profile
    }

    /** What the model reads of a call that names no profile [id]. */
    private suspend fun unknown(id: String): String {
        val known = profiles.all().map { it.profile.id }
        val choose = if (known.isEmpty()) "No profile exists yet." else "Choose one of ${known.joinToString()}."
        return "No profile '${oneLine(id)}'. $choose"
    }

    /** The last notice of the turn [turn], null when it stored none or it cannot be read. */
    private suspend fun notice(turn: TurnId): String? = try {
        transcripts.turnEntries(turn).filterIsInstance<NoticeEntry>().lastOrNull()?.text
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Cannot read the notices of the sub-agent turn {}: {}", turn, e.toString())
        null
    }

    /** The entry of [conversation] before the response that made [call], null unless [fork]. */
    private suspend fun forkPoint(conversation: ConversationId, call: ToolCallId, fork: Boolean): ForkPoint? {
        if (!fork) return null
        val entries = history.entries(conversation)
        val asking = entries.indexOfFirst { entry ->
            entry is AssistantEntry && entry.parts.any { it is ToolCallPart && it.id == call }
        }
        val through = (if (asking < 0) entries else entries.subList(0, asking)).lastOrNull()?.record?.id
        return through?.let { ForkPoint(conversation, it) }
    }

    /** The end of [conversation], from which a run that resumes its run goes on; null when it holds nothing. */
    private suspend fun lastEntryOf(conversation: ConversationId): ForkPoint? =
        transcripts.entries(conversation).lastOrNull()?.record?.id?.let { ForkPoint(conversation, it) }

    /**
     * Marks [conversation] of [run] tainted when [earlier], the conversation of the run [resumed] it goes on from, is,
     * and returns why; null when it is clean.
     */
    private suspend fun carryTaint(
        resumed: RunId,
        earlier: ConversationId,
        conversation: ConversationId,
        run: RunId,
    ): String? {
        val why = conversations.taint(earlier)?.reason ?: return null
        val reason = "run $run resumes run $resumed, whose conversation took in untrusted content ($why)"
        conversations.markTainted(conversation, reason, clock.instant())
        return reason
    }

    /** Marks [conversation] of [run] tainted like [turn], which delegated it, tainted for [why]. */
    private suspend fun inherit(conversation: ConversationId, run: RunId, turn: TurnInfo, why: String) {
        val reason = "run $run was delegated by a turn of tainted conversation ${turn.conversation} ($why)"
        conversations.markTainted(conversation, reason, clock.instant())
    }

    /** Seals the conversation of [delegation], whose run ended. */
    private suspend fun seal(delegation: Delegation) {
        val conversation = delegation.conversation.id
        try {
            conversations.sealDelegated(conversation)
            contexts.sealed(conversation)
        } catch (e: Exception) {
            logger.warn(
                "The conversation {} of run {} could not be sealed: {}",
                conversation,
                delegation.run,
                e.toString(),
            )
        }
    }
}

/** The chain of a run of [profile] on [task] below [parent]. */
private fun chain(parent: DelegatingTurn, profile: AgentProfile, task: String): RunChain {
    val above = parent.chain
    val agents = (above?.agents ?: listOf(parent.turn.agent)) + profile.id
    return RunChain(
        agents,
        above?.modes.orEmpty() + listOfNotNull(profile.mode),
        above?.tasks.orEmpty() + DelegatedTask(agents[agents.size - 2], profile.id, task),
    )
}

private val logger = LoggerFactory.getLogger(Delegator::class.java)

private fun lifecycle(run: Delegation): AgentRunLifecycle =
    AgentRunLifecycle(run.parent.turn, run.run, run.profile.id, run.background)

/** The characters of a stopped run's last words that its result keeps. */
private const val MAX_LAST_WORDS = 2000
