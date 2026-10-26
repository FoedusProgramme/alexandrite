package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * The open streaming message of each qqbot turn, which an approval closes before the reply continues.
 *
 * A reply's stream belongs to the turn that opened it. Once an approval message is shown, the reply's final answer
 * cannot be moved below it; this registry lets the approval desk close the stream and remember that the answer is to
 * be sent as a message of its own.
 */
@Singleton
internal class QqBotProgress {
    private class Open(val chat: ChatAddress, val stream: QqBotStream)

    private val open = ConcurrentHashMap<TurnId, Open>()
    private val gates = ConcurrentHashMap<TurnId, Mutex>()
    private val deferred = ConcurrentHashMap.newKeySet<TurnId>()

    private fun gate(turn: TurnId): Mutex = gates.computeIfAbsent(turn) { Mutex() }

    /** Makes [stream] the streaming message of [turn]. */
    suspend fun attach(turn: TurnId, chat: ChatAddress, stream: QqBotStream) {
        gate(turn).withLock {
            if (turn in deferred) return@withLock
            open.getOrPut(turn) { Open(chat, stream) }
        }
    }

    /**
     * Closes every open stream of [chat] before an approval message.
     *
     * An approval can be asked by a delegated run whose turn is not the turn that opened the stream. The chat is
     * what the stream and the approval have in common, so an approval defers all replies open in it.
     */
    suspend fun deferChat(chat: ChatAddress): Int {
        var closed = 0
        for (turn in open.entries.filter { it.value.chat == chat }.map { it.key }) {
            gate(turn).withLock {
                val entry = open.remove(turn) ?: return@withLock
                entry.stream.finish()
                deferred += turn
                closed++
            }
        }
        return closed
    }

    /** Whether the final answer of [turn] is to be sent outside its stream, after an approval request. */
    fun isDeferred(turn: TurnId): Boolean = turn in deferred

    /** Done with [turn], whose deferred answer was sent or whose stream is its own from here. */
    fun done(turn: TurnId) {
        deferred -= turn
        open.remove(turn)
        gates.remove(turn)
    }

    /** Forgets [turn], which ended without a stream to close. */
    suspend fun abandon(turn: TurnId) {
        deferred -= turn
        gate(turn).withLock {
            open.remove(turn)
            gates.remove(turn)
        }
    }
}
