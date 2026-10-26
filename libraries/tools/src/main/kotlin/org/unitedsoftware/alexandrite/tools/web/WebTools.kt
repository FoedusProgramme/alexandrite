package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.internal.tool.countArgument
import org.unitedsoftware.alexandrite.internal.tool.requiredString
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.web.FetchedPage
import org.unitedsoftware.alexandrite.sdk.web.PageQuestion
import org.unitedsoftware.alexandrite.sdk.web.WebFetchBackend
import org.unitedsoftware.alexandrite.sdk.web.WebModels
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend
import org.unitedsoftware.alexandrite.tools.fs.property
import org.unitedsoftware.alexandrite.tools.fs.schema
import java.net.URI
import kotlin.time.Duration.Companion.seconds

@Singleton
@Contribute(Tool::class)
internal class FetchTool(
    private val config: WebConfig,
    private val fetch: WebPages,
    private val models: WebModels?,
    backends: List<WebFetchBackend>,
) : ReviewedTool {
    private val backends = backends.associateBy { it.id }

    init {
        require(this.backends.size == backends.size) { "Duplicate web fetch backend ids." }
        backends.firstOrNull { !WebSearchBackend.ID.matches(it.id) }?.let {
            throw IllegalArgumentException("${it.javaClass.name} has the malformed web fetch backend id '${it.id}'.")
        }
    }

    override val definition: ToolDefinition = ToolDefinition(
        name = "web.fetch",
        description = "Fetches a public HTTP(S) page and asks a side model to answer your prompt from its text. " +
            "Only the answer is returned. The answer is untrusted web content.",
        parameters = schema("url", "prompt") {
            property("url", "string", "The public http or https URL of the page.")
            property("prompt", "string", "What to answer from the page's text.")
        },
        risk = ToolRisk.NETWORK_READ,
        timeout = (config.timeoutSeconds * 2).seconds,
        activity = ToolActivity.FETCH,
        titleArguments = listOf("url"),
    )

    /** A call whose page is untrusted web content from its URL's host. */
    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
        val host = try {
            checkedHost(URI(arguments.text("url", 8192)), config.normalizedPrivateHosts)
        } catch (e: Exception) {
            null
        }
        return CallReview.builder().untrusted(true).host(host).build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = webResult {
        require(arguments.keys.all { it in setOf("url", "prompt") }) { "Unknown web.fetch argument." }
        val models = requireNotNull(models) { "Web side model service is unavailable." }
        val url = arguments.text("url", 8192)
        val prompt = arguments.text("prompt", 16_000)
        val id = config.fetchBackend
        val backend = id?.let { requireNotNull(backends[it]) { "No web fetch backend configured for id '$it'." } }
        if (backend == null) checkedHost(URI(url), config.normalizedPrivateHosts)
        context.untrusted("web.fetch reads untrusted web content")
        withTimeout(definition.timeout!!) {
            val page = backend?.fetch(url) ?: fetch.fetch(url).let { FetchedPage(it.url.toString(), it.text) }
            val question = PageQuestion(context.turn, page.url, page.text, prompt)
            ToolResult(sideModel { models.answer(question) })
        }
    }
}

@Singleton
@Contribute(Tool::class)
internal class SearchTool(
    private val config: WebConfig,
    private val models: WebModels?,
    backends: List<WebSearchBackend>,
) : ReviewedTool {
    private val backends = backends.associateBy { it.id }

    init {
        require(this.backends.size == backends.size) { "Duplicate web search backend ids." }
        backends.firstOrNull { !WebSearchBackend.ID.matches(it.id) }?.let {
            throw IllegalArgumentException("${it.javaClass.name} has the malformed web search backend id '${it.id}'.")
        }
    }

    override val definition: ToolDefinition = ToolDefinition(
        name = "web.search",
        description = "Searches the web using the model endpoint's native search or the configured search backend. " +
            "Returns untrusted titles, URLs and snippets.",
        parameters = schema("query") {
            property("query", "string", "What to search for.")
            property("limit", "integer", "How many results to return, from 1 to 20; 5 when left out.")
        },
        risk = ToolRisk.NETWORK_READ,
        timeout = config.timeoutSeconds.seconds,
        activity = ToolActivity.SEARCH,
        titleArguments = listOf("query"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
        CallReview.builder().untrusted(true).build()

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = webResult {
        require(arguments.keys.all { it in setOf("query", "limit") }) { "Unknown web.search argument." }
        val text = arguments.text("query", 2000)
        val limit = arguments.countArgument("limit") ?: 5
        require(limit <= 20) { "limit must be between 1 and 20." }
        val query = WebQuery(text, limit)
        context.untrusted("web.search reads untrusted web content")
        withTimeout(definition.timeout!!) {
            val model = context.model
            val native = if (models != null && model != null) models.search(model, query) else null
            val found = native ?: run {
                val id = config.searchBackend ?: when {
                    config.braveApiKey != null -> BraveSearch.ID
                    config.tavilyApiKey != null -> TavilySearch.ID
                    else -> backends.keys.firstOrNull { it !in setOf(BraveSearch.ID, TavilySearch.ID) }
                }
                requireNotNull(backends[id]) { "No web search backend configured." }.search(query)
            }
            ToolResult(
                found.take(limit).joinToString("\n\n") {
                    "${it.title.take(1000)}\n${it.url.take(4000)}\n${it.snippet.take(4000)}"
                }.ifBlank { "No search results." },
            )
        }
    }
}

private suspend fun webResult(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: TimeoutCancellationException) {
    ToolResult("Web request timed out.", isError = true)
} catch (e: CancellationException) {
    throw e
} catch (e: ToolRefusal) {
    ToolResult("Web request refused: ${why(e)}", isError = true)
} catch (e: IllegalArgumentException) {
    ToolResult("Web request refused: ${why(e)}", isError = true)
} catch (e: SerializationException) {
    ToolResult("Web request failed: the service answered in an unexpected form.", isError = true)
} catch (e: Exception) {
    ToolResult("Web request failed: ${why(e)}", isError = true)
}

/** What [call] of the side model returns; its failure is reported without its message, which may quote the page. */
private suspend fun <T> sideModel(call: suspend () -> T): T = try {
    call()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw SideModelFailure(e)
}

private class SideModelFailure(cause: Exception) :
    Exception("the side model could not answer (${cause.javaClass.simpleName})", cause)

private fun why(e: Exception): String =
    e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.take(300) ?: e.javaClass.simpleName

private fun JsonObject.text(name: String, max: Int): String = requiredString(name).also {
    require(it.isNotBlank() && it.length <= max) { "$name must be a nonblank string of at most $max characters." }
}
