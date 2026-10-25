package org.unitedsoftware.alexandrite.provider.pleochroic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFixture

/** The provider checks' fixture of a Pleochroic endpoint: `GET /v1/models` in the OpenRouter shape. */
internal class PleochroicFixture : ChatFixture(PLEOCHROIC_FLAVOR) {
    override fun listing(): JsonObject = buildJsonObject {
        putJsonArray("data") {
            for ((id, rich) in listOf(model to true, plainModel to false)) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("name", "Pleochroic $id")
                        put("context_length", 128000)
                        putJsonObject("architecture") {
                            val modalities = listOfNotNull("text", "image".takeIf { rich })
                            put("input_modalities", JsonArray(modalities.map(::JsonPrimitive)))
                        }
                        putJsonObject("top_provider") { put("max_completion_tokens", 32000) }
                        val extra = listOf("tools", "parallel_tool_calls", "reasoning_effort").takeIf { rich }
                        val parameters = listOf("temperature") + extra.orEmpty()
                        put("supported_parameters", JsonArray(parameters.map(::JsonPrimitive)))
                    },
                )
            }
        }
    }
}
