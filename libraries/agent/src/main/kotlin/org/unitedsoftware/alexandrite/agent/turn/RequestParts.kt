package org.unitedsoftware.alexandrite.agent.turn

import org.unitedsoftware.alexandrite.agent.context.Compactor
import org.unitedsoftware.alexandrite.agent.media.MediaPlan
import org.unitedsoftware.alexandrite.agent.model.SelectedModel
import org.unitedsoftware.alexandrite.agent.model.cacheKey
import org.unitedsoftware.alexandrite.agent.model.crossedTo
import org.unitedsoftware.alexandrite.agent.prompt.TurnContextPlan
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry

/** What a turn's requests to [model] share. */
internal class RequestParts(
    val model: SelectedModel,
    val options: ModelOptions,
    val window: Compactor.TurnWindow,
    val sections: List<PromptSection>,
    val tools: List<ToolDefinition>,
    val context: TurnContextPlan,
) {
    /**
     * The request of [turn]'s [round] with [options], whose history ends with the turn's own entries [current], their
     * media carried by [media] as [plan] routes them.
     */
    suspend fun request(
        turn: TurnInfo,
        current: List<TranscriptEntry>,
        round: Int,
        options: ModelOptions,
        toolless: Boolean,
        media: TurnMedia,
        plan: MediaPlan,
    ): ModelRequest {
        val (entries, turnStart) = window.history(current)
        val history = crossedTo(model.ref, media.inline(entries, plan))
        return ModelRequest.builder(model.ref, history, turnStart, RequestIds(turn.conversation, turn.id, round))
            .instructions(sections)
            .turnContext(context.kept)
            .tools(tools)
            .toolChoice(if (toolless) ToolChoice.None else ToolChoice.Auto)
            .options(options)
            .cacheKey(cacheKey(turn.key))
            .build()
    }
}
