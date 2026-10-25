package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.channel.onebot.api.OneBotApi
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * The API of every running OneBot instance, by the instance that serves a chat.
 *
 * A plugin that acts on a chat names the instance the chat belongs to, and an action of the standard is called on the
 * API of that instance. The API itself belongs to one instance of one channel, because each holds its own connection
 * and its own account, so a tool that runs once for the whole runtime reads the one it needs from here.
 *
 * An instance is here while it runs and gone once it stops, so a caller that finds none knows the channel is not
 * serving that chat right now.
 */
@Singleton
internal class OneBotApis {
    private val apis = ConcurrentHashMap<ChannelInstanceId, OneBotApi>()

    /** Records that [instance] is served by [api]. */
    fun register(instance: ChannelInstanceId, api: OneBotApi) {
        apis[instance] = api
    }

    /** Forgets [instance], which stopped. */
    fun forget(instance: ChannelInstanceId) {
        apis.remove(instance)
    }

    /** The API of [instance], null when no such instance is running. */
    fun of(instance: ChannelInstanceId): OneBotApi? = apis[instance]
}
