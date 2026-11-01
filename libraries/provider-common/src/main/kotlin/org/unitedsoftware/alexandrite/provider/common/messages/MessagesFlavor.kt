package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.MESSAGES_CALL_IDS
import org.unitedsoftware.alexandrite.provider.common.ModelFacts
import org.unitedsoftware.alexandrite.provider.common.ModelListing
import org.unitedsoftware.alexandrite.provider.common.TurnContextSetting
import org.unitedsoftware.alexandrite.provider.common.boolean
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.positiveInt
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.provider.common.unsupportedEffort
import org.unitedsoftware.alexandrite.provider.common.wireToolName
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.model.WarningKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import java.net.URLEncoder

/**
 * How a family of backends speaks the Messages API: its wire dialect and, one part per concern, what it does its own
 * way. This library may change between releases more freely than the SDK.
 */
public class MessagesFlavor(
    /** The wire dialect of the flavor's models, reasoning seals and provider data. */
    public val dialect: Dialect,
    /** How the backend lists its models. */
    public val listing: ModelListing = MessagesListing(),
    /** How a request makes a model think and gives the model its thinking back. */
    public val reasoning: MessagesReasoning = MessagesReasoning(),
    /** How a request offers tools and asks for a tool choice. */
    public val tools: MessagesTools = MessagesTools(),
    /** How a request marks what the backend caches, and how the backend counts the tokens of a request. */
    public val caching: MessagesCaching = MessagesCaching(),
    /** Where a request carries turn context. */
    public val turnContext: MessagesTurnContext = MessagesTurnContext(),
    /** How the backend says why a response ended. */
    public val finishReasons: MessagesFinishReasons = MessagesFinishReasons(),
    /** How the backend names its failures. */
    public val errors: MessagesErrors = MessagesErrors(),
    /** The `anthropic-version` header of every request. */
    public val version: String = "2023-06-01",
) {
    public companion object {
        /** The dialect of the standard API. */
        public val ANTHROPIC: Dialect = Dialect("anthropic")

        /** The standard API. */
        public val STANDARD: MessagesFlavor = MessagesFlavor(ANTHROPIC)
    }
}

/** The standard Models API: `/v1/models`, paged by `after_id`, each model with its token limits and capabilities. */
public open class MessagesListing : ModelListing() {
    override fun urls(baseUrl: String): List<String> = listOf("$baseUrl/v1/models")

    override fun next(page: JsonObject): String? {
        if (page.boolean("has_more") != true) return null
        val last = page.string("last_id") ?: return null
        return "models?after_id=${URLEncoder.encode(last, "UTF-8")}"
    }

    override fun facts(model: JsonObject): ModelFacts {
        val capabilities = model.obj("capabilities")
        return ModelFacts(
            id = model.string("id"),
            displayName = model.string("display_name"),
            contextWindow = model.positiveInt("max_input_tokens"),
            maxOutputTokens = model.positiveInt("max_tokens"),
            inputMedia = capabilities?.let { if (it.supports("image_input")) setOf(MediaKind.IMAGE) else emptySet() },
            reasoningEfforts = capabilities?.let(::efforts),
        )
    }

    /** NONE where a request may turn thinking off, and each effort of adaptive thinking that the model takes. */
    private fun efforts(capabilities: JsonObject): Set<ReasoningEffort> {
        val thinking = capabilities.obj("thinking")
        val types = thinking?.obj("types")
        val efforts = mutableSetOf<ReasoningEffort>()
        if (types?.supports("disabled") == true) efforts += ReasoningEffort.NONE
        val effort = capabilities.obj("effort")
        if (effort != null && effort.boolean("supported") == true && thinking?.boolean("supported") == true &&
            types?.supports("adaptive") == true
        ) {
            LEVELS.filterTo(efforts) { effort.supports(it.id) }
        }
        return efforts
    }

    private fun JsonObject.supports(name: String): Boolean = obj(name)?.boolean("supported") == true

    private companion object {
        val LEVELS = listOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
            ReasoningEffort.MAX,
        )
    }
}

/**
 * How a request makes a model think and gives the model its thinking back. The standard API thinks adaptively with an
 * `output_config.effort`, turns thinking off with `thinking.type` `disabled`, and takes each thinking block back
 * unchanged from the model that wrote it.
 */
public open class MessagesReasoning {
    /** Adds the fields that ask [info]'s model to think with [effort] to [body], or returns why it adds none. */
    public open fun request(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
        if (effort !in info.reasoningEfforts) return unsupported(effort, info)
        if (effort == ReasoningEffort.NONE) {
            body.putJsonObject("thinking") { put("type", "disabled") }
            return null
        }
        body.putJsonObject("thinking") {
            put("type", "adaptive")
            put("display", "summarized")
        }
        body.putJsonObject("output_config") { put("effort", effort.id) }
        return null
    }

    /** Whether [info]'s model may think on a request that sends [effort], null when it sends none. */
    public open fun thinks(effort: ReasoningEffort?, info: ModelInfo): Boolean = when {
        info.reasoningEfforts.isEmpty() -> false
        effort == ReasoningEffort.NONE -> ReasoningEffort.NONE !in info.reasoningEfforts
        else -> true
    }

    /** The seal of a complete thinking [block] that [origin] wrote in [dialect], null when it needs none of it back. */
    public open fun seal(block: JsonObject, origin: ModelRef, dialect: Dialect): ReasoningSeal? =
        when (block.string("type")) {
            "thinking" -> block.string("signature")?.takeIf { it.isNotEmpty() }?.let {
                ReasoningSeal(origin, dialect, SealKind.SIGNATURE, it)
            }

            "redacted_thinking" -> block.string("data")?.let { ReasoningSeal(origin, dialect, SealKind.ENCRYPTED, it) }

            else -> null
        }

    /** The block that gives [part] back to [request]'s model, null when that model gets none of it. */
    public open fun replay(part: ReasoningPart, request: ModelRequest, dialect: Dialect): JsonObject? {
        val seal = part.seal?.takeIf { it.dialect == dialect && it.origin == request.model } ?: return null
        return when (seal.kind) {
            SealKind.SIGNATURE -> buildJsonObject {
                put("type", "thinking")
                put("thinking", part.text.orEmpty())
                put("signature", seal.data)
            }

            SealKind.ENCRYPTED -> buildJsonObject {
                put("type", "redacted_thinking")
                put("data", seal.data)
            }

            else -> null
        }
    }

    /** The output tokens that were thinking, as the `usage` object [usage] counts them, null when it does not. */
    public open fun reasoningTokens(usage: JsonObject): Long? =
        usage.obj("output_tokens_details")?.long("thinking_tokens")

    /** The warning that [info]'s model does not accept [effort], so the request leaves the effort out. */
    protected fun unsupported(effort: ReasoningEffort, info: ModelInfo): Warning = unsupportedEffort(effort, info)
}

/**
 * How a request offers tools and asks for a tool choice. The standard API forces a tool call only with thinking off:
 * forced use fails with manual extended thinking, and several models reject it whenever they think.
 */
public open class MessagesTools {
    /** The effort that [request] goes out with: its own, or NONE when it forces a call and leaves the effort open. */
    public open fun effort(request: ModelRequest, info: ModelInfo): ReasoningEffort? {
        val effort = request.options.reasoning
        val forced = request.toolChoice == ToolChoice.Required || request.toolChoice is ToolChoice.Named
        val canStop = ReasoningEffort.NONE in info.reasoningEfforts
        return if (forced && effort == null && canStop && request.tools.isNotEmpty()) ReasoningEffort.NONE else effort
    }

    /** Adds the `tool_choice` of [request] to [body], as [info]'s model [thinks] or not, and returns what it drops. */
    public open fun toolChoice(
        request: ModelRequest,
        info: ModelInfo,
        thinks: Boolean,
        body: JsonObjectBuilder,
    ): List<Warning> {
        val warnings = mutableListOf<Warning>()
        val parallel = request.options.parallelToolCalls
        if (parallel == true && !info.parallelToolCalls) {
            warnings += Warning(WarningKind.UNSUPPORTED_OPTION, "Model '${info.id}' calls no tools in parallel.")
        }
        var choice = request.toolChoice
        if ((choice == ToolChoice.Required || choice is ToolChoice.Named) && thinks) {
            warnings += Warning(
                WarningKind.UNSUPPORTED_OPTION,
                "Model '${info.id}' takes no forced tool choice while it may think, so the model decides.",
            )
            choice = ToolChoice.Auto
        }
        val type = when (choice) {
            ToolChoice.Auto -> "auto"

            ToolChoice.None -> "none"

            ToolChoice.Required -> "any"

            is ToolChoice.Named -> "tool"

            else -> {
                warnings +=
                    Warning(WarningKind.UNSUPPORTED_OPTION, "The tool choice $choice is unknown, so the model decides.")
                "auto"
            }
        }
        val one = parallel == false && type != "none"
        if (type == "auto" && !one) return warnings
        body.putJsonObject("tool_choice") {
            put("type", type)
            if (choice is ToolChoice.Named) put("name", wireName(choice.name))
            if (one) put("disable_parallel_tool_use", true)
        }
        return warnings
    }

    /** The tool that a call naming [wire] calls, given the [offered] tools: the inverse of [wireName]. */
    public open fun callName(wire: String, offered: List<ToolDefinition>): String =
        offered.firstOrNull { wireName(it.name) == wire }?.name ?: ToolNames.fromWire(wire) ?: wire

    /** The name that the tool [name] and its stored calls go out with, one for each name. */
    public open fun wireName(name: String): String = wireToolName(name)

    /** The id that the stored call [id] and its result go out with, one for each id. */
    public open fun wireCallId(id: ToolCallId): String = MESSAGES_CALL_IDS.of(id.value)
}

/**
 * How a request marks what the backend caches, and how the backend counts the tokens of a request. The standard API
 * counts in `input_tokens` only what it read neither from the cache nor into it.
 */
public open class MessagesCaching {
    /** The `cache_control` of a breakpoint, null when the backend takes none. */
    public open val breakpoint: JsonObject? = buildJsonObject { put("type", "ephemeral") }

    /** The prompt tokens read from the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheReadTokens(usage: JsonObject): Long? = usage.long("cache_read_input_tokens")

    /** The prompt tokens written to the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheWriteTokens(usage: JsonObject): Long? = usage.long("cache_creation_input_tokens")

    /** All prompt tokens of the request, cached ones included, as [usage] counts them, null when it does not. */
    public open fun promptTokens(usage: JsonObject): Long? {
        val uncached = usage.long("input_tokens") ?: return null
        return uncached + (cacheReadTokens(usage) ?: 0) + (cacheWriteTokens(usage) ?: 0)
    }
}

/**
 * Where a request carries turn context. On an endpoint whose models take turn-scoped system messages, the standard API
 * puts trusted text into one after the last user message of each request, which later requests repeat unchanged.
 * Elsewhere it puts the text after everything persisted, or, while the model may think, at the end of the user message
 * that opened the turn in every request of the turn, where its thinking binds it.
 */
public open class MessagesTurnContext {
    /** The beta that a request holding turn-scoped system messages asks for, null when it needs none. */
    public open val beta: String? = "mid-conversation-system-clear-at-2026-08-21"

    /**
     * Whether a model may bind its thinking to everything sent before it, so that an endpoint keeping no turn context
     * sends the thinking of the current turn alone.
     */
    public open val prefixBound: Boolean = true

    /**
     * How a request renders turn context of [trust], given the endpoint's [setting], whether its models take
     * turn-scoped system messages ([turnScoped]) and whether the model may think on the request ([thinks]).
     */
    public open fun mode(
        trust: Trust,
        setting: TurnContextSetting,
        turnScoped: Boolean,
        thinks: Boolean,
    ): TurnContextMode = when {
        setting == TurnContextSetting.BAKE -> TurnContextMode.NOT_SUPPORTED
        turnScoped && trust == Trust.TRUSTED -> TurnContextMode.KEPT_UNRENDERED
        turnScoped && thinks -> TurnContextMode.NOT_SUPPORTED
        else -> TurnContextMode.TRANSIENT
    }

    /** The turn-scoped system message that carries [text]. */
    public open fun message(text: String): JsonObject = buildJsonObject {
        put("role", "system")
        put("clear_at", "next_user_message")
        put("content", text)
    }
}

/** How the backend says why a response ended. */
public open class MessagesFinishReasons {
    /** The kind of an end for the `stop_reason` [raw]. */
    public open fun kind(raw: String): FinishKind = when (raw) {
        "end_turn" -> FinishKind.END_TURN
        "tool_use" -> FinishKind.TOOL_USE
        "max_tokens" -> FinishKind.MAX_OUTPUT_TOKENS
        "stop_sequence" -> FinishKind.STOP_SEQUENCE
        "pause_turn" -> FinishKind.PAUSED
        "refusal" -> FinishKind.REFUSAL
        "model_context_window_exceeded" -> FinishKind.CONTEXT_WINDOW_EXCEEDED
        else -> FinishKind.OTHER
    }

    /** What the `message_delta` [delta] says besides its `stop_reason`: the stop sequence or the refusal's reason. */
    public open fun detail(delta: JsonObject): String? = delta.string("stop_sequence")
        ?: delta.obj("stop_details")?.let { it.string("explanation") ?: it.string("category") }

    /** The kind of failure that an end for the reason [raw] is, null when it is none. */
    public open fun failure(raw: String): ModelErrorKind? = null
}

/** How the backend names its failures. */
public open class MessagesErrors {
    /** The kind of a failure of [type] that says [message], null when the HTTP status decides. */
    public open fun kind(type: String?, message: String?): ModelErrorKind? = when (type) {
        "invalid_request_error" ->
            if (message?.contains("prompt is too long", ignoreCase = true) == true) {
                ModelErrorKind.CONTEXT_WINDOW_EXCEEDED
            } else {
                ModelErrorKind.INVALID_REQUEST
            }

        "authentication_error" -> ModelErrorKind.AUTHENTICATION

        "permission_error" -> ModelErrorKind.PERMISSION_DENIED

        "billing_error" -> ModelErrorKind.QUOTA_EXHAUSTED

        "not_found_error" -> ModelErrorKind.MODEL_NOT_FOUND

        "rate_limit_error" -> ModelErrorKind.RATE_LIMITED

        "api_error" -> ModelErrorKind.SERVER_ERROR

        "timeout_error" -> ModelErrorKind.TIMEOUT

        "overloaded_error" -> ModelErrorKind.OVERLOADED

        else -> null
    }
}
