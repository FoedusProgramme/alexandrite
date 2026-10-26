package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/**
 * The messages this instance sent, so that one answering the bot can be told from one answering somebody else.
 *
 * A reported message carries the id it answers and no author of it, and the only other way to learn that author is a
 * call to the implementation for every answer in every group. What this instance sent is what it can answer for, so
 * the ids of its own messages are what a reply is matched against.
 *
 * The ids are kept in the order they were sent and only so many of them, because a chat that runs for long sends far
 * more messages than any answer can reach back to.
 */
@ChannelInstanceScoped
internal class OneBotSentMessages {
    private val sent = ArrayDeque<String>()

    /** Records that this instance sent the message [id]. */
    fun remember(id: String) {
        if (id.isEmpty()) return
        synchronized(sent) {
            sent.remove(id)
            sent.addLast(id)
            while (sent.size > KEPT) sent.removeFirst()
        }
    }

    /** Whether [id] is a message this instance sent. */
    fun holds(id: String): Boolean = synchronized(sent) { id in sent }

    private companion object {
        /** How many of the messages this instance sent are remembered. */
        const val KEPT = 512
    }
}
