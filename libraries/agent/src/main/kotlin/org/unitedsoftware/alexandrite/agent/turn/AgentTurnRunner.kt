package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.cancel.turnCause
import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.context.Compaction
import org.unitedsoftware.alexandrite.agent.context.Compactor
import org.unitedsoftware.alexandrite.agent.context.TokenEstimator
import org.unitedsoftware.alexandrite.agent.context.contextView
import org.unitedsoftware.alexandrite.agent.control.PendingNote
import org.unitedsoftware.alexandrite.agent.control.PendingNotes
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.control.effectiveZone
import org.unitedsoftware.alexandrite.agent.delegation.Delegation
import org.unitedsoftware.alexandrite.agent.delegation.delegationEntry
import org.unitedsoftware.alexandrite.agent.delegation.delegator
import org.unitedsoftware.alexandrite.agent.delivery.ChatReplies
import org.unitedsoftware.alexandrite.agent.delivery.Notices
import org.unitedsoftware.alexandrite.agent.delivery.ReplyStream
import org.unitedsoftware.alexandrite.agent.delivery.Routes
import org.unitedsoftware.alexandrite.agent.delivery.delivered
import org.unitedsoftware.alexandrite.agent.delivery.notice
import org.unitedsoftware.alexandrite.agent.delivery.replyMessage
import org.unitedsoftware.alexandrite.agent.delivery.visibleText
import org.unitedsoftware.alexandrite.agent.directory.Inbox
import org.unitedsoftware.alexandrite.agent.hook.Hooked
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.hook.or
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.job.EndedJob
import org.unitedsoftware.alexandrite.agent.job.InterruptedJob
import org.unitedsoftware.alexandrite.agent.job.InterruptedJobs
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.agent.job.described
import org.unitedsoftware.alexandrite.agent.job.jobEntry
import org.unitedsoftware.alexandrite.agent.media.MediaPlan
import org.unitedsoftware.alexandrite.agent.media.MediaReader
import org.unitedsoftware.alexandrite.agent.media.MediaRouting
import org.unitedsoftware.alexandrite.agent.messaging.MessageBudget
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.model.RetryPolicy
import org.unitedsoftware.alexandrite.agent.model.SelectedModel
import org.unitedsoftware.alexandrite.agent.model.cacheKey
import org.unitedsoftware.alexandrite.agent.model.collectResponse
import org.unitedsoftware.alexandrite.agent.model.crossedTo
import org.unitedsoftware.alexandrite.agent.model.movesOn
import org.unitedsoftware.alexandrite.agent.model.nextModel
import org.unitedsoftware.alexandrite.agent.model.plus
import org.unitedsoftware.alexandrite.agent.model.requestOptions
import org.unitedsoftware.alexandrite.agent.model.roomier
import org.unitedsoftware.alexandrite.agent.model.select
import org.unitedsoftware.alexandrite.agent.permission.Mandates
import org.unitedsoftware.alexandrite.agent.permission.Refusals
import org.unitedsoftware.alexandrite.agent.project.InstructionBudget
import org.unitedsoftware.alexandrite.agent.project.LoadedFile
import org.unitedsoftware.alexandrite.agent.project.ProjectInstructions
import org.unitedsoftware.alexandrite.agent.project.UntrustedFolder
import org.unitedsoftware.alexandrite.agent.project.entries
import org.unitedsoftware.alexandrite.agent.prompt.PromptAssembler
import org.unitedsoftware.alexandrite.agent.prompt.PromptTurn
import org.unitedsoftware.alexandrite.agent.prompt.TurnContextPlan
import org.unitedsoftware.alexandrite.agent.prompt.initiatedEntry
import org.unitedsoftware.alexandrite.agent.prompt.messageEntry
import org.unitedsoftware.alexandrite.agent.prompt.turnContextPlan
import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.agent.tool.CANCELLED_RESULT
import org.unitedsoftware.alexandrite.agent.tool.DelegatingTurn
import org.unitedsoftware.alexandrite.agent.tool.RenderedTool
import org.unitedsoftware.alexandrite.agent.tool.RunChain
import org.unitedsoftware.alexandrite.agent.tool.ToolExecutor
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.agent.tool.Toolset
import org.unitedsoftware.alexandrite.agent.tool.relativized
import org.unitedsoftware.alexandrite.agent.tool.textResult
import org.unitedsoftware.alexandrite.agent.tool.toolTitle
import org.unitedsoftware.alexandrite.agent.trust.ConversationTaints
import org.unitedsoftware.alexandrite.agent.trust.ReadTaint
import org.unitedsoftware.alexandrite.agent.trust.TaintOffers
import org.unitedsoftware.alexandrite.agent.trust.TrustSources
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.agent.usage.Budgets
import org.unitedsoftware.alexandrite.agent.usage.UsageMeter
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyActivity
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.FinishReason
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** Runs each turn that a worker takes through the turn pipeline. */
@Singleton
@Binds(TurnRunner::class)
internal class AgentTurnRunner(
    private val agentSettings: AgentSettings,
    private val directory: AgentDirectory,
    private val settings: SettingsStates,
    private val topology: ChatTopology,
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val mediaStore: MediaStore,
    private val mediaReader: MediaReader,
    private val routing: MediaRouting,
    private val inbox: Inbox,
    private val channels: ChannelDirectory,
    private val endpoints: Endpoints,
    private val retries: RetryPolicy,
    private val history: HistoryCache,
    private val compactor: Compactor,
    private val prompt: PromptAssembler,
    private val tools: ToolRegistry,
    private val workspaces: Workspaces,
    notes: List<PendingNotes>,
    private val executor: ToolExecutor,
    private val jobs: Jobs,
    private val interruptedJobs: InterruptedJobs,
    private val chatReplies: ChatReplies,
    private val mandates: Mandates,
    private val projects: ProjectInstructions,
    private val taints: ConversationTaints,
    private val reads: ReadTaint,
    private val trust: TrustSources,
    private val offers: TaintOffers,
    private val hooks: TurnHooks,
    private val notices: Notices,
    private val routes: Routes,
    private val clock: Clock,
    private val budgets: Budgets,
) : TurnRunner {
    private val notes = notes.sortedBy { it.order }

    override suspend fun run(
        plan: TurnPlan,
        followUps: FollowUps,
        permit: TurnPermit,
        resolved: (ConversationId) -> Unit,
        decided: () -> Unit,
    ): TurnOutcome {
        val run = Run(plan, followUps, permit, resolved, decided)
        val budget = (plan.input as? TurnSource.FromAgentMessage)?.message?.budget
            ?: (plan.input as? TurnSource.FromJob)?.job?.job?.carried?.get(MessageBudget)
            ?: currentCoroutineContext()[MessageBudget] ?: MessageBudget(plan.id)
        return try {
            withContext(budget + run.meter) { run.execute() }
        } catch (e: Throwable) {
            val cause = (e as? CancellationException)?.let(::turnCause)
            if (cause != null) {
                if (budget.rootTurn == plan.id) budget.cancel()
                return withContext(NonCancellable) { run.cutOff(cause) }
            }
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            val failed = withContext(NonCancellable) { run.fail(e) }
            if (e is VirtualMachineError) throw e
            failed
        } finally {
            (plan.input as? TurnSource.FromJob)?.job?.consumed?.invoke()
        }
    }

    /** One turn on its way through the pipeline, with what it stored and showed so far. */
    private inner class Run(
        private val plan: TurnPlan,
        private val followUps: FollowUps,
        private val permit: TurnPermit,
        private val resolved: (ConversationId) -> Unit,
        private val decided: () -> Unit,
    ) {
        private val startedAt = clock.instant()
        val meter = UsageMeter(budgets)
        private lateinit var agent: Agent
        private lateinit var model: ModelRef
        private var reasoning: ReasoningEffort? = null
        private var turn: TurnInfo? = null
        private var sink: ReplySink? = null
        private var capabilities: ChannelCapabilities? = null
        private var previews: ReplyStream? = null

        /** The reply of the sub-agent run the turn is, where its channel gives each run one, null otherwise. */
        private var runReply: ReplySink? = null

        /** Whether [runReply] ended. */
        private var runClosed = false
        private val media = TurnMedia(plan.id, mediaStore, agentSettings.maxMediaBytes, inbox, mediaReader)

        /** How the turn's model takes each kind of media. */
        private var mediaPlan = MediaPlan(emptyMap())
        private var recorded = false
        private lateinit var taint: TurnTaint

        /** Whether the turn's operator acts under a plugin's mandate, its own or that of the turn above it. */
        @Volatile
        private var mandated = false

        /** The turn as its calls see it once its mandate ended, null before. */
        @Volatile
        private var demoted: TurnInfo? = null
        private val tracked = Calls()
        private val delegation: Delegation? = (plan.input as? TurnSource.FromDelegation)?.delegation
        private var maxRounds: Int? = null
        private var maxTurnTokens: Long? = null

        /** The model the turn moved on from after it failed, null while the turn keeps its first. */
        private var movedFrom: ModelRef? = null

        /** Why the turn moved on from [movedFrom]. */
        private var movedWhy: String? = null

        /** The turn-context items of the turn, as the hooks injected them. */
        private var contextItems: List<TurnContextItem> = emptyList()

        /** The entries of the history that the turn's conversation forks. */
        private var inherited: List<TranscriptEntry> = emptyList()
        private var callable: Set<String> = emptySet()

        /** Whose messages the turn takes as follow-ups, null when it takes none. */
        private val principal: UserAddress? = plan.actor?.address?.takeIf { plan.kind == TurnKind.MESSAGE }

        /** Whether the sink got its final message. */
        private var replied = false
        private val stored = mutableListOf<TranscriptEntry>()
        private val appending = Mutex()
        private var usage: Usage? = null

        /** The input and output tokens of the turn's model calls so far, its sub-agent runs' included. */
        private val spent = AtomicLong()
        private var outcome: TurnOutcome? = null

        suspend fun execute(): TurnOutcome {
            val input = plan.input
            val message = input.message
            if (input is TurnSource.FromJob && input.job.cancelled()) return TurnOutcome.TakenBack
            if (input is TurnSource.FromJob && !stillActive(input.job)) return dropped(input.job)
            if (!runsPastBudgets(input) && budgets.exhausted(plan.key.agent)) return overBudget(input)
            val turn = dequeue()
            if (!openReply(turn)) return TurnOutcome.Failed("Channel instance ${turn.chat.instance} is not open.")
            withContext(NonCancellable) {
                conversations.startTurn(turn)
                recorded = true
            }
            val selected = when (val selection = selection(model)) {
                is Selection.Found -> selection.model

                is Selection.Failed ->
                    selection.error.takeIf(::movesOn)?.let { movedOn(it.kind.toString()) }
                        ?: return modelFailed(selection.error)

                Selection.Unknown -> movedOn(UNKNOWN_MODEL) ?: return unknownModel()
            }
            mediaPlan = routing.plan(agent.id, selected.info)
            val options = requestOptions(agent, selected.info, reasoning)
            val toolset = toolsOf(selected)
            val offered = toolset.offered
            val definitions = offered.map { (it as? RenderedTool)?.definitionFor(turn) ?: it.definition }
            val narrowed = hooks.turnStart(turn, message, definitions).or { return stopped(it) }.map { it.name }
            val callable = tools.callable(turn, narrowed + toolset.callableUnoffered)
            this.callable = callable
            val text = hooks.turnInput(turn, message, input.text).or { return stopped(it) }
            val context = turnContext(turn, text, selected, options)
            val opening = opening(turn, input, text, context.baked)
            val notes = if (delegation == null) pendingNotes(turn, definitions) else emptyList()
            val view = contextView(inherited + history.entries(turn.conversation))
            tracked.history = view.loaded
            hooks.contextLoaded(turn, view.loaded)
            val sections = hooks.promptSections(turn, sections(turn, message, narrowed.toSet(), selected.info))
            val summaryModel = agent.summaryModel ?: selected.ref
            val window = compactor.open(turn, agent.config.compaction, selected.ref, selected.info, summaryModel, view)
            val parts = RequestParts(selected, options, window, sections, definitions, context)
            val turnTools = TurnTools((offered + toolset.unoffered).associateBy { it.definition.name }, callable)
            val unstored = notes.mapNotNull { it.notice } + listOfNotNull(opening)
            if (input !is TurnSource.FromJob) return rounds(parts, unstored, turnTools, notes)
            store(unstored, notes)
            if (input.job.final) jobs.delivered(input.job.job)
            return rounds(parts, emptyList(), turnTools, emptyList())
        }

        /** Ends the turn as [cause] cut it off, unless it had ended. */
        suspend fun cutOff(cause: CancellationException): TurnOutcome {
            outcome?.let { return it }
            val cancelled = cause is TurnCancelled
            answerOpenCalls { call -> cutOffResult(call, shutdown = !cancelled) }
            sink?.takeUnless { replied }?.let { sink ->
                val end = if (cancelled) ReplyEnd.CANCELLED else ReplyEnd.SHUTDOWN
                attempt("abandon its reply") { sink.abandon(end, turn?.let(::vouched) == true) }
            }
            val outcome = if (cancelled) {
                TurnOutcome.Cancelled
            } else {
                val replayable = stored.all { it is SummaryEntry } && !replied && previews?.shown != true
                TurnOutcome.ShutDown(replayable)
            }
            return end(outcome, if (cancelled) TurnEndKind.CANCELLED else TurnEndKind.SHUT_DOWN)
        }

        /** Ends the turn after [error], with a notice where the chat can still get one. */
        suspend fun fail(error: Throwable): TurnOutcome {
            logger.error("Turn {} of {} failed", plan.id, plan.key, error)
            outcome?.let { return it }
            val failed = TurnOutcome.Failed(error.toString())
            val turn = turn ?: return failed
            answerOpenCalls()
            val text = notices.text(turn, TextKeys.FAILED)
            if (recorded) attempt("store its failure notice") { append(NoticeEntry(null, text, NoticeKind.FAILED)) }
            sink?.takeUnless { replied }?.let { sink ->
                val message = try {
                    notice(text, plan.trigger, turn.conversation)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Turn {} of {} has no failure notice to show: {}", plan.id, plan.key, e.toString())
                    null
                }
                if (message == null) {
                    attempt("abandon its reply") { sink.abandon(ReplyEnd.FAILED, vouched(turn)) }
                } else {
                    showNotice(sink, turn, message)
                }
            }
            if (sink == null) attempt("send its failure notice") { routeNotice(turn, text) }
            return end(failed, TurnEndKind.FAILED)
        }

        /** The turn as its worker takes it, with the settings of its key as they are now. */
        private suspend fun dequeue(): TurnInfo {
            val key = plan.key
            agent = checkNotNull(directory.agent(key.agent)) { "No agent '${key.agent}' is configured." }
            val delegation = delegation
            val language: LanguageTag?
            val zone: ZoneId
            if (delegation == null) {
                model = settings.model(key) ?: checkNotNull(agent.model) { "Agent '${agent.id}' has no model." }
                reasoning = settings.reasoning(key) ?: agent.reasoning
                language = settings.language(key) ?: agent.language
                zone = settings.effectiveZone(key, agent, clock)
                maxRounds = agent.config.maxRounds
                maxTurnTokens = agent.config.maxTurnTokens
            } else {
                val profile = delegation.profile
                model = profile.model ?: delegation.parent.model
                reasoning = profile.reasoning ?: delegation.parent.reasoning
                language = delegation.parent.turn.language
                zone = delegation.parent.turn.zone ?: clock.zone
                maxRounds = profile.maxRounds
                maxTurnTokens = profile.maxTurnTokens
            }
            val input = plan.input
            val conversation = when {
                delegation != null -> delegation.conversation.also { inherited = inherited(it) }

                input is TurnSource.FromJob -> checkNotNull(
                    conversations.conversation(input.job.job.resultConversation),
                ) {
                    "The conversation of job ${input.job.job.id} is gone."
                }

                plan.kind == TurnKind.HEARTBEAT -> conversations.heartbeatBase(key)

                else -> conversations.current(key)
            }
            resolved(conversation.id)
            taint = taints.open(plan.id, key, conversation.id)
            delegation?.opened(taint)
            mandated = when (input) {
                is TurnSource.FromJob -> input.job.job.mandated
                is TurnSource.FromDelegation -> input.delegation.parent.mandated
                is TurnSource.FromAgentMessage -> input.message.mandated
                else -> false
            }
            val actor = when {
                plan.actor == null -> mandated(input, key)?.also { mandated = true }

                mandated && taint.tainted -> null.also {
                    logger.info("Turn {} of {} runs as nobody: its conversation is tainted", plan.id, key)
                }

                else -> plan.actor
            }
            val turn = TurnInfo.builder(plan.id, plan.origin, conversation.id, plan.kind)
                .actor(actor)
                .language(language)
                .zone(zone)
                .agent(key.agent)
                .lineage(delegation?.lineage)
                .replyTarget(plan.replyTarget)
                .key(key)
                .build()
            this.turn = turn
            meter.turn = turn
            delegation?.started(turn, taint)
            return turn
        }

        /**
         * The operator whose mandate [input] runs under, null for a turn of nobody: when it is no initiated turn of a
         * mandate, its prompt is not trusted, the mandate is not its plugin's at [key], or the conversation took in
         * something untrusted.
         */
        private suspend fun mandated(input: TurnSource, key: AgentChatKey): ChatUser? {
            val mandate = (input as? TurnSource.FromInitiated)?.mandate ?: return null
            if (input.trust != Trust.TRUSTED) {
                logger.info("Turn {} of {} runs as nobody: its mandated prompt is not trusted", plan.id, key)
                return null
            }
            val actor = mandates.actor(mandate, input.plugin, key) ?: return null
            if (taint.tainted) {
                logger.info("Turn {} of {} runs as nobody, not as {}: its conversation is tainted", plan.id, key, actor)
                return null
            }
            return actor
        }

        /** [turn] as its next call acts: under a mandate, as nobody once its conversation is tainted. */
        private fun acting(turn: TurnInfo): TurnInfo {
            if (!mandated || !taint.tainted || turn.actor == null) return turn
            demoted?.let { return it }
            logger.info("Turn {} of {} acts as nobody from now: its conversation is tainted", turn.id, turn.key)
            return turn.toBuilder().actor(null).build().also { demoted = it }
        }

        /** The entries of the conversations that [conversation] forks, up to each fork point. */
        private suspend fun inherited(conversation: ConversationInfo): List<TranscriptEntry> {
            val fork = conversation.fork ?: return emptyList()
            val forked = conversations.conversation(fork.conversation) ?: return emptyList()
            val through = fork.through.value
            return inherited(forked) +
                history.entries(forked.id).filter { (it.record?.id?.value ?: through) <= through }
        }

        /** The tools of the turn, which for a sub-agent run are those of its profile that the turn above may call. */
        private fun toolsOf(selected: SelectedModel): Toolset {
            val all = tools.offered(agent.id, selected.ref, selected.info.nativeTools)
            return delegation?.tools(all) ?: Toolset(all, emptyList(), emptySet())
        }

        /** The system prompt sections of [turn], which answers [message] and offers the tools [offered]. */
        private suspend fun sections(
            turn: TurnInfo,
            message: IncomingMessage?,
            offered: Set<String>,
            model: ModelInfo,
        ): List<PromptSection> {
            val prompted = PromptTurn(turn, offered, model)
            val delegation = delegation ?: return prompt.sections(prompted, message?.chatInfo?.kind)
            return prompt.delegated(prompted, delegation.profile, delegation.delegator)
        }

        /**
         * Whether the agent vouches for [turn]'s reply: its conversation is clean, and either a plugin started it with a
         * trusted prompt, such as a heartbeat or an operator's scheduled task, or its actor is trusted and its message
         * brings in nothing that [markUntrusted] would mark.
         */
        private fun vouched(turn: TurnInfo): Boolean {
            if (taint.tainted) return false
            val input = plan.input
            if (input is TurnSource.FromInitiated) return input.trust == Trust.TRUSTED
            val message = input.message
            val actor = turn.actor ?: return false
            return trust.isTrusted(turn.agent, actor) && message?.forwarded == null &&
                message?.quote?.trust?.content != false
        }

        /** Opens the reply of a turn that replies to its chat, false when the chat's channel is not open. */
        private suspend fun openReply(turn: TurnInfo): Boolean {
            if (turn.replyTarget != ReplyTarget.CHAT) {
                delegation?.let { openRunReply(turn, it) }
                return true
            }
            val channel = channels.channel(turn.chat.instance)
            if (channel == null) {
                logger.warn("Turn {} fails: channel instance {} is not open", turn.id, turn.chat.instance)
                return false
            }
            val capabilities = channel.capabilities(turn.chat)
            val sink = channel.openReply(ReplyRequest(turn, plan.trigger))
            this.capabilities = capabilities
            this.sink = sink
            if (capabilities.streaming) previews = previews(turn, sink)
            return true
        }

        /**
         * Opens the reply of [delegation], the run that [turn] is, where its chat's channel gives each run a reply of
         * its own and shows the activity of the turn above.
         */
        private suspend fun openRunReply(turn: TurnInfo, delegation: Delegation) {
            if (!delegation.parent.activityVisible) return
            val channel = channels.channel(turn.chat.instance) ?: return
            attempt("open its run's reply") {
                val capabilities = channel.capabilities(turn.chat)
                if (!capabilities.runReplies) return@attempt
                val reply = channel.openReply(ReplyRequest(turn, null, delegation.shown))
                this.capabilities = capabilities
                runReply = reply
                if (capabilities.streaming) previews = previews(turn, reply)
            }
        }

        private fun previews(turn: TurnInfo, sink: ReplySink): ReplyStream {
            val interval = Duration.ofMillis(agentSettings.previewIntervalMillis)
            return ReplyStream(turn.id, sink, interval, clock) { segment, text ->
                hooks.responsePreview(turn, segment, text)
            }
        }

        /** The turn context of [turn], whose input reads [text], placed for its requests to [model] with [options]. */
        private suspend fun turnContext(
            turn: TurnInfo,
            text: String,
            model: SelectedModel,
            options: ModelOptions,
        ): TurnContextPlan {
            val items = hooks.contextInject(turn, text, emptyList())
            contextItems = items
            val context = turnContextPlan(items) { model.endpoint.turnContextMode(model.ref.model, options, it) }
            if (context.tainted) taint.mark("untrusted turn context")
            return context
        }

        /** The unstored opening entry of [turn] from [input], which reads [text], with the turn context [baked]. */
        private suspend fun opening(
            turn: TurnInfo,
            input: TurnSource,
            text: String,
            baked: List<ContextPart>,
        ): UserEntry = when (input) {
            is TurnSource.FromMessage -> {
                markUntrusted(turn, input.message)
                entry(turn, input.message, text, baked)
            }

            is TurnSource.FromInitiated -> {
                if (input.trust == Trust.UNTRUSTED) taint.mark("an untrusted prompt of plugin '${input.plugin}'")
                initiatedEntry(
                    input.plugin,
                    turn.kind,
                    input.trust,
                    plan.queuedAt,
                    text,
                    turn.zone ?: clock.zone,
                    baked,
                    turn.actor,
                )
            }

            is TurnSource.FromAgentMessage -> {
                input.message.taint?.let { taint.mark(it) }
                initiatedEntry(
                    "alexandrite-agent",
                    turn.kind,
                    input.message.trust,
                    plan.queuedAt,
                    text,
                    turn.zone ?: clock.zone,
                    baked,
                    turn.actor,
                    input.message.framing,
                )
            }

            is TurnSource.FromJob -> jobOpening(turn, input.job, text)

            is TurnSource.FromDelegation -> delegationEntry(input.delegation, text, baked)
        }

        /** The unstored entry of the result of [job], which reads [text], after the turn took in the job's taint. */
        private suspend fun jobOpening(turn: TurnInfo, job: EndedJob, text: String): UserEntry {
            job.untrusted?.let { taint.mark(it) }
            reads.reason(turn, job.job.call.name, job.touched)?.let { taint.mark(it) }
            val stored = media.stored(turn, job.result, mediaPlan)
            val carried = job.result.content.zip(stored.content).mapNotNull { (part, kept) ->
                (kept as? UserPart)?.takeIf { part is MediaPart }
            }
            return jobEntry(job, text, turn.zone ?: clock.zone, carried)
        }

        /** Whether the conversation of [job] still takes entries. */
        private suspend fun stillActive(job: EndedJob): Boolean =
            conversations.conversation(job.job.resultConversation)?.state == ConversationState.ACTIVE

        /** Drops the result of [job], whose conversation was sealed since the job started, telling its chat. */
        private suspend fun dropped(job: EndedJob): TurnOutcome = withContext(NonCancellable) {
            val started = job.job
            if (!job.final) {
                jobs.stop(started.id, started.turn.chat, null)
                return@withContext TurnOutcome.Completed(null)
            }
            logger.info(
                "The result of job {} ({}) is dropped: its conversation {} of {} was sealed",
                started.id,
                described(started),
                started.resultConversation,
                plan.key,
            )
            val text = notices.text(
                started.turn,
                TextKeys.JOB_DROPPED,
                "job" to started.id,
                "call" to described(started),
            )
            val chat = started.turn.chat
            val delivery = channels.channel(chat.instance)?.let { channel ->
                delivered { channel.send(chat, notice(text, null, null)) }
            }
            if (delivery !is Delivery.Delivered) {
                logger.warn("Chat {} is not told that the result of job {} is dropped: {}", chat, started.id, delivery)
            }
            if (job.final) jobs.delivered(started)
            TurnOutcome.Completed(null)
        }

        /** Whether [input] runs once its agent used up a budget: an operator's own message, or a run in its turn. */
        private fun runsPastBudgets(input: TurnSource): Boolean = when (input) {
            is TurnSource.FromMessage -> plan.actor?.isOperator == true
            is TurnSource.FromDelegation -> !input.delegation.background
            else -> false
        }

        /**
         * Refuses the turn past a budget, noting a job's dropped result for the conversation's next turn and telling
         * the chat where that is news.
         */
        private suspend fun overBudget(input: TurnSource): TurnOutcome = withContext(NonCancellable) {
            val agent = plan.key.agent
            logger.info("Turn {} of {} is refused: agent '{}' used up a budget", plan.id, plan.key, agent)
            val job = (input as? TurnSource.FromJob)?.job
            val chat = job?.job?.turn?.chat ?: plan.origin
            val message = input is TurnSource.FromMessage
            if (job?.final != false && (message || budgets.firstRefusal(agent, chat))) {
                val name = directory.agent(agent)?.name ?: agent.value
                val text = if (job == null) {
                    notices.text(plan.key, TextKeys.BUDGET_REFUSED, "agent" to name)
                } else {
                    val call = described(job.job)
                    notices.text(
                        plan.key,
                        TextKeys.BUDGET_JOB_REFUSED,
                        "agent" to name,
                        "job" to job.job.id,
                        "call" to call,
                    )
                }
                val delivery = channels.channel(chat.instance)?.let { channel ->
                    delivered { channel.send(chat, notice(text, plan.trigger.takeIf { message }, null)) }
                }
                if (delivery !is Delivery.Delivered) {
                    logger.warn("Chat {} is not told that turn {} is refused: {}", chat, plan.id, delivery)
                }
            }
            if (job != null) {
                if (job.final && !job.cancelled()) {
                    val started = job.job
                    val dropped = InterruptedJob(started.id, started.call.name, started.title, pastBudget = true)
                    interruptedJobs.add(started.resultConversation, dropped)
                }
                if (job.final) jobs.delivered(job.job)
                job.consumed()
            }
            if (input is TurnSource.FromDelegation) {
                TurnOutcome.Failed("Agent '$agent' used up a budget.")
            } else {
                TurnOutcome.Completed(null)
            }
        }

        /** Stores the [opening] entries before the first round, and that [notes] were told. */
        private suspend fun store(opening: List<UserEntry>, notes: List<PendingNote>) {
            withContext(NonCancellable) {
                append(*opening.toTypedArray())
                notes.forEach { it.told() }
            }
        }

        /**
         * What waits to be told at the start of [turn] of a conversation the turn shares, offered [definitions], which
         * marks it tainted where it carries untrusted content.
         */
        private suspend fun pendingNotes(turn: TurnInfo, definitions: List<ToolDefinition>): List<PendingNote> {
            val found = notes.mapNotNull { it.pending(turn, definitions) }
            found.forEach { note -> note.taint?.let { taint.mark(it) } }
            return found
        }

        /** The unstored entry of [message] in [turn], whose words read [text], with the turn context [baked]. */
        private suspend fun entry(
            turn: TurnInfo,
            message: IncomingMessage,
            text: String,
            baked: List<ContextPart>,
        ): UserEntry {
            val linked = topology.group(turn.agent, message.chat) != null
            return messageEntry(
                message,
                text,
                turn.zone ?: clock.zone,
                linked,
                baked + media.put(turn, message, mediaPlan),
            )
        }

        /**
         * Marks [turn] tainted where [message] was forwarded, quotes what its channel does not vouch for or comes from
         * someone whom the agent does not trust, whom an operator is then offered to trust.
         */
        private suspend fun markUntrusted(turn: TurnInfo, message: IncomingMessage) {
            val sender = message.sender
            when {
                message.forwarded != null -> taint.mark("a forwarded message")
                message.quote?.trust?.content == false -> taint.mark("a quote that its channel does not vouch for")
                trust.isTrusted(turn.agent, sender) -> Unit
                taint.tainted -> taint.mark("a message from someone whom the agent does not trust")
                else -> offers.markedSender(turn, taint, sender)
            }
        }

        /** Runs the turn's rounds from its [opening] entries until the turn ends. */
        private suspend fun rounds(
            first: RequestParts,
            opening: List<UserEntry>,
            firstTools: TurnTools,
            notes: List<PendingNote>,
        ): TurnOutcome {
            val turn = checkNotNull(turn)
            var parts = first
            var tools = firstTools
            var pending = opening
            var round = 0
            var toolRounds = 0
            var pauses = 0
            var noted = false
            var retried = -1
            var retry: ModelOptions? = null
            while (true) {
                if (round > 0) previews?.nextSegment()
                val limit = limit(toolRounds)
                if (limit != null && !noted) {
                    pending = pending + limitEntry(limit, maxRounds, maxTurnTokens?.takeIf { spent.get() >= it })
                    noted = true
                }
                val final = limit != null
                val options = retry ?: parts.options
                retry = null
                val operatorCall = plan.operatorCall?.takeIf { round == 0 }
                val response = if (operatorCall != null) {
                    operatorResponse(operatorCall, parts.model.ref)
                } else {
                    val built = fitted(parts, options, pending, round, final) ?: return windowFull(parts.model)
                    val request = hooks.llmRequest(turn, round, built).or { return stopped(it) }
                    val response = try {
                        call(parts.model, request)
                    } catch (e: ModelException) {
                        val moved = moveOn(parts, e.error) ?: return modelFailed(e.error)
                        parts = moved.first
                        tools = moved.second
                        continue
                    }
                    usage = usage?.plus(response.usage) ?: response.usage
                    spend(spentOn(response.usage))
                    parts.window.measured(request, response.usage)
                    emitUsageUpdated(usage, parts.window.tokens)
                    hooks.llmResponse(turn, round, response).or { return stopped(it) }
                    response
                }
                val message = response.message
                val calls = message.parts.filterIsInstance<ToolCallPart>()
                val finish = response.finish.kind
                val paused = finish == FinishKind.PAUSED && calls.isEmpty() && pauses < MAX_PAUSES
                val first = pending.isNotEmpty()
                val quiet = turn.kind in SILENT_KINDS || chatReplies.answered(turn.id)
                val retries = retried < round && !quiet
                when {
                    finish == FinishKind.CONTEXT_WINDOW_EXCEEDED -> return windowFull(parts.model)

                    finish == FinishKind.REFUSAL -> return unanswered(first, NoticeKind.REFUSED, TextKeys.REFUSED)

                    paused -> Unit

                    message.blank && finish == FinishKind.MAX_OUTPUT_TOKENS && retries -> {
                        logger.info("Turn {} of {} asks again: its blank answer was cut off", plan.id, plan.key)
                        retried = round
                        retry = roomier(options, parts.model.info)
                        continue
                    }

                    message.blank && finish == FinishKind.MAX_OUTPUT_TOKENS ->
                        return unanswered(first, NoticeKind.OUTPUT_LIMIT, TextKeys.OUTPUT_LIMIT_UNANSWERED)

                    message.blank && retries -> {
                        logger.info("Turn {} of {} asks again: the model gave a blank answer", plan.id, plan.key)
                        retried = round
                        continue
                    }

                    message.blank && !quiet -> return unanswered(first, NoticeKind.BLANK_REPLY, TextKeys.BLANK_REPLY)
                }
                val reply = withContext(NonCancellable) {
                    val entries = append(*(pending + message).toTypedArray())
                    if (first) notes.forEach { it.told() }
                    entries.last() as AssistantEntry
                }
                pending = emptyList()
                round++
                when {
                    paused -> pauses++

                    limit != null -> return withContext(NonCancellable) { limited(reply, calls, limit) }

                    calls.isEmpty() && awaitedRuns(turn) -> {
                        absorbJobs(turn)
                        absorbFollowUps(turn)
                    }

                    calls.isEmpty() -> return withContext(NonCancellable) {
                        val cutShort = finish == FinishKind.MAX_OUTPUT_TOKENS
                        answer(reply, (NoticeKind.OUTPUT_LIMIT to TextKeys.OUTPUT_LIMIT).takeIf { cutShort })
                    }

                    else -> {
                        runCalls(turn, calls, tools)
                        if (operatorCall != null) return withContext(NonCancellable) { operated(reply) }
                        absorbJobs(turn)
                        absorbFollowUps(turn)
                        toolRounds++
                    }
                }
            }
        }

        /**
         * The request of [round] once old tool results are cleared or old entries folded where it grew past its
         * thresholds, null when it cannot fit into the model's context window.
         */
        private suspend fun fitted(
            parts: RequestParts,
            options: ModelOptions,
            pending: List<UserEntry>,
            round: Int,
            toolless: Boolean,
        ): ModelRequest? {
            val current = appending.withLock { stored.toList() } + pending
            val build = suspend { request(parts, options, pending, round, toolless) }
            val fit = parts.window.fit(current, round, build) { summary ->
                append(summary).single() as SummaryEntry
            }
            val compaction = fit.compaction
            compaction?.usage?.let { spend(spentOn(it)) }
            if (compaction is Compaction.Done) {
                emit(TurnEventKind.COMPACTED) { TurnEvent.Compacted(compaction.tokensBefore, compaction.tokensAfter) }
            }
            return fit.request
        }

        /** The request of [round] with [options], whose history ends with what the turn stored and [pending]. */
        private suspend fun request(
            parts: RequestParts,
            options: ModelOptions,
            pending: List<UserEntry>,
            round: Int,
            toolless: Boolean,
        ): ModelRequest {
            val turn = checkNotNull(turn)
            val (entries, turnStart) = parts.window.history(appending.withLock { stored.toList() } + pending)
            val history = crossedTo(parts.model.ref, media.inline(entries, mediaPlan))
            val ids = RequestIds(turn.conversation, turn.id, round)
            return ModelRequest.builder(parts.model.ref, history, turnStart, ids)
                .instructions(parts.sections)
                .turnContext(parts.context.kept)
                .tools(parts.tools)
                .toolChoice(if (toolless) ToolChoice.None else ToolChoice.Auto)
                .options(options)
                .cacheKey(cacheKey(turn.key))
                .build()
        }

        /** The model [ref] names, as its endpoint lists it. */
        private suspend fun selection(ref: ModelRef): Selection = try {
            endpoints.select(ref)?.let(Selection::Found) ?: Selection.Unknown
        } catch (e: ModelException) {
            Selection.Failed(e.error)
        }

        /** The model the turn moves on to once its own failed, null when it moved on already or is a sub-agent run. */
        private fun nextAfterFailure(): ModelRef? =
            if (movedFrom != null || delegation != null) null else nextModel(agent.models, model)

        /**
         * The next model, which the turn's later requests go to as its own failed for [why]; null when the turn does not
         * move on or that model fails too.
         */
        private suspend fun movedOn(why: String): SelectedModel? {
            val next = nextAfterFailure() ?: return null
            val selected = (selection(next) as? Selection.Found)?.model
            if (selected == null) {
                logger.warn("Turn {} of {} cannot move on to model {} either", plan.id, plan.key, next)
                return null
            }
            logger.warn("Turn {} of {} moves on from model {} ({}) to {}", plan.id, plan.key, model, why, next)
            movedFrom = model
            movedWhy = why
            model = next
            return selected
        }

        /**
         * The parts and tools of the turn's requests to the next model once [error] failed the request of [parts], null
         * when the turn does not move on.
         */
        private suspend fun moveOn(parts: RequestParts, error: ModelError): Pair<RequestParts, TurnTools>? {
            if (!movesOn(error)) return null
            val selected = movedOn(error.kind.toString()) ?: return null
            val next = model
            previews?.discard()
            mediaPlan = routing.plan(agent.id, selected.info)
            val turn = checkNotNull(turn)
            val options = requestOptions(agent, selected.info, reasoning)
            val toolset = toolsOf(selected)
            val names = parts.tools.mapTo(HashSet()) { it.name }
            val definitions = toolset.offered.map { (it as? RenderedTool)?.definitionFor(turn) ?: it.definition }
                .filter { it.name in names }
            val contextPlan = turnContextPlan(contextItems) {
                selected.endpoint.turnContextMode(next.model, options, it)
            }
            if (contextPlan.tainted && !parts.context.tainted) taint.mark("untrusted turn context")
            val context =
                TurnContextPlan(parts.context.baked, contextPlan.kept, parts.context.tainted || contextPlan.tainted)
            parts.window.moveTo(next, selected.info, agent.summaryModel ?: next)
            val tools = TurnTools((toolset.offered + toolset.unoffered).associateBy { it.definition.name }, callable)
            val sections = hooks.promptSections(turn, sections(turn, plan.input.message, names, selected.info))
            return RequestParts(selected, options, parts.window, sections, definitions, context) to tools
        }

        /** The response of [model] to [request], whose text the chat is shown as it comes. */
        private suspend fun call(model: SelectedModel, request: ModelRequest): ModelEvent.Completed {
            var attempt = 0
            return retries.retrying(plan.id, TokenEstimator.request(request)) {
                attempt++
                try {
                    collectResponse(model.endpoint, request) { event ->
                        previews?.event(event)
                        if (event is ModelEvent.ReasoningDelta) emitThoughtChunk(event.text ?: event.summary)
                    }
                } catch (e: ModelException) {
                    if (e.error.kind == ModelErrorKind.MALFORMED_OUTPUT) {
                        logger.warn(
                            "Turn {} of {}: model {} wrote malformed output on attempt {}: {}",
                            plan.id,
                            plan.key,
                            model.ref,
                            attempt,
                            e.error.message,
                        )
                        previews?.discard()
                    }
                    throw e
                }
            }
        }

        /**
         * Runs [calls] of the turn's [tools], storing each result as its call ends, and then the project instructions
         * that the calls reached.
         */
        private suspend fun runCalls(turn: TurnInfo, calls: List<ToolCallPart>, tools: TurnTools) {
            val timeout = agent.config.toolTimeoutSeconds.seconds
            val registry = this@AgentTurnRunner.tools
            val parallel = calls.all { call ->
                val tool = tools.offered[call.name] ?: return@all true
                tool.definition.risk == ToolRisk.READ_ONLY && (registry.isBuiltIn(tool) || tool !is ReviewedTool)
            }
            val slots = Semaphore(if (parallel) MAX_PARALLEL_CALLS else 1)
            val loading = Collections.synchronizedList(mutableListOf<LoadedFile>())
            val untrusted = Collections.synchronizedList(mutableListOf<UntrustedFolder>())
            val skills = Collections.synchronizedList(mutableListOf<UserEntry>())
            val budget = InstructionBudget()
            var loaded = false
            val workspace = workspaces.paths[turn.agent]
            for (call in calls) {
                val definition = tools.offered[call.name]?.definition ?: continue
                val title = relativized(toolTitle(definition, call.parseArguments()), workspace)
                emitToolCallStarted(call.id, definition.name, definition.activity, title)
            }
            try {
                coroutineScope {
                    for (call in calls) {
                        launch {
                            slots.withPermit {
                                val acting = acting(turn)
                                val callable =
                                    if (acting === turn) tools.callable else registry.callable(acting, tools.callable)
                                val end = executor.execute(
                                    acting,
                                    call,
                                    tools.offered,
                                    timeout,
                                    callable,
                                    taint,
                                    tracked,
                                    plan.trigger,
                                )
                                reads.reason(turn, call.name, end.tainting)?.let { taint.mark(it) }
                                taint.record()
                                val found = projects.inspect(turn, end.touched, budget)
                                loading += found.loaded
                                untrusted += found.untrusted
                                skills += end.loads
                                val result = withContext(NonCancellable) {
                                    appending.withLock {
                                        appendLocked(listOf(media.stored(turn, found.noted(end.result), mediaPlan)))
                                            .single()
                                    }
                                }
                                end.notice?.let { notify(NoticeKind.HOOK_ABORTED, it) }
                                hooks.toolAfter(turn, call, result as ToolResultEntry)
                            }
                        }
                    }
                }
                if (untrusted.isNotEmpty()) warn(turn, untrusted.toList())
                val entries = entries(loading) + skills
                if (entries.isNotEmpty()) append(*entries.toTypedArray())
                loaded = true
            } finally {
                if (!loaded) projects.release(turn.conversation, loading.map { it.file })
            }
        }

        /**
         * Offers an operator to trust [folders], whose project instructions the turn did not load, else tells the chat
         * in one notice.
         */
        private suspend fun warn(turn: TurnInfo, folders: List<UntrustedFolder>) {
            val folder = folders.singleOrNull()
            val text = if (folder != null) {
                notices.text(
                    turn,
                    TextKeys.UNTRUSTED_FOLDER,
                    "agent" to agent.name,
                    "folder" to folder.folder,
                    "files" to folder.files.joinToString(),
                )
            } else {
                val named = folders.take(MAX_NAMED_FOLDERS)
                    .joinToString("; ") { "${it.folder} (${it.files.joinToString()})" }
                val more = folders.size - MAX_NAMED_FOLDERS
                val key = if (more > 0) TextKeys.UNTRUSTED_FOLDERS_MORE else TextKeys.UNTRUSTED_FOLDERS
                notices.text(turn, key, "agent" to agent.name, "folders" to named, "count" to more)
            }
            append(NoticeEntry(null, text, NoticeKind.UNTRUSTED_FOLDER))
            val trigger = plan.trigger
            val notice = notice(text, trigger, turn.conversation).takeIf { sink != null }
            offers.offerInstructions(turn, folders, trigger?.chat ?: turn.chat, notice)
        }

        /** What makes the turn's next round its last after [toolRounds] tool rounds, null when nothing does. */
        private fun limit(toolRounds: Int): TurnLimit? = when {
            maxRounds?.let { toolRounds >= it } == true -> TurnLimit.ROUNDS
            overBudget() -> TurnLimit.BUDGET
            else -> null
        }

        /** Whether the turn, or a turn it works for, used up its token budget. */
        private fun overBudget(): Boolean = maxTurnTokens?.let { spent.get() >= it } == true ||
            delegation?.takeUnless { it.background }?.parent?.overBudget == true

        /** Counts [tokens] toward the turn's budget and toward that of each turn it works for. */
        private fun spend(tokens: Long) {
            spent.addAndGet(tokens)
            delegation?.takeUnless { it.background }?.parent?.spend(tokens)
        }

        /**
         * Whether a report came in from a background run that this run started, or a person sent the run a message,
         * waited for without the turn's permit, so that the run answers only once none is left; false at once for a
         * top-level turn.
         */
        private suspend fun awaitedRuns(turn: TurnInfo): Boolean =
            delegation != null && permit.released { followUps.awaitJobs(turn.conversation) }

        /** Takes in the results of the jobs that turns from the turn's chat started in its conversation. */
        private suspend fun absorbJobs(turn: TurnInfo) {
            val taken = ArrayDeque(followUps.takeJobs(turn.chat, turn.conversation))
            try {
                while (taken.isNotEmpty()) absorbJob(turn, taken)
            } finally {
                if (taken.isNotEmpty()) followUps.restore(taken.toList())
            }
        }

        /** Takes the result of the first job of [taken] into the turn. */
        private suspend fun absorbJob(turn: TurnInfo, taken: ArrayDeque<FollowUp>) {
            val followUp = taken.first()
            val job = (followUp.plan.input as TurnSource.FromJob).job
            if (job.cancelled()) {
                taken.removeFirst()
                job.consumed()
                followUp.complete(TurnOutcome.TakenBack)
                return
            }
            val entry = jobOpening(turn, job, job.text)
            withContext(NonCancellable) {
                taken.removeFirst()
                try {
                    if (job.cancelled()) {
                        followUp.complete(TurnOutcome.TakenBack)
                        return@withContext
                    }
                    append(entry)
                    if (job.final) jobs.delivered(job.job)
                    followUp.complete(TurnOutcome.Absorbed(turn.id))
                    logger.debug("Turn {} of {} takes in the result of job {}", turn.id, turn.key, job.job.id)
                } catch (e: Throwable) {
                    followUp.complete(TurnOutcome.Failed(e.toString()))
                    throw e
                } finally {
                    job.consumed()
                }
            }
        }

        /** Absorbs the messages that the turn's principal queued since the turn started, or that people sent its run. */
        private suspend fun absorbFollowUps(turn: TurnInfo) {
            val principal = principal
            val queued = when {
                principal != null -> followUps.take(principal, plan.origin, startedAt)
                delegation != null -> followUps.takeSent()
                else -> return
            }
            val taken = ArrayDeque(queued)
            try {
                while (taken.isNotEmpty()) absorbFirst(turn, taken)
            } finally {
                if (taken.isNotEmpty()) followUps.restore(taken.toList())
            }
        }

        /** Takes the first of [taken] into the turn, or drops it with a notice where `turn.input` refuses it. */
        private suspend fun absorbFirst(turn: TurnInfo, taken: ArrayDeque<FollowUp>) {
            val followUp = taken.first()
            val message = checkNotNull(followUp.plan.input.message)
            val input = hooks.turnInput(turn, message, message.text, followUp = true)
            val entry = (input as? Hooked.Proceed)?.let { entry(turn, message, it.value, emptyList()) }
            withContext(NonCancellable) {
                taken.removeFirst()
                try {
                    if (entry != null) {
                        markUntrusted(turn, message)
                        append(entry)
                        wordsOf(message)?.let(tracked.words::add)
                        followUp.complete(TurnOutcome.Absorbed(turn.id))
                        val absorbed = followUp.plan.id
                        logger.debug("Turn {} of {} absorbs turn {} as a follow-up", turn.id, turn.key, absorbed)
                    } else {
                        val reply = (input as? Hooked.Stopped)?.reply?.takeIf { it.isNotBlank() }
                        notify(NoticeKind.HOOK_ABORTED, reply ?: notices.text(turn, TextKeys.HOOK_ABORTED), message.ref)
                        followUp.complete(TurnOutcome.Completed(null))
                    }
                } catch (e: Throwable) {
                    followUp.complete(TurnOutcome.Failed(e.toString()))
                    throw e
                }
            }
        }

        /** Answers each call that the turn stored without a result with [result], as cancelled unless given. */
        private suspend fun answerOpenCalls(
            result: (ToolCallPart) -> ToolResultEntry = { textResult(it, CANCELLED_RESULT, ToolOutcome.Cancelled) },
        ) {
            val open = openCalls(appending.withLock { stored.toList() })
            if (open.isEmpty()) return
            attempt("store the results of its open calls") { append(*open.map(result).toTypedArray()) }
        }

        /** The result of [call] that the turn's end cut off, at a [shutdown] or a cancel. */
        private fun cutOffResult(call: ToolCallPart, shutdown: Boolean): ToolResultEntry {
            val request = tracked.waiting[call.id]
            return when {
                call.id in tracked.started -> textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)

                shutdown && request != null ->
                    textResult(call, Refusals.INTERRUPTED, ToolOutcome.NotRun(NotRunReason.INTERRUPTED, request))

                else -> textResult(call, Refusals.CANCELLED, ToolOutcome.NotRun(NotRunReason.CANCELLED, request))
            }
        }

        /** Answers [calls] of the final round as not run, and ends the turn on [reply] with the notice of [limit]. */
        private suspend fun limited(reply: AssistantEntry, calls: List<ToolCallPart>, limit: TurnLimit): TurnOutcome {
            when (limit) {
                TurnLimit.ROUNDS -> logger.info(
                    "Turn {} of {} used up its {} tool rounds",
                    plan.id,
                    plan.key,
                    maxRounds,
                )

                TurnLimit.BUDGET -> logger.info(
                    "Turn {} of {} used up its budget of {} tokens with {}",
                    plan.id,
                    plan.key,
                    maxTurnTokens ?: "a turn it works for",
                    spent.get(),
                )
            }
            val notRun = ToolOutcome.NotRun(limit.reason)
            if (calls.isNotEmpty()) append(*calls.map { textResult(it, limit.result, notRun) }.toTypedArray())
            return answer(reply, limit.notice to limit.key)
        }

        /**
         * Ends the turn after [reply], the call it made for its operator, without asking its model about the result,
         * which the agent reads in its next turn; the reply showed the call alone. A model that needs the reasoning
         * behind each call it made since the last message, like DeepSeek's, would refuse to go on from a call it did
         * not make.
         */
        private suspend fun operated(reply: AssistantEntry): TurnOutcome {
            decided()
            val turn = checkNotNull(turn)
            sink?.takeUnless { replied }?.let { sink ->
                replied = true
                attempt("end its reply") { sink.abandon(ReplyEnd.COMPLETED, vouched(turn)) }
            }
            return end(TurnOutcome.Completed(reply), TurnEndKind.COMPLETED)
        }

        /** Delivers the stored [reply] as the turn's answer, followed by the notice [after]. */
        private suspend fun answer(reply: AssistantEntry, after: Pair<NoticeKind, String>?): TurnOutcome {
            decided()
            val turn = checkNotNull(turn)
            val text = visibleText(reply)
            val quiet = turn.kind in SILENT_KINDS || chatReplies.answered(turn.id)
            if (text.isBlank() && quiet && after == null) {
                return end(TurnOutcome.Completed(reply), TurnEndKind.COMPLETED)
            }
            if (text.isEmpty()) {
                val (kind, key) = after ?: (NoticeKind.BLANK_REPLY to TextKeys.BLANK_REPLY)
                return ended(kind, notices.text(turn, key), TurnOutcome.Completed(null), TurnEndKind.COMPLETED)
            }
            val deliveries = sink?.let { mapOf(turn.chat to deliver(it, turn, text)) } ?: route(turn, text)
            after?.let { (kind, key) -> notify(kind, notices.text(turn, key)) }
            movedFrom?.let { from ->
                val text = notices.text(turn, TextKeys.MODEL_SWITCHED, "from" to from, "why" to movedWhy, "to" to model)
                notify(NoticeKind.MODEL_SWITCHED, text)
            }
            return end(TurnOutcome.Completed(reply, deliveries), TurnEndKind.COMPLETED)
        }

        private suspend fun deliver(sink: ReplySink, turn: TurnInfo, text: String): Delivery {
            val drafted = replyMessage(text, capabilities, plan.trigger, turn.conversation, vouched(turn))
            val reply = hooks.responseBefore(turn, drafted, turn.chat)
            return fallBack(turn, turn.chat, reply, show(sink, reply))
        }

        /**
         * The [delivery] of the final [reply] to [chat], else that of the reply sent again as plain text, after which
         * the chat is told in a notice where that fails too.
         */
        private suspend fun fallBack(
            turn: TurnInfo,
            chat: ChatAddress,
            reply: OutboundMessage,
            delivery: Delivery,
        ): Delivery {
            if (delivery !is Delivery.NotDelivered || delivery.kind in UNREACHABLE) return delivery
            if (delivery.kind == DeliveryFailure.TOO_LONG) {
                notify(NoticeKind.TOO_LONG, notices.text(turn, TextKeys.REPLY_TOO_LONG))
                return delivery
            }
            val plain = channels.channel(chat.instance)?.let { channel ->
                delivered { channel.send(chat, reply.toBuilder().markup(Markup.PLAIN).build()) }
            }
            if (plain is Delivery.Delivered) {
                logger.info("The reply of turn {} reached chat {} as plain text, after {}", turn.id, chat, delivery)
                return plain
            }
            logger.warn("The reply of turn {} did not reach chat {} as plain text either: {}", turn.id, chat, plain)
            val text = notices.text(turn, TextKeys.REPLY_UNDELIVERED, "kind" to delivery.kind)
            append(NoticeEntry(null, text, NoticeKind.UNDELIVERED))
            val told = channels.channel(chat.instance)?.let { channel ->
                delivered { channel.send(chat, notice(text, reply.replyTo, turn.conversation)) }
            }
            if (told !is Delivery.Delivered) {
                logger.warn("Turn {} could not tell chat {} that its reply did not arrive: {}", turn.id, chat, told)
            }
            return delivery
        }

        /** Sends [text] to each destination of the turn's route that it may reach. */
        private suspend fun route(turn: TurnInfo, text: String): Map<ChatAddress, Delivery> =
            routes.destinations(turn.agent, plan.route).mapValues { (chat, refusal) ->
                refusal ?: send(turn, text, chat)
            }

        /** Sends [text] to [destination] as the turn's reply. */
        private suspend fun send(turn: TurnInfo, text: String, destination: ChatAddress): Delivery {
            val channel = channels.channel(destination.instance)
                ?: return Delivery.NotDelivered(
                    DeliveryFailure.TRANSIENT,
                    "Channel instance ${destination.instance} is not open.",
                ).also { logger.warn("The reply of turn {} did not reach chat {}: {}", turn.id, destination, it) }
            var reply: OutboundMessage? = null
            val delivery = delivered {
                val capabilities = channel.capabilities(destination)
                val drafted = replyMessage(text, capabilities, null, turn.conversation, vouched(turn))
                channel.send(destination, hooks.responseBefore(turn, drafted, destination).also { reply = it })
            }
            if (delivery is Delivery.NotDelivered) {
                logger.warn("The reply of turn {} did not reach chat {}: {}", turn.id, destination, delivery)
            }
            return reply?.let { fallBack(turn, destination, it, delivery) } ?: delivery
        }

        /** Stores the notice [text] of [kind] and sends it beside the turn's reply, replying to [replyTo]. */
        private suspend fun notify(kind: NoticeKind, text: String, replyTo: ChannelMessageRef? = plan.trigger) {
            val turn = checkNotNull(turn)
            append(NoticeEntry(null, text, kind))
            if (sink == null) return
            val chat = replyTo?.chat ?: turn.chat
            val channel = channels.channel(chat.instance) ?: return
            val delivery = delivered { channel.send(chat, notice(text, replyTo, turn.conversation)) }
            if (delivery is Delivery.NotDelivered) {
                logger.warn("Turn {} could not send {} its {} notice: {}", turn.id, chat, kind, delivery)
            }
        }

        /** Ends the turn on the notice [key] of [kind], taking its input back where the response is its [first]. */
        private suspend fun unanswered(first: Boolean, kind: NoticeKind, key: String): TurnOutcome {
            val text = notices.text(checkNotNull(turn), key)
            if (first) return ended(kind, text, TurnOutcome.TakenBack, TurnEndKind.TAKEN_BACK)
            return ended(kind, text, TurnOutcome.Completed(null), TurnEndKind.COMPLETED)
        }

        private suspend fun stopped(reply: String?): TurnOutcome {
            val written = reply?.takeIf { it.isNotBlank() }
            val text = written ?: notices.text(checkNotNull(turn), TextKeys.HOOK_ABORTED)
            val completed = TurnOutcome.Completed(null)
            return ended(NoticeKind.HOOK_ABORTED, text, completed, TurnEndKind.COMPLETED, vouched = written == null)
        }

        private suspend fun unknownModel(): TurnOutcome {
            val turn = checkNotNull(turn)
            logger.warn("Turn {} of {} fails: no endpoint lists its model {}", turn.id, turn.key, model)
            return ended(
                NoticeKind.FAILED,
                notices.text(turn, TextKeys.MODEL_UNKNOWN, "model" to model),
                TurnOutcome.Failed("No endpoint lists the model $model."),
                TurnEndKind.FAILED,
            )
        }

        private suspend fun modelFailed(error: ModelError): TurnOutcome {
            val turn = checkNotNull(turn)
            logger.warn("Turn {} fails: its model call failed ({}): {}", turn.id, error.kind, error.message)
            return ended(
                NoticeKind.FAILED,
                notices.text(turn, TextKeys.modelFailure(error.kind), "model" to model, "kind" to error.kind),
                TurnOutcome.Failed("${error.kind}: ${error.message}"),
                TurnEndKind.FAILED,
            )
        }

        private suspend fun windowFull(model: SelectedModel): TurnOutcome {
            val turn = checkNotNull(turn)
            logger.warn("Turn {} fails: its conversation fills the context window of {}", turn.id, model.ref)
            return ended(
                NoticeKind.FAILED,
                notices.text(turn, TextKeys.CONTEXT_WINDOW),
                TurnOutcome.Failed("${FinishKind.CONTEXT_WINDOW_EXCEEDED}: the conversation fills the window."),
                TurnEndKind.FAILED,
            )
        }

        /** Stores a notice of [kind] with [text], shows it and ends the turn with [outcome] as [end]. */
        private suspend fun ended(
            kind: NoticeKind,
            text: String,
            outcome: TurnOutcome,
            end: TurnEndKind,
            vouched: Boolean = true,
        ): TurnOutcome = withContext(NonCancellable) {
            decided()
            val turn = checkNotNull(turn)
            append(NoticeEntry(null, text, kind))
            sink?.let { showNotice(it, turn, notice(text, plan.trigger, turn.conversation, vouched)) }
            closeRun(outcome, end, notice(text, null, turn.conversation, vouched))
            if (sink == null && end == TurnEndKind.FAILED) {
                attempt("send its failure notice") { routeNotice(turn, text) }
            }
            end(outcome, end)
        }

        /** Sends the notice [text] to each destination of the turn's route that it may reach. */
        private suspend fun routeNotice(turn: TurnInfo, text: String) {
            for ((chat, refusal) in routes.destinations(turn.agent, plan.route)) {
                val delivery = refusal ?: channels.channel(chat.instance)?.let { channel ->
                    delivered { channel.send(chat, notice(text, null, turn.conversation)) }
                }
                if (delivery !is Delivery.Delivered) {
                    logger.warn("Turn {} could not send {} its failure notice: {}", turn.id, chat, delivery)
                }
            }
        }

        private suspend fun end(outcome: TurnOutcome, end: TurnEndKind): TurnOutcome {
            decided()
            val turn = turn
            closeRun(outcome, end)
            if (::taint.isInitialized) taint.record()
            if (turn != null && recorded) {
                attempt("record its end") { conversations.endTurn(turn.id, end) }
                hooks.turnCommitted(turn, stored.toList(), outcome, usage)
            }
            this.outcome = outcome
            return outcome
        }

        private suspend fun append(vararg entries: TranscriptEntry): List<TranscriptEntry> =
            withContext(NonCancellable) { appending.withLock { appendLocked(entries.toList()) } }

        /** Stores [entries] while the caller holds [appending]. */
        private suspend fun appendLocked(entries: List<TranscriptEntry>): List<TranscriptEntry> {
            val turn = checkNotNull(turn)
            val appended = transcripts.append(turn.id, entries)
            stored += appended
            history.appended(turn.conversation, appended)
            return appended
        }

        /**
         * Ends the reply of the run the turn is, which ended with [outcome] as [end]: with the run's report, else with
         * [notice], else without a final message.
         */
        private suspend fun closeRun(outcome: TurnOutcome, end: TurnEndKind, notice: OutboundMessage? = null) {
            val reply = runReply?.takeUnless { runClosed } ?: return
            runClosed = true
            val turn = checkNotNull(turn)
            val report = (outcome as? TurnOutcome.Completed)?.reply?.let(::visibleText)?.takeIf { it.isNotBlank() }
            val message = report?.let { replyMessage(it, capabilities, null, turn.conversation, vouched(turn)) }
                ?: notice
            withContext(NonCancellable) {
                attempt("end its run's reply") {
                    if (message != null) reply.complete(message) else reply.abandon(replyEnd(end), vouched(turn))
                }
            }
        }

        /** Shows the final [notice] of [turn] through [sink], else sends it once more beside the reply. */
        private suspend fun showNotice(sink: ReplySink, turn: TurnInfo, notice: OutboundMessage) {
            val delivery = show(sink, notice)
            if (delivery !is Delivery.NotDelivered || delivery.kind in UNREACHABLE) return
            val again = channels.channel(turn.chat.instance)?.let { channel ->
                delivered { channel.send(turn.chat, notice) }
            }
            if (again !is Delivery.Delivered) {
                logger.warn("The notice of turn {} did not reach chat {} again: {}", turn.id, turn.chat, again)
            }
        }

        private suspend fun show(sink: ReplySink, message: OutboundMessage): Delivery {
            replied = true
            val delivery = delivered { sink.complete(message) }
            if (delivery is Delivery.NotDelivered) {
                logger.warn("The {} of turn {} did not reach chat {}: {}", message.kind, plan.id, plan.origin, delivery)
            }
            return delivery
        }

        /** The calls of the turn whose tools started, and those that wait for an approver. */
        private inner class Calls : DelegatingTurn {
            val started: MutableSet<ToolCallId> = ConcurrentHashMap.newKeySet()

            /** The approval request that each call waits for, or waited for when the turn's end cut the wait off. */
            val waiting = ConcurrentHashMap<ToolCallId, String>()

            val words: MutableList<String> = CopyOnWriteArrayList(listOfNotNull(plan.input.message?.let(::wordsOf)))

            /** The entries of the conversation that the turn loaded at its start. */
            @Volatile
            var history: List<TranscriptEntry> = emptyList()

            /** Lets one call of the turn's tree ask at a time. */
            private val asks = Mutex()

            /** The calls that an approver denied in a sub-agent run of the turn's tree. */
            private val denials: MutableSet<String> = ConcurrentHashMap.newKeySet()

            override val turn: TurnInfo get() = checkNotNull(this@Run.turn)

            override val mandated: Boolean get() = this@Run.mandated

            override val model: ModelRef get() = this@Run.model

            override val reasoning: ReasoningEffort? get() = this@Run.reasoning

            override val runAncestors: Set<RunId>
                get() = delegation?.let { it.parent.runAncestors + it.run }.orEmpty()

            override val activityVisible: Boolean get() = delegation?.parent?.activityVisible
                ?: (turn.replyTarget == ReplyTarget.CHAT)

            override val callable: Set<String> get() = this@Run.callable

            override val chatKind: ChatKind?
                get() = delegation?.parent?.chatKind ?: plan.input.message?.chatInfo?.kind

            override val overBudget: Boolean get() = this@Run.overBudget()

            override val vouched: Boolean get() = this@Run.vouched(turn)

            override val chain: RunChain? get() = delegation?.chain

            override fun starting(call: ToolCallId) {
                started += call
            }

            override fun followedUp(): Boolean = delegation?.parent?.followedUp()
                ?: (principal?.let { followUps.queued(it, plan.origin, startedAt) } == true)

            override fun ownWords(): List<String> = delegation?.parent?.ownWords() ?: words.toList()

            override fun history(): List<TranscriptEntry> = delegation?.parent?.history() ?: history

            override suspend fun running(call: ToolCallId) = emitToolCallUpdated(call, ToolCallStatus.RUNNING)

            override suspend fun waitingApproval(call: ToolCallId) =
                emitToolCallUpdated(call, ToolCallStatus.WAITING_APPROVAL)

            override suspend fun ended(call: ToolCallId, status: ToolCallStatus, content: TurnEvent.Content?) =
                emitToolCallUpdated(call, status, content)

            override suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T): T? =
                asking {
                    waiting[call] = request
                    try {
                        ask()
                    } finally {
                        if (currentCoroutineContext().isActive) waiting -= call
                    }
                }

            override suspend fun <T : Any> asking(ask: suspend () -> T): T? {
                delegation?.let { return it.parent.asking(ask) }
                return asks.withLock {
                    if (followedUp()) return@withLock null
                    val writer = principal
                    permit.released { if (writer == null) ask() else answerOrFollowUp(writer, ask) }
                }
            }

            override suspend fun show(event: TurnEvent) {
                val kind = when (event) {
                    is TurnEvent.ToolCallStarted -> TurnEventKind.TOOL_CALL_STARTED
                    is TurnEvent.ToolCallUpdated -> TurnEventKind.TOOL_CALL_UPDATED
                    else -> return
                }
                emit(kind) { event }
            }

            override suspend fun detachActivity(run: DelegatedRun): ReplyActivity? {
                var activity: ReplyActivity? = null
                attempt("retain a delegated activity") {
                    activity = delegation?.let { runReply?.detachActivity(run) ?: it.parent.detachActivity(run) }
                        ?: sink?.detachActivity(run)
                }
                return activity
            }

            override fun spend(tokens: Long) = this@Run.spend(tokens)

            override fun deniedInTree(call: String): Boolean =
                delegation?.parent?.deniedInTree(call) ?: (call in denials)

            override fun denied(call: String) {
                val parent = delegation?.parent
                if (parent != null) parent.denied(call) else denials += call
            }

            /** What [ask] returns, or null once [principal] queued a follow-up first. */
            private suspend fun <T : Any> answerOrFollowUp(principal: UserAddress, ask: suspend () -> T): T? =
                coroutineScope {
                    val answer = async { ask() }
                    val written = async { followUps.await(principal, plan.origin, startedAt) }
                    select<T?> {
                        answer.onAwait {
                            written.cancel()
                            it
                        }
                        written.onAwait {
                            answer.cancel()
                            null
                        }
                    }
                }
        }

        /** Shows that the model's call [id] of [tool], of kind [activity], started, titled [title]. */
        private suspend fun emitToolCallStarted(id: ToolCallId, tool: String, activity: ToolActivity, title: String) {
            emit(TurnEventKind.TOOL_CALL_STARTED) {
                TurnEvent.ToolCallStarted(id, tool, activity, title, delegation?.run, delegation?.profile?.id)
            }
        }

        private suspend fun emitToolCallUpdated(
            id: ToolCallId,
            status: ToolCallStatus,
            content: TurnEvent.Content? = null,
        ) {
            emit(TurnEventKind.TOOL_CALL_UPDATED) { TurnEvent.ToolCallUpdated(id, status, content, delegation?.run) }
        }

        private suspend fun emitThoughtChunk(text: String?) {
            if (text.isNullOrEmpty()) return
            emit(TurnEventKind.THOUGHT_CHUNK) { TurnEvent.ThoughtChunk(text) }
        }

        private suspend fun emitUsageUpdated(usage: Usage?, window: Int?) {
            emit(TurnEventKind.USAGE_UPDATED) {
                TurnEvent.UsageUpdated(usage?.contextTokens, usage?.outputTokens, window)
            }
        }

        /**
         * Shows [build]'s event through the sink, or the reply of the run the turn is, where the chat's capabilities ask
         * for [kind], or else the tool-call events of a sub-agent run through the turn that delegated it.
         */
        private suspend fun emit(kind: TurnEventKind, build: () -> TurnEvent) {
            val runReply = runReply
            if (runReply != null) {
                if (capabilities?.events?.contains(kind) ==
                    true
                ) {
                    attempt("show a turn event") { runReply.event(build()) }
                }
                return
            }
            val parent = delegation?.parent
            if (parent != null) {
                if (kind in FORWARDED) attempt("show a turn event") { parent.show(build()) }
                return
            }
            val sink = sink ?: return
            if (capabilities?.events?.contains(kind) != true) return
            attempt("show a turn event") { sink.event(build()) }
        }

        private suspend fun attempt(what: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                currentCoroutineContext().ensureActive()
                logger.warn("Turn {} of {} could not {}: {}", plan.id, plan.key, what, e.toString())
            }
        }
    }
}

/** What every request of one turn shares. */
private class RequestParts(
    val model: SelectedModel,
    val options: ModelOptions,
    val window: Compactor.TurnWindow,
    val sections: List<PromptSection>,
    val tools: List<ToolDefinition>,
    val context: TurnContextPlan,
)

/** The tools that a turn's calls may name, by name, and the names of those it may call. */
private class TurnTools(val offered: Map<String, Tool>, val callable: Set<String>)

/** The message that the turn answers, null for a turn that a plugin started. */
private val TurnSource.message: IncomingMessage?
    get() = (this as? TurnSource.FromMessage)?.message

/** What the model reads of the turn's input before `turn.input` changes it. */
private val TurnSource.text: String
    get() = when (this) {
        is TurnSource.FromAgentMessage -> message.text
        is TurnSource.FromMessage -> message.text
        is TurnSource.FromInitiated -> prompt
        is TurnSource.FromJob -> job.text
        is TurnSource.FromDelegation -> delegation.task
    }

/** The words that the sender of [message] wrote themselves, null when it holds none of theirs. */
private fun wordsOf(message: IncomingMessage): String? =
    message.text.takeIf { message.forwarded == null && it.isNotBlank() }

/** The tool calls of [entries] that none of them answers. */
internal fun openCalls(entries: List<TranscriptEntry>): List<ToolCallPart> {
    val answered = entries.filterIsInstance<ToolResultEntry>().map { it.callId }.toSet()
    return entries.filterIsInstance<AssistantEntry>()
        .flatMap { it.parts.filterIsInstance<ToolCallPart>() }
        .filter { it.id !in answered }
}

/** The input and output tokens that [usage] counts. */
private fun spentOn(usage: Usage): Long = (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)

/**
 * The response that makes [call] for the turn's operator in place of [model]'s first, which the model then reads as
 * its own, as if it had made the call that the operator asked for.
 */
private fun operatorResponse(call: ToolCallPart, model: ModelRef): ModelEvent.Completed = ModelEvent.Completed(
    AssistantEntry(null, listOf(call), model),
    FinishReason(FinishKind.TOOL_USE, null, "a call the operator asked for"),
    Usage.builder().build(),
)

/** The pauses after which a turn goes on. */
private const val MAX_PAUSES = 3

/** The events of a sub-agent run that the turn above it shows. */
private val FORWARDED = setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED)

/** How a reply ends without a final message when its turn ended as [end]. */
private fun replyEnd(end: TurnEndKind): ReplyEnd = when (end) {
    TurnEndKind.CANCELLED -> ReplyEnd.CANCELLED
    TurnEndKind.FAILED -> ReplyEnd.FAILED
    TurnEndKind.SHUT_DOWN -> ReplyEnd.SHUTDOWN
    else -> ReplyEnd.COMPLETED
}

/** The tool calls of one response that run at once. */
private const val MAX_PARALLEL_CALLS = 4

/** The untrusted folders that one notice names. */
private const val MAX_NAMED_FOLDERS = 10

private val logger = LoggerFactory.getLogger(AgentTurnRunner::class.java)

/** The failures after which nothing more reaches a chat. */
private val UNREACHABLE = setOf(DeliveryFailure.FORBIDDEN, DeliveryFailure.CHAT_GONE)

/** Why a turn moved on from a model that its endpoint does not list. */
private const val UNKNOWN_MODEL = "unknown"

/** What selecting a turn's model came to. */
private sealed interface Selection {
    class Found(val model: SelectedModel) : Selection

    class Failed(val error: ModelError) : Selection

    data object Unknown : Selection
}

/** The kinds of turn that may end saying nothing: a job's that has nothing to add, a heartbeat's. */
private val SILENT_KINDS = setOf(TurnKind.JOB, TurnKind.HEARTBEAT)
