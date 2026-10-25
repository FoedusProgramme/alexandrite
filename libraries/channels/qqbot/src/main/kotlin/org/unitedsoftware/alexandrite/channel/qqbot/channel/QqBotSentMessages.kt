package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * The last message the bot sent to each chat, by the id the platform reported for it.
 *
 * The platform recalls a message by the id its send answered with, which is not the id of the message a turn answers.
 * Keeping it here lets a recall without an explicit id take back the bot's own last message.
 */
@Singleton
internal class QqBotSentMessages {
    private val last = ConcurrentHashMap<ChatAddress, String>()

    /** Notes that the bot sent [id] to [chat], where the platform reported a real id. */
    fun record(chat: ChatAddress, id: String) {
        if (id.isBlank() || id == "sent" || id == "typing") return
        last[chat] = id
    }

    /** The id of the last message the bot sent to [chat], null when it sent none this process knows. */
    fun lastOf(chat: ChatAddress): String? = last[chat]

    /** Forgets the last message of [chat]. */
    fun forget(chat: ChatAddress) {
        last.remove(chat)
    }
}
