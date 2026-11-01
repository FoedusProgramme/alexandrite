package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.unitedsoftware.alexandrite.provider.common.EndpointCore
import org.unitedsoftware.alexandrite.provider.common.ErrorDetail
import org.unitedsoftware.alexandrite.provider.common.modelException
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.protocol
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.sdk.web.WebSearchResult

/**
 * The results of [query] from one request to [model] that must call the backend's `web_search` tool: the pages its
 * answer cites, each with the cited text, and then the other sources it searched.
 */
internal suspend fun nativeSearch(
    core: EndpointCore,
    model: String,
    query: WebQuery,
    classify: (ErrorDetail) -> ModelErrorKind?,
): List<WebSearchResult> {
    val body = buildJsonObject {
        put("model", model)
        put("instructions", "Search the web for the user's query. Use web_search and cite the relevant results.")
        put("input", query.text)
        putJsonArray("tools") { add(buildJsonObject { put("type", "web_search") }) }
        put("tool_choice", "required")
        putJsonArray("include") { add(JsonPrimitive("web_search_call.action.sources")) }
        put("store", false)
        put("stream", false)
    }
    val response = core.postJson("/responses", body.toString(), emptyMap(), classify)
    val output = response["output"] as? JsonArray ?: protocol("Native web search returned no output.")
    val found = linkedMapOf<String, WebSearchResult>()
    val sources = mutableListOf<String>()
    var searched = false
    for (element in output) {
        val item = element as? JsonObject ?: protocol("Native web search returned an invalid output item.")
        when (item.string("type")) {
            "web_search_call" -> {
                searched = true
                if (item.string("status") == "failed") {
                    throw modelException(ModelErrorKind.SERVER_ERROR, "Native web search failed.", false)
                }
                (item.obj("action")?.get("sources") as? JsonArray)?.forEach { source ->
                    (source as? JsonObject)?.string("url")?.let(sources::add)
                }
            }

            "message" -> (item["content"] as? JsonArray)?.forEach { content -> cite(content as? JsonObject, found) }
        }
    }
    if (!searched) protocol("Native web search did not execute a search.")
    sources.forEach { url -> found.putIfAbsent(url, WebSearchResult(url, url, "")) }
    return found.values.take(query.limit)
}

/** Adds the pages that the `url_citation` annotations of the output text [content] cite to [found]. */
private fun cite(content: JsonObject?, found: MutableMap<String, WebSearchResult>) {
    if (content?.string("type") != "output_text") return
    val text = content.string("text").orEmpty()
    (content["annotations"] as? JsonArray)?.forEach { element ->
        val annotation = element as? JsonObject ?: return@forEach
        if (annotation.string("type") != "url_citation") return@forEach
        val url = annotation.string("url") ?: return@forEach
        val start = annotation.index("start_index")
        val end = annotation.index("end_index")
        val snippet = if (start != null && end != null && start <= end && end <= text.length) {
            text.substring(start, end)
        } else {
            ""
        }
        found.putIfAbsent(url, WebSearchResult(annotation.string("title") ?: url, url, snippet))
    }
}

private fun JsonObject.index(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
