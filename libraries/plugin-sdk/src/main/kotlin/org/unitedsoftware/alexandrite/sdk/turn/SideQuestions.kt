package org.unitedsoftware.alexandrite.sdk.turn

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi

/**
 * Questions to a chat's model about its current conversation, asked aside: no turn runs, nothing is stored, and no
 * channel or turn control sees them.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface SideQuestions {
    /**
     * What the current model of the chat's agent answers to [question] after the chat's current conversation, asked in
     * a request that starts as that of the next message of [by] there, so that a provider's prompt cache serves it;
     * null when the agent has no conversation at [chat].
     *
     * [reasoning] asks with that effort in place of the chat's own, which null keeps; an effort the model does not take
     * is left out, as the chat's own would be. Chat Completions and Responses send the effort as an option outside the
     * prompt, so a cache keyed on the prompt's prefix serves any effort, though a backend may keep its caches apart by
     * effort; Anthropic's Messages send it as `thinking` and `output_config.effort`, and a request whose effort differs
     * from the conversation's misses the cache of its messages (and on some models of its system prompt and tools), so
     * asking such a model with the chat's own keeps the cache.
     *
     * While a turn runs there, the question follows what the turn stored up to its first tool call still running.
     * Throws [ControlRefusedException] when no agent serves [chat] or the agent spent its budget, the model's
     * [ModelException] when its call fails, and [IllegalArgumentException] when [question] is blank.
     */
    public suspend fun ask(
        chat: ChatAddress,
        question: String,
        by: ChatUser? = null,
        reasoning: ReasoningEffort? = null,
    ): String?
}
