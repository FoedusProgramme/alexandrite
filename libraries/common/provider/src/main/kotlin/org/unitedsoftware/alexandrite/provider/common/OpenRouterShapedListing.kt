package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.model.ModelPrice
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import java.math.BigDecimal

/**
 * [ModelListing] for a backend that describes each model the way OpenRouter does: `context_length`,
 * `top_provider.max_completion_tokens`, `architecture.input_modalities`, `supported_parameters`, `pricing`.
 */
public open class OpenRouterShapedListing : ModelListing() {
    override fun facts(model: JsonObject): ModelFacts {
        val parameters = model.strings("supported_parameters")
        val tools = parameters?.let { "tools" in it }
        return ModelFacts(
            id = model.string("id"),
            displayName = model.string("name"),
            contextWindow =
            model.positiveInt("context_length") ?: model.obj("top_provider")?.positiveInt("context_length"),
            maxOutputTokens = model.obj("top_provider")?.positiveInt("max_completion_tokens"),
            nativeTools = tools,
            parallelToolCalls = parameters?.let { tools == true && "parallel_tool_calls" in it },
            inputMedia = model.obj(
                "architecture",
            )?.strings("input_modalities")?.mapNotNullTo(mutableSetOf()) { modality ->
                MediaKind.entries.firstOrNull { it.id == modality }
            },
            reasoningEfforts = parameters?.let {
                if ("reasoning" in it || "reasoning_effort" in it) OPEN_ROUTER_SHAPE_EFFORTS else emptySet()
            },
            price = model.obj("pricing")?.let(::openRouterShapedPrice),
        )
    }
}

/** The efforts a backend's `reasoning.effort` or `reasoning_effort` field takes, from its `supported_parameters`. */
public val OPEN_ROUTER_SHAPE_EFFORTS: Set<ReasoningEffort> = setOf(
    ReasoningEffort.NONE,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
)

/** The price that [pricing] lists in dollars per token, null when it lists no input or output price. */
public fun openRouterShapedPrice(pricing: JsonObject): ModelPrice? {
    val input = pricing.perMillion("prompt") ?: return null
    val output = pricing.perMillion("completion") ?: return null
    return ModelPrice(input, output, pricing.perMillion("input_cache_read"))
}

private fun JsonObject.perMillion(name: String): BigDecimal? =
    string(name)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.movePointRight(6)?.stripTrailingZeros()
