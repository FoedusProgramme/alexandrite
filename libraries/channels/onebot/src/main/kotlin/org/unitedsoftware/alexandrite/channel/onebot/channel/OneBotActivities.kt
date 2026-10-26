package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * What each chat has been told of the turns working in it, by chat.
 *
 * A turn that works on tools is not the only one speaking to a chat: a run moved to the background opens a reply of
 * its own for the same chat, and the runtime hands each reply only its own turn's activity. A chat whose messages
 * cannot be added to would then hear the same work twice, so the words of a stretch of steps are kept here, by the
 * chat the turns speak to, and every reply of that chat tells what is waiting once.
 */
@Singleton
internal class OneBotActivities {
    private val byChat = ConcurrentHashMap<ChatAddress, OneBotProgress>()

    /** What waits to be told in [chat], which a chat is told of once however many turns are working in it. */
    fun of(chat: ChatAddress): OneBotProgress = byChat.computeIfAbsent(chat) { OneBotProgress() }

    /**
     * Forgets [chat] where it holds nothing to tell and no call still running.
     *
     * A turn that ends asks for this, so a chat that holds nothing is not kept for as long as the channel runs. A chat
     * another turn is still working in holds something, and is left as it is.
     */
    fun forget(chat: ChatAddress) {
        byChat.computeIfPresent(chat) { _, progress -> if (progress.idle()) null else progress }
    }
}
