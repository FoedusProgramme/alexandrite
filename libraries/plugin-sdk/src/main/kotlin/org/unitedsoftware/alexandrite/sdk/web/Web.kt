package org.unitedsoftware.alexandrite.sdk.web

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.BoundSpi
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef

/** A search service contributed by a plugin, which the host's settings choose by its [id]. */
@ContributedSpi
public interface WebSearchBackend {
    /** Lowercase letters, digits and `-`, as [ID] takes them, such as `brave`. */
    public val id: String

    public suspend fun search(query: WebQuery): List<WebSearchResult>

    public companion object {
        /** The ids a backend may have. */
        public val ID: Regex = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    }
}

/** A web search for [text], wanting at most [limit] results. */
@Poko
public class WebQuery(public val text: String, public val limit: Int) {
    init {
        require(text.isNotBlank()) { "A web query is not blank." }
        require(limit > 0) { "A web query wants at least one result, was $limit." }
    }
}

/** A result returned by a search service. */
@Poko
public class WebSearchResult(public val title: String, public val url: String, public val snippet: String)

/** A page-fetching service contributed by a plugin, which the host's settings choose by its [id]. */
@ContributedSpi
public interface WebFetchBackend {
    /** Lowercase letters, digits and `-`, as [WebSearchBackend.ID] takes them, such as `pleochroic`. */
    public val id: String

    public suspend fun fetch(url: String): FetchedPage
}

/** A page a fetch backend read, its [text] already reduced to what the backend gives (plain text, Markdown, …). */
@Poko
public class FetchedPage(public val url: String, public val text: String)

/** A [question] about the text of the page at [url], for the turn's agent's web model to answer from that text alone. */
@Poko
public class PageQuestion(
    public val turn: TurnInfo,
    public val url: String,
    public val page: String,
    public val question: String,
)

/** What the web tools ask of the turn's models. */
@BoundSpi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface WebModels {
    /** The side model's answer to [question], which never sees anything but the question and the page. */
    public suspend fun answer(question: PageQuestion): String

    /** The results of [query] from the server-side search that [model]'s endpoint has, null when it has none. */
    public suspend fun search(model: ModelRef, query: WebQuery): List<WebSearchResult>?
}
