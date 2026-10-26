package org.unitedsoftware.alexandrite.channel.qqbot.tools

import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * The API of each open channel instance, which a tool of the shared tool layer looks up by the chat of its call.
 *
 * A tool is a singleton and the API of an instance lives in that instance's scope, so the instance registers its API
 * while it is open. A call of a chat whose instance is not open reaches nothing, which is the answer the tool gives.
 */
@Singleton
internal class QqBotApis {
    private val apis = ConcurrentHashMap<ChannelInstanceId, QqBotApi>()

    /** Makes [api] the API of [instance] while it is open. */
    fun register(instance: ChannelInstanceId, api: QqBotApi) {
        apis[instance] = api
    }

    /** Forgets the API of [instance]. */
    fun forget(instance: ChannelInstanceId) {
        apis.remove(instance)
    }

    /** The API that serves [chat], null when its instance is not open. */
    fun serving(chat: ChatAddress): QqBotApi? = apis[chat.instance]
}
