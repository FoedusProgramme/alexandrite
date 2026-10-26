package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.channel.onebot.mapping.QuotedMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/**
 * The messages this instance read not long ago, so that a message answering one says what it answered.
 *
 * A report names the message it answers and says nothing about it, and reading it back from the implementation is a
 * call for every answer. What the channel read as it arrived is kept instead, which is enough for what a chat normally
 * answers: something said not long before. A message older than what is kept is read back from the implementation, and
 * one it no longer knows is answered with its name alone.
 *
 * What is kept is bounded by the chats and by the messages in each, because a chat that runs for long says far more
 * than any answer reaches back to.
 */
@ChannelInstanceScoped
internal class OneBotRecentMessages {
    /** The messages of each chat, oldest first, with the chats least recently asked for forgotten first. */
    private val chats = object : LinkedHashMap<ChatAddress, MutableMap<String, QuotedMessage>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ChatAddress, MutableMap<String, QuotedMessage>>,
        ) = size > MAX_CHATS
    }

    /** Keeps [message] as what the message [id] of [chat] holds. */
    fun remember(chat: ChatAddress, id: String, message: QuotedMessage) {
        if (id.isEmpty()) return
        synchronized(chats) {
            val held = chats.getOrPut(chat) { LinkedHashMap() }
            held[id] = message
            while (held.size > MAX_PER_CHAT) {
                held.remove(held.keys.first())
            }
        }
    }

    /** What the message [id] of [chat] holds, null when it was not read or is no longer kept. */
    fun find(chat: ChatAddress, id: String): QuotedMessage? = synchronized(chats) { chats[chat]?.get(id) }

    private companion object {
        /** How many chats' messages are kept. */
        const val MAX_CHATS = 256

        /** How many messages of one chat are kept. */
        const val MAX_PER_CHAT = 200
    }
}
