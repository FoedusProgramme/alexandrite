package org.unitedsoftware.alexandrite.channel.common.reply

import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.turn.ModelReply
import java.util.concurrent.ConcurrentHashMap

/** The reply of a turn, as the words of a model's request reach it. */
public interface ReplyWords {
    /** Shows [message], the response of one request of this turn. */
    public suspend fun showResponse(message: AssistantEntry)
}

/**
 * The replies of the turns of a channel instance that are open, by turn.
 *
 * A reply opens when the agent dequeues a turn and closes as the turn ends, so the words of a response reach the reply
 * of its own turn and no other. A turn whose reply is not here is one this instance does not speak for, such as a
 * delegated turn whose reply goes to the agent that asked for it.
 */
public open class ReplyRegistry<T : ReplyWords> {
    private val open = ConcurrentHashMap<TurnId, T>()

    /** Remembers [words] as the reply of [turn]. */
    public fun open(turn: TurnId, words: T) {
        open[turn] = words
    }

    /** Forgets the reply of [turn], which nothing speaks for afterwards. */
    public fun close(turn: TurnId) {
        open.remove(turn)
    }

    /** The reply of [turn], null when this instance speaks for no such turn. */
    public fun of(turn: TurnId): T? = open[turn]
}

/**
 * Tells the reply [payload]'s turn opened the words of its response, whole and once, as the request ends.
 *
 * A response that calls no tool is the answer of the turn, which the message that ends it carries, so telling it here
 * too would say it twice; a turn this registry speaks for none of, such as a delegated one, is told nothing.
 */
public suspend fun <T : ReplyWords> ReplyRegistry<T>.tellResponse(payload: ModelReply) {
    val words = of(payload.turn.id) ?: return
    if (payload.response.message.parts.none { it is ToolCallPart }) return
    words.showResponse(payload.response.message)
}
