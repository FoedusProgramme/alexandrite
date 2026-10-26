package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

/**
 * One streaming message of a single chat, which the platform lets a reply grow in place.
 *
 * The first piece starts the message and gives the id the later pieces name; every piece replaces the shown text with
 * the whole text so far, and the last one ends the message. A platform that takes no streaming, or a piece it refuses,
 * leaves the reply to the ordinary message path: the parts are then not shown, and only the final message is sent.
 */
internal class QqBotStream(
    private val sender: QqBotSender,
    private val chat: ChatAddress,
    private val msgId: String,
    private val msgSeq: Int,
) {
    private val lock = Mutex()

    @Volatile private var streamId: String? = null

    @Volatile private var index = 0

    @Volatile private var shown = ""

    @Volatile private var failed = false

    @Volatile private var finished = false

    /** The id of the platform message this stream writes, null before the first piece was taken. */
    val messageId: String? get() = streamId

    /** What the platform has accepted as the message so far. */
    val shownText: String get() = shown

    /** Shows [text], the whole text of the streaming message so far; false once the platform refuses. */
    suspend fun show(text: String): Boolean = lock.withLock {
        if (failed || finished || text.isEmpty()) return@withLock false
        val delta = delta(text)
        if (delta.isEmpty()) return@withLock true
        val answer = sender.stream(chat, delta, msgId, msgSeq, streamId, index, last = false, append = true)
        if (answer !is QqBotAnswer.Ok) {
            failed = true
            return@withLock false
        }
        val id = sender.sentId(answer) ?: run {
            failed = true
            return@withLock false
        }
        streamId = id
        shown += delta
        index++
        true
    }

    /** Ends the streaming message with [text], the whole answer; null when it was never started or is refused. */
    suspend fun finish(text: String): Delivery? = lock.withLock { finishLocked(text) }

    /** Ends the streaming message with what it already showed. */
    suspend fun finish(): Delivery? = lock.withLock { finishLocked(shown) }

    private suspend fun finishLocked(text: String): Delivery? {
        if (failed || finished) return null
        val id = streamId ?: return null
        val delta = delta(text.ifBlank { shown })
        val answer = sender.stream(chat, delta, msgId, msgSeq, id, index, last = true, append = true)
        return if (answer is QqBotAnswer.Ok) {
            finished = true
            sender.delivery(chat, answer)
        } else {
            failed = true
            null
        }
    }

    /** Appends [text] as the next piece, without comparing it with what was shown. */
    suspend fun append(text: String): Boolean = lock.withLock {
        if (failed || finished || text.isEmpty()) return@withLock false
        val answer = sender.stream(chat, text, msgId, msgSeq, streamId, index, last = false, append = true)
        if (answer !is QqBotAnswer.Ok) {
            failed = true
            return@withLock false
        }
        val id = sender.sentId(answer) ?: run {
            failed = true
            return@withLock false
        }
        streamId = id
        shown += text
        index++
        true
    }

    /** Ends the streaming message after its pieces were appended. */
    suspend fun finishAppended(): Delivery? = lock.withLock {
        if (failed || finished) return@withLock null
        val id = streamId ?: return@withLock null
        val answer = sender.stream(chat, "", msgId, msgSeq, id, index, last = true, append = true)
        if (answer is QqBotAnswer.Ok) {
            finished = true
            sender.delivery(chat, answer)
        } else {
            failed = true
            null
        }
    }

    /**
     * The part of [text] the platform does not hold yet.
     *
     * A stream is appended in pieces: a preview that grows contributes its suffix, and text that does not continue
     * what was shown (an answer replacing a tool activity, a rewritten segment) starts a new paragraph. Append mode
     * has no prefix restriction, unlike replace, which refused such a body with `40113003`.
     */
    private fun delta(text: String): String = when {
        shown.isEmpty() -> text
        text.startsWith(shown) -> text.removePrefix(shown)
        else -> "\n\n" + text
    }

    /** Ends a stream that is not carried on, so the chat does not show it as still being written. */
    suspend fun abandon() = lock.withLock {
        val id = streamId ?: return@withLock
        if (failed || finished) return@withLock
        finished = true
        runCatching { sender.stream(chat, shown, msgId, msgSeq, id, index, last = true) }
    }
}
