package org.unitedsoftware.alexandrite.sdk.web

import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi

/**
 * A browser-automation service a plugin could contribute, chosen by its [id] the same way [WebSearchBackend] and
 * [WebFetchBackend] are. Alexandrite ships none built in: `init`'s browser automation step lists only the ids a
 * plugin has actually contributed, and says plainly that there are none when there are not, rather than assuming
 * one particular backend.
 *
 * Deliberately thin until a real backend exists to shape it against: a plugin author who implements this now
 * should expect [act]'s contract to grow (structured instructions, screenshots, a session that outlives one call,
 * …), not necessarily to keep this exact shape.
 */
@ContributedSpi
public interface WebBrowserBackend {
    /** Lowercase letters, digits and `-`, as [WebSearchBackend.ID] takes them. */
    public val id: String

    /** Runs one browser instruction (open a page, click, type, read what is on screen, …) given as free text
     * [instruction], and answers with what happened, also as free text. */
    public suspend fun act(instruction: String): String

    public companion object {
        /** The ids a backend may have, the same grammar [WebSearchBackend.ID] uses. */
        public val ID: Regex = WebSearchBackend.ID
    }
}
