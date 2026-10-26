package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/**
 * Who operates this instance, the one question [QqBotChannel.isOperator] and a message's own [sender][isOperator]
 * both ask.
 *
 * The platform names an account by its single-chat open id in a direct chat and by another id in each group, so
 * [QqBotInstanceConfig.admins] alone, which holds the single-chat ids, never names an admin inside a group. A group's
 * own owner or administrator role is not asked here. An admin reaches a group as themselves only once [links] holds
 * an alias of them there.
 */
@ChannelInstanceScoped
internal class QqBotOperators(private val config: QqBotInstanceConfig, private val links: QqBotOperatorLinks) {
    /** Whether [id], the account the platform names a sender by in [chat], operates this instance. */
    suspend fun isOperator(chat: ChatAddress, id: String): Boolean =
        id in config.admins || links.resolve(chat, id) != null
}
