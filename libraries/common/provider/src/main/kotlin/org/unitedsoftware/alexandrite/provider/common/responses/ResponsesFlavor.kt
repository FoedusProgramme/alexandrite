package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.provider.common.ModelListing
import org.unitedsoftware.alexandrite.provider.common.RESPONSES_CALL_IDS
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.putToolChoice
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.provider.common.unsupportedEffort
import org.unitedsoftware.alexandrite.provider.common.wireToolName
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ProviderData
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind

/**
 * How a family of backends speaks the Responses API: its wire dialect and, one part per concern, what it does its own
 * way. This library may change between releases more freely than the SDK.
 */
public class ResponsesFlavor(
    /** The wire dialect of the flavor's models, reasoning seals and opaque output items. */
    public val dialect: Dialect,
    public val listing: ModelListing = ModelListing(),
    public val reasoning: ResponsesReasoning = ResponsesReasoning(),
    public val tools: ResponsesTools = ResponsesTools(),
    public val caching: ResponsesCaching = ResponsesCaching(),
    public val finishReasons: ResponsesFinishReasons = ResponsesFinishReasons(),
    public val errors: ResponsesErrors = ResponsesErrors(),
) {
    public companion object {
        /** The dialect of the standard API. */
        public val OPENAI_RESPONSES: Dialect = Dialect("openai-responses")

        /** The standard API. */
        public val STANDARD: ResponsesFlavor = ResponsesFlavor(OPENAI_RESPONSES)
    }
}

/**
 * How a request asks a model to reason and gives the model its reasoning back. The standard API keeps no state between
 * requests, so each reasoning item goes back with its `encrypted_content`, unchanged, to the model that wrote it.
 */
public open class ResponsesReasoning {
    /** Whether [info]'s model reasons. */
    public open fun reasons(info: ModelInfo): Boolean = info.reasoningEfforts.isNotEmpty()

    /** Adds the effort that asks [info]'s model to reason with [effort] to [reasoning], or returns why it adds none. */
    public open fun effort(effort: ReasoningEffort, info: ModelInfo, reasoning: JsonObjectBuilder): Warning? {
        if (effort !in info.reasoningEfforts) return unsupportedEffort(effort, info)
        reasoning.put("effort", effort.id)
        return null
    }

    /** Whether [info]'s model may reason on a request that sends [effort], null when it sends none. */
    public open fun thinks(effort: ReasoningEffort?, info: ModelInfo): Boolean =
        reasons(info) && effort != ReasoningEffort.NONE

    /** The seal of a complete reasoning [item] that [origin] wrote in [dialect], null when it needs none of it back. */
    public open fun seal(item: JsonObject, origin: ModelRef, dialect: Dialect): ReasoningSeal? {
        val encrypted = item.string("encrypted_content")?.takeIf { it.isNotEmpty() } ?: return null
        val kept = JsonObject(item.filterKeys { it in KEPT })
        return ReasoningSeal(origin, dialect, SealKind.ENCRYPTED, encrypted, ProviderData.EMPTY.with(dialect, kept))
    }

    /** The reasoning item that gives [part] back to [request]'s model, null when that model gets none of it. */
    public open fun replay(part: ReasoningPart, request: ModelRequest, dialect: Dialect): JsonObject? {
        val seal = part.seal?.takeIf { it.dialect == dialect && it.origin == request.model } ?: return null
        if (seal.kind != SealKind.ENCRYPTED) return null
        val kept = seal.providerData.entries[dialect].orEmpty()
        return buildJsonObject {
            put("type", "reasoning")
            put("summary", kept["summary"] as? JsonArray ?: JsonArray(emptyList()))
            (kept["content"] as? JsonArray)?.let { put("content", it) }
            put("encrypted_content", seal.data)
        }
    }

    /** The output tokens that were reasoning, as the `usage` object [usage] counts them, null when it does not. */
    public open fun reasoningTokens(usage: JsonObject): Long? =
        usage.obj("output_tokens_details")?.long("reasoning_tokens")

    private companion object {
        /** The fields of a reasoning item besides its encrypted content that go back with it. */
        val KEPT = setOf("summary", "content")
    }
}

/** How a request offers tools and asks for a tool choice. */
public open class ResponsesTools {
    /** The function tool that offers [tool]. */
    public open fun definition(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", wireName(tool.name))
        put("description", tool.description)
        put("parameters", tool.parameters)
        put("strict", false)
    }

    /** Adds the field that asks for [request]'s tool choice to [body], or returns why it adds none. */
    public open fun toolChoice(request: ModelRequest, body: JsonObjectBuilder): Warning? =
        body.putToolChoice(request.toolChoice) { choice -> put("name", wireName(choice.name)) }

    /** The tool that a call naming [wire] calls, given the [offered] tools: the inverse of [wireName]. */
    public open fun callName(wire: String, offered: List<ToolDefinition>): String =
        offered.firstOrNull { wireName(it.name) == wire }?.name ?: ToolNames.fromWire(wire) ?: wire

    /** The name that the tool [name] and its stored calls go out with, one for each name. */
    public open fun wireName(name: String): String = wireToolName(name)

    /** The `call_id` that the stored call [id] and its output go out with, one for each id. */
    public open fun wireCallId(id: ToolCallId): String = RESPONSES_CALL_IDS.of(id.value)
}

/** How a request keeps requests on one prompt cache, and how the backend counts what the cache held. */
public open class ResponsesCaching {
    /** Adds the fields that keep the requests that share [key] on one prompt cache to [body]. */
    public open fun key(key: String, body: JsonObjectBuilder) {
        body.put("prompt_cache_key", key)
    }

    /** The input tokens read from the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheReadTokens(usage: JsonObject): Long? = usage.obj("input_tokens_details")?.long("cached_tokens")

    /** The input tokens written to the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheWriteTokens(usage: JsonObject): Long? =
        usage.obj("input_tokens_details")?.long("cache_write_tokens")
}

/**
 * How the backend says why a response ended: by its `status`, by the `incomplete_details.reason` of an incomplete
 * response, or by a refusal in its output.
 */
public open class ResponsesFinishReasons {
    /** The kind of an end for the reason [raw], of a response that [calledTools] or not. */
    public open fun kind(raw: String, calledTools: Boolean): FinishKind = when (raw) {
        "completed" -> if (calledTools) FinishKind.TOOL_USE else FinishKind.END_TURN
        "max_output_tokens" -> FinishKind.MAX_OUTPUT_TOKENS
        "content_filter", "refusal" -> FinishKind.REFUSAL
        else -> FinishKind.OTHER
    }
}

/** How the backend names its failures. */
public open class ResponsesErrors {
    /** The kind of a failure of [type] and [code] that says [message], null when the HTTP status decides. */
    public open fun kind(type: String?, code: String?, message: String?): ModelErrorKind? = when {
        code == "rate_limit_exceeded" || type == "rate_limit_error" -> ModelErrorKind.RATE_LIMITED
        code == "insufficient_quota" || type == "insufficient_quota" -> ModelErrorKind.QUOTA_EXHAUSTED
        code == "server_is_overloaded" || code == "slow_down" -> ModelErrorKind.OVERLOADED
        code == "invalid_api_key" -> ModelErrorKind.AUTHENTICATION
        code in POLICY -> ModelErrorKind.CONTENT_FILTERED
        code == "invalid_prompt" || code?.contains("image") == true -> ModelErrorKind.INVALID_REQUEST
        code == "server_error" || type == "server_error" -> ModelErrorKind.SERVER_ERROR
        else -> null
    }

    private companion object {
        val POLICY = setOf("bio_policy", "misalignment_policy_violation", "image_content_policy_violation")
    }
}
