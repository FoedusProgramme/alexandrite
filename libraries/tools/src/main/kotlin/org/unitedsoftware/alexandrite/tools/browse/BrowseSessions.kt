package org.unitedsoftware.alexandrite.tools.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.sdk.browse.BrowseBackend
import org.unitedsoftware.alexandrite.sdk.browse.BrowseOpen
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** One session per conversation, opened at its first step and closed by `close` or [closeAll]. */
internal class BrowseSessions(private val idleSeconds: Int) {
    /** [scale]: the session's image pixels per pixel of the latest visible-area image the model was shown. */
    private class Open(val backend: BrowseBackend, val id: String, var scale: Double = 1.0)

    private val open = ConcurrentHashMap<ConversationId, Open>()
    private val locks = ConcurrentHashMap<ConversationId, Mutex>()

    /** Runs [block] with [conversation]'s session, one step at a time per conversation. */
    suspend fun <T> inSession(conversation: ConversationId, block: suspend Steps.() -> T): T =
        locks.computeIfAbsent(conversation) { Mutex() }.withLock { Steps(conversation).block() }

    inner class Steps(private val conversation: ConversationId) {
        /** The session's id in [backend], opening one when the conversation has none (or had one in another). */
        suspend fun session(backend: BrowseBackend): Pair<String, Boolean> {
            open[conversation]?.takeIf { it.backend === backend }?.let { return it.id to false }
            open.remove(conversation)?.let { old -> closeQuietly(old) }
            val id = backend.open(BrowseOpen(idleSeconds))
            open[conversation] = Open(backend, id)
            return id to true
        }

        val scale: Double get() = open[conversation]?.scale ?: 1.0

        fun scaled(scale: Double) {
            open[conversation]?.scale = scale
        }

        fun forget() {
            open.remove(conversation)
        }

        /** Closes the conversation's session, answering whether it had one. */
        suspend fun close(): Boolean {
            val old = open.remove(conversation) ?: return false
            old.backend.close(old.id)
            return true
        }
    }

    // (a backend may bill each open session until it closes or idles out)
    suspend fun closeAll() {
        val all = open.values.toList()
        open.clear()
        all.forEach { closeQuietly(it) }
    }

    private suspend fun closeQuietly(session: Open) {
        try {
            withTimeoutOrNull(10.seconds) { session.backend.close(session.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the backend closes it after its idle time anyway
        }
    }
}
