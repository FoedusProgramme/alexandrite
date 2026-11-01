package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.context.Compactor
import org.unitedsoftware.alexandrite.agent.context.TokenEstimator
import org.unitedsoftware.alexandrite.agent.context.contextView
import org.unitedsoftware.alexandrite.agent.control.ControlRunner
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.control.effectiveZone
import org.unitedsoftware.alexandrite.agent.control.existing
import org.unitedsoftware.alexandrite.agent.directory.Inbox
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.media.MediaReader
import org.unitedsoftware.alexandrite.agent.media.MediaRouting
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.model.RetryPolicy
import org.unitedsoftware.alexandrite.agent.model.collectResponse
import org.unitedsoftware.alexandrite.agent.model.listed
import org.unitedsoftware.alexandrite.agent.model.requestOptions
import org.unitedsoftware.alexandrite.agent.prompt.PromptAssembler
import org.unitedsoftware.alexandrite.agent.prompt.PromptTurn
import org.unitedsoftware.alexandrite.agent.prompt.TurnContextPlan
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.agent.tool.definitionsFor
import org.unitedsoftware.alexandrite.agent.usage.Budgets
import org.unitedsoftware.alexandrite.agent.usage.UsageMeter
import org.unitedsoftware.alexandrite.agent.worker.newTurnId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.SideQuestions
import java.time.Clock

/**
 * Asks in the request that the next message turn of a chat would start with: its model, options, tools, prompt
 * sections after the `prompt.sections` hook, and history, the question in place of the message.
 */
@Singleton
@Binds(SideQuestions::class)
internal class SideQuestionsImpl(
    private val agentSettings: AgentSettings,
    private val runner: ControlRunner,
    private val directory: AgentDirectory,
    private val settings: SettingsStates,
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
    private val endpoints: Endpoints,
    private val retries: RetryPolicy,
    private val compactor: Compactor,
    private val prompt: PromptAssembler,
    private val hooks: TurnHooks,
    private val tools: ToolRegistry,
    private val routing: MediaRouting,
    private val mediaStore: MediaStore,
    private val mediaReader: MediaReader,
    private val imageCodec: ImageCodec?,
    private val inbox: Inbox,
    private val budgets: Budgets,
    private val clock: Clock,
) : SideQuestions {
    override suspend fun ask(chat: ChatAddress, question: String, by: ChatUser?): String? {
        require(question.isNotBlank()) { "A side question is not blank." }
        val key = runner.key(chat)
        val conversation = conversations.existing(key) ?: return null
        val agent = checkNotNull(directory.agent(key.agent)) { "No agent '${key.agent}' is configured." }
        if (budgets.exhausted(agent.id)) {
            runner.refusing(key) { throw Refused(TextKeys.BUDGET_REFUSED, "agent" to agent.name) }
        }
        val model = settings.model(key) ?: checkNotNull(agent.model) { "Agent '${agent.id}' has no model." }
        val selected = endpoints.listed(model)
        val turn = TurnInfo.builder(newTurnId(clock.instant()), chat, conversation, TurnKind.MESSAGE)
            .actor(by)
            .language(settings.language(key) ?: agent.language)
            .zone(settings.effectiveZone(key, agent, clock))
            .agent(key.agent)
            .key(key)
            .build()
        val definitions = tools.offered(agent.id, model, selected.info.nativeTools).definitionsFor(turn)
        val offered = definitions.mapTo(HashSet()) { it.name }
        val sections = hooks.promptSections(turn, prompt.sections(PromptTurn(turn, offered, selected.info), null))
        val view = contextView(settled(transcripts.entries(conversation)))
        val summaryModel = agent.summaryModel ?: model
        val window = compactor.open(turn, agent.config.compaction, model, selected.info, summaryModel, view)
        val options = requestOptions(agent, selected.info, settings.reasoning(key) ?: agent.reasoning)
        val parts = RequestParts(selected, options, window, sections, definitions, NO_CONTEXT)
        val media = TurnMedia(turn.id, mediaStore, agentSettings.maxMediaBytes, inbox, mediaReader, imageCodec)
        val plan = routing.plan(agent.id, selected.info)
        val asked = listOf(UserEntry(null, listOf(TextPart(question)), UserOrigin.SystemWritten(SOURCE)))
        val meter = UsageMeter(budgets).also { it.turn = turn }
        return withContext(meter) {
            val first = parts.request(turn, asked, 0, options, toolless = false, media, plan)
            answer(turn, parts, first) ?: run {
                logger.info("A side question at {} is asked again without tools: {} called one", key, model)
                answer(turn, parts, parts.request(turn, asked, 1, options, toolless = true, media, plan)).orEmpty()
            }
        }.also { logger.info("A side question at {} is answered by {}", key, model) }
    }

    /** The text that the model of [parts] answers [request] with, null when it only called tools. */
    private suspend fun answer(turn: TurnInfo, parts: RequestParts, request: ModelRequest): String? {
        val response = retries.retrying(turn.id, TokenEstimator.request(request)) {
            collectResponse(parts.model.endpoint, request)
        }
        val answer = response.message.parts
        val text = answer.filterIsInstance<TextPart>().joinToString("") { it.text }
        return text.takeUnless { it.isBlank() && answer.any { part -> part is ToolCallPart } }
    }
}

/** [entries] up to the first response whose tool calls are not all answered, as a running turn may leave them. */
internal fun settled(entries: List<TranscriptEntry>): List<TranscriptEntry> {
    val open = openCalls(entries).mapTo(HashSet()) { it.id }
    val first = entries.indexOfFirst { entry ->
        entry is AssistantEntry && entry.parts.any { it is ToolCallPart && it.id in open }
    }
    return if (first < 0) entries else entries.subList(0, first)
}

private const val SOURCE = "side-question"

private val NO_CONTEXT = TurnContextPlan(emptyList(), emptyList(), false)

private val logger = LoggerFactory.getLogger(SideQuestionsImpl::class.java)
