package org.unitedsoftware.alexandrite.channel.qqbot.mapping

import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotMessageEvent
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress

/**
 * The chats of a QQ bot instance, printed the way the rest of Alexandrite reads them.
 *
 * A single chat is `private:<user openid>` and a group is `group:<group openid>`. The ids are the open ids the
 * platform gave the bot itself: the same user or group has another id for another bot.
 */
internal object QqBotChats {
    const val PRIVATE: String = "private"

    const val GROUP: String = "group"

    /** The chat [event] was written in. */
    fun addressOf(instance: ChannelInstanceId, event: QqBotMessageEvent): ChatAddress {
        val group = event.groupOpenid
        return if (group.isNullOrEmpty()) {
            ChatAddress(instance, "$PRIVATE:${event.author.openid(group = false)}")
        } else {
            ChatAddress(instance, "$GROUP:$group")
        }
    }

    /** The kind of [chat], null when it names no chat this channel serves. */
    fun kind(chat: ChatAddress): ChatKind? = when {
        chat.chat.startsWith("$PRIVATE:") -> ChatKind.DIRECT
        chat.chat.startsWith("$GROUP:") -> ChatKind.GROUP
        else -> null
    }

    /** The openid of [chat], null when it names no chat this channel serves. */
    fun openid(chat: ChatAddress): String? = when {
        chat.chat.startsWith("$PRIVATE:") -> chat.chat.removePrefix("$PRIVATE:").takeIf { it.isNotEmpty() }
        chat.chat.startsWith("$GROUP:") -> chat.chat.removePrefix("$GROUP:").takeIf { it.isNotEmpty() }
        else -> null
    }

    /** The path below the API base that sends a message to [chat], null when it names no chat this channel serves. */
    fun sendPath(chat: ChatAddress): String? {
        val id = openid(chat) ?: return null
        return when (kind(chat)) {
            ChatKind.DIRECT -> "/v2/users/$id/messages"
            ChatKind.GROUP -> "/v2/groups/$id/messages"
            else -> null
        }
    }

    /** The user of [id] in this instance. */
    fun user(instance: ChannelInstanceId, id: String): UserAddress = UserAddress(instance, id)
}
