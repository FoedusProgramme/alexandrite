package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.channel.common.reply.ReplyRegistry
import org.unitedsoftware.alexandrite.channel.common.reply.ReplyWords
import org.unitedsoftware.alexandrite.channel.common.reply.tellResponse
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.turn.ModelReply
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints

/** The reply of a turn, as the words of a model's request reach it. */
internal typealias OneBotWords = ReplyWords

/**
 * The replies of the turns of this instance that are open, by turn.
 *
 * A turn whose reply is not here is one this instance does not speak for, such as a delegated turn whose reply goes
 * to the agent that asked for it.
 */
@Singleton
internal class OneBotReplyRegistry : ReplyRegistry<OneBotWords>()

/**
 * Tells the reply of a turn the words of each of its requests, whole and once, as the request ends.
 *
 * This is what the platform needs where a message cannot be added to later: a channel that can change its messages
 * shows a growing preview and has no use for this point, while a chat of this one is told each response in full at the
 * moment the model finished saying it.
 */
@Singleton
@Contribute(Hook::class)
internal class OneBotWordsHook(private val replies: OneBotReplyRegistry) : InterceptorHook<ModelReply> {
    override val point: InterceptorPoint<ModelReply> get() = TurnPoints.LLM_RESPONSE

    override suspend fun intercept(payload: ModelReply): HookDecision<ModelReply> {
        replies.tellResponse(payload)
        return HookDecision.Continue
    }
}
