package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.channel.common.command.ChannelCommandRules
import org.unitedsoftware.alexandrite.channel.common.command.CommandRefusal
import org.unitedsoftware.alexandrite.channel.common.command.CommandWho
import org.unitedsoftware.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.onebot.i18n.TextKeys
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotMessages
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation

/**
 * Who may run each command of this channel, by [ChannelCommandRules] of `channel-common` with the instance's own
 * [configured] rules.
 */
@ChannelInstanceScoped
internal class OneBotCommandRules(
    handlers: List<CommandHandler>,
    links: ChatLinks?,
    configured: Map<String, CommandWho> = emptyMap(),
) {
    @Inject
    constructor(
        handlers: List<CommandHandler>,
        links: ChatLinks?,
        config: OneBotInstanceConfig,
    ) : this(handlers, links, config.commands)

    private val rules = ChannelCommandRules(handlers, links, configured) { !OneBotMessages.isGroup(it) }

    /** The key of the refusal of [invocation] in a chat of [kind], null when its issuer may run it. */
    fun refusal(invocation: CommandInvocation, kind: ChatKind): String? = rules.refusal(invocation, kind)?.key()

    /** Whether an admin, or a member when [admin] is false, may run [command] in [chat]. */
    fun mayRun(chat: ChatAddress, admin: Boolean, command: String, withArguments: Boolean): Boolean =
        rules.mayRun(chat, admin, command, withArguments)

    private fun CommandRefusal.key(): String = when (this) {
        CommandRefusal.ADMIN -> TextKeys.REFUSED_ADMIN
        CommandRefusal.ADMIN_HERE -> TextKeys.REFUSED_ADMIN_HERE
    }
}
