package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** How a backend lists its models and what each listed model says about itself. */
public open class ModelListing {
    /** What a model can do when neither the backend nor the operator says. */
    public open val defaults: ModelFacts = ModelFacts(
        id = null,
        nativeTools = true,
        parallelToolCalls = true,
        inputMedia = emptySet(),
        reasoningEfforts = emptySet(),
    )

    /** The URLs that list the models below [baseUrl], tried in order while the backend answers 404. */
    public open fun urls(baseUrl: String): List<String> = listOf("$baseUrl/models")

    /** The model objects of one [page] of a listing. */
    public open fun models(page: JsonObject): List<JsonObject> =
        (page["data"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /** The URL of the page after [page], relative to the listing's and on its origin, null when there is none. */
    public open fun next(page: JsonObject): String? = null

    /** What a listed [model] says about itself, its id null when it names none. */
    public open fun facts(model: JsonObject): ModelFacts = ModelFacts(
        id = model.string("id"),
        contextWindow = model.positiveInt("context_window") ?: model.positiveInt("context_length")
            ?: model.positiveInt("max_model_len"),
    )
}
