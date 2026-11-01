package org.unitedsoftware.alexandrite.agent.context

import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.model.RetryPolicy
import org.unitedsoftware.alexandrite.agent.model.SideAnswer
import org.unitedsoftware.alexandrite.agent.model.ask
import org.unitedsoftware.alexandrite.agent.model.listed
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry

/** Has a model write the rolling summary of a conversation. */
@Singleton
internal class Summarizer(private val endpoints: Endpoints, private val retries: RetryPolicy) {
    /**
     * What [model] writes, in at most [maxTokens] tokens, as the summary of [previous] and [entries] that keeps [focus]
     * in view, blank when it writes none.
     */
    suspend fun summarize(
        model: ModelRef,
        ids: RequestIds,
        previous: SummaryEntry?,
        entries: List<TranscriptEntry>,
        focus: String?,
        maxTokens: Int,
    ): SideAnswer {
        val answer = endpoints.listed(model).ask(
            ids,
            SUMMARY_SOURCE,
            summarizerInput(previous, entries),
            summarizerSections(maxTokens, focus),
            maxTokens,
        ) { call -> retries.retrying(ids.turn) { call() } }
        return SideAnswer(answer.text.trim(), answer.usage)
    }
}
