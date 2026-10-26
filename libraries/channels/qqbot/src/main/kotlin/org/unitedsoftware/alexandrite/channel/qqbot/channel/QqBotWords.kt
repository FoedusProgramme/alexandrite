package org.unitedsoftware.alexandrite.channel.qqbot.channel

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
internal typealias QqBotWords = ReplyWords

/** The replies of the turns of this instance that are open, by turn. */
@Singleton
internal class QqBotReplyRegistry : ReplyRegistry<QqBotWords>()

/**
 * Tells the reply of a turn the words of each of its requests, as OneBot does for a chat whose messages cannot be
 * rewritten. A streaming single chat already shows those words in its preview, so this only sends the responses of a
 * non-streaming chat.
 */
@Singleton
@Contribute(Hook::class)
internal class QqBotWordsHook(private val replies: QqBotReplyRegistry) : InterceptorHook<ModelReply> {
    override val point: InterceptorPoint<ModelReply> get() = TurnPoints.LLM_RESPONSE

    override suspend fun intercept(payload: ModelReply): HookDecision<ModelReply> {
        replies.tellResponse(payload)
        return HookDecision.Continue
    }
}
