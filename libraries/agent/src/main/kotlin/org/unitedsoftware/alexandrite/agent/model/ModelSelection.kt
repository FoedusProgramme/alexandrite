package org.unitedsoftware.alexandrite.agent.model

import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
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
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ProviderData
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart

/** The model a turn calls, as its endpoint lists it. */
internal class SelectedModel(val ref: ModelRef, val endpoint: ModelEndpoint, val info: ModelInfo)

/** The model [ref] names, null when no endpoint lists it. */
internal suspend fun Endpoints.select(ref: ModelRef): SelectedModel? {
    val endpoint = endpoint(ref.endpoint) ?: return null
    return model(ref)?.let { SelectedModel(ref, endpoint, it) }
}

/** The model [ref] names, throwing a [ModelException] of [ModelErrorKind.MODEL_NOT_FOUND] when no endpoint lists it. */
internal suspend fun Endpoints.listed(ref: ModelRef): SelectedModel = select(ref) ?: throw ModelException(
    ModelError.builder(ModelErrorKind.MODEL_NOT_FOUND, "No endpoint lists the model $ref.").retryable(false).build(),
)

/** The [text] that a side model answered and what its call took. */
internal class SideAnswer(val text: String, val usage: Usage)

/**
 * What this model answers to [input], written by [source], under [instructions] with its least reasoning in at most
 * [maxTokens] tokens; [call] wraps the call, such as in retries.
 */
internal suspend fun SelectedModel.ask(
    ids: RequestIds,
    source: String,
    input: String,
    instructions: List<PromptSection>,
    maxTokens: Int,
    temperature: Double? = null,
    call: suspend (suspend () -> ModelEvent.Completed) -> ModelEvent.Completed = { it() },
): SideAnswer = ask(ids, source, listOf(TextPart(input)), instructions, maxTokens, temperature, call)

/** As the other [ask], with its single text input replaced by [parts], such as to carry media inline. */
internal suspend fun SelectedModel.ask(
    ids: RequestIds,
    source: String,
    parts: List<UserPart>,
    instructions: List<PromptSection>,
    maxTokens: Int,
    temperature: Double? = null,
    call: suspend (suspend () -> ModelEvent.Completed) -> ModelEvent.Completed = { it() },
): SideAnswer {
    val options = ModelOptions.builder()
        .maxOutputTokens(minOf(maxTokens, info.maxOutputTokens ?: maxTokens))
        .reasoning(leastReasoning(info))
        .temperature(temperature)
        .build()
    val history = listOf(UserEntry(null, parts, UserOrigin.SystemWritten(source)))
    val request = ModelRequest.builder(ref, history, 0, ids).instructions(instructions).options(options).build()
    val response = call { collectResponse(endpoint, request) }
    return SideAnswer(response.message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }, response.usage)
}

/** The options of [agent]'s requests to [model] with the effort [reasoning]. */
internal fun requestOptions(agent: Agent, model: ModelInfo, reasoning: ReasoningEffort?): ModelOptions =
    ModelOptions.builder()
        .maxOutputTokens(outputReserve(agent, model))
        .temperature(agent.config.temperature)
        .reasoning(reasoning)
        .parallelToolCalls(model.parallelToolCalls)
        .build()

/**
 * The tokens [agent]'s requests to [model] leave for the answer: its config's, else the model's most but at most
 * [DEFAULT_MAX_OUTPUT_TOKENS], since a provider counts them against the context window; null when neither says.
 */
internal fun outputReserve(agent: Agent, model: ModelInfo): Int? =
    agent.config.maxOutputTokens ?: model.maxOutputTokens?.coerceAtMost(DEFAULT_MAX_OUTPUT_TOKENS)

/**
 * The [options] of a retry to [model] after its output limit cut off a response before its answer: the model's least
 * reasoning, and twice the answer tokens where the model takes that many, else its most.
 */
internal fun roomier(options: ModelOptions, model: ModelInfo): ModelOptions {
    val room = options.maxOutputTokens
    val most = model.maxOutputTokens
    val more = if (room == null || most == null) room else maxOf(room, minOf(room.toLong() * 2, most.toLong()).toInt())
    return options.toBuilder().maxOutputTokens(more).reasoning(leastReasoning(model)).build()
}

/**
 * Whether a turn moves on to its next model after [error]: an unreachable or failing backend, a rate limit that its
 * retries did not outlast, or a model the endpoint does not know.
 */
internal fun movesOn(error: ModelError): Boolean = error.kind in MOVING_ON || error.status in 500..599

private val MOVING_ON = setOf(
    ModelErrorKind.CONNECTION,
    ModelErrorKind.TIMEOUT,
    ModelErrorKind.SERVER_ERROR,
    ModelErrorKind.OVERLOADED,
    ModelErrorKind.RATE_LIMITED,
    ModelErrorKind.MODEL_NOT_FOUND,
)

/** [entries] as a request to [model] carries them, without the reasoning seals and provider data of other endpoints. */
internal fun crossedTo(model: ModelRef, entries: List<TranscriptEntry>): List<TranscriptEntry> = entries.map { entry ->
    if (entry !is AssistantEntry) return@map entry
    val own = entry.producedBy.endpoint == model.endpoint
    val parts = entry.parts.mapNotNull { part ->
        when {
            part is ReasoningPart && part.seal != null && part.seal?.origin?.endpoint != model.endpoint ->
                ReasoningPart(part.text, part.summary, null).takeIf { it.text != null || it.summary != null }

            part is ToolCallPart && !own && part.providerData != ProviderData.EMPTY ->
                ToolCallPart(part.id, part.name, part.arguments)

            else -> part
        }
    }
    if (own && parts == entry.parts) entry else AssistantEntry(entry.record, parts, entry.producedBy)
}

/** The model of [models] after [current], round to the first, else their first when [current] is none of them. */
internal fun nextModel(models: List<ModelRef>, current: ModelRef): ModelRef? {
    val at = models.indexOf(current)
    val next = if (at < 0) models.firstOrNull() else models[(at + 1) % models.size]
    return next?.takeIf { it != current }
}

/**
 * The least reasoning [model] takes, for a side request whose short answer a reasoning model could spend all its
 * tokens before writing: none where the model may skip it, else its lowest effort, null when it takes no effort.
 */
internal fun leastReasoning(model: ModelInfo): ReasoningEffort? = EFFORTS.firstOrNull { it in model.reasoningEfforts }

private val EFFORTS = listOf(
    ReasoningEffort.NONE,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

/** The answer tokens a request leaves unless the agent's config says otherwise. */
internal const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 32_768

/** The prompt cache key of the conversations at [key]: the first 32 hex digits of the SHA-256 of the printed key. */
internal fun cacheKey(key: AgentChatKey): String = sha256Hex(key.toString()).take(CACHE_KEY_LENGTH)

private const val CACHE_KEY_LENGTH = 32
