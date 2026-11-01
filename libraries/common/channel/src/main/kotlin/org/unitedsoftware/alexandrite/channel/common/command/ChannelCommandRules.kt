package org.unitedsoftware.alexandrite.channel.common.command

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import java.util.Locale

/** Who may run a command. */
@Serializable
public enum class CommandWho {
    @SerialName("anyone")
    ANYONE,

    /** Anyone in a direct chat, admins elsewhere. */
    @SerialName("direct")
    DIRECT,

    /** Anyone in a direct chat linked with no other chat, admins elsewhere. */
    @SerialName("unlinked_direct")
    UNLINKED_DIRECT,

    @SerialName("admin")
    ADMIN,
}

/** Who may run a command: [bare] without arguments, [withArguments] with them. */
public class CommandRule(public val bare: CommandWho, public val withArguments: CommandWho = bare)

/** Why a command was refused: an admin may run it anywhere, or here but not elsewhere. */
public enum class CommandRefusal {
    ADMIN,
    ADMIN_HERE,
}

/** The pattern a command name matches, lowercase letters, digits and `_`, 32 characters at most. */
public val COMMAND_NAME: Regex = Regex("[a-z0-9_]{1,32}")

/** The rules of the default commands a channel's config may not already have a name for. */
public val DEFAULT_COMMAND_RULES: Map<String, CommandRule> = mapOf(
    "help" to CommandRule(CommandWho.ANYONE),
    "settings" to CommandRule(CommandWho.ANYONE),
    "context" to CommandRule(CommandWho.ANYONE),
    "profiles" to CommandRule(CommandWho.ANYONE),
    "agent" to CommandRule(CommandWho.ANYONE, CommandWho.DIRECT),
    "reasoning" to CommandRule(CommandWho.ANYONE, CommandWho.UNLINKED_DIRECT),
    "language" to CommandRule(CommandWho.ANYONE, CommandWho.UNLINKED_DIRECT),
    "timezone" to CommandRule(CommandWho.ANYONE, CommandWho.UNLINKED_DIRECT),
    "model" to CommandRule(CommandWho.ANYONE, CommandWho.ADMIN),
    "mode" to CommandRule(CommandWho.ANYONE, CommandWho.ADMIN),
    "jobs" to CommandRule(CommandWho.ANYONE, CommandWho.ADMIN),
    "new" to CommandRule(CommandWho.UNLINKED_DIRECT),
    "compact" to CommandRule(CommandWho.UNLINKED_DIRECT),
    "cancel" to CommandRule(CommandWho.DIRECT),
    "trust" to CommandRule(CommandWho.ADMIN),
    "untrust" to CommandRule(CommandWho.ADMIN),
    "allow" to CommandRule(CommandWho.ADMIN),
    "untaint" to CommandRule(CommandWho.ADMIN),
    "streaming" to CommandRule(CommandWho.ANYONE, CommandWho.DIRECT),
    "heartbeat" to CommandRule(CommandWho.ANYONE),
    "schedule" to CommandRule(CommandWho.ANYONE, CommandWho.ADMIN),
    "home" to CommandRule(CommandWho.ADMIN),
    "link" to CommandRule(CommandWho.ADMIN),
    "unlink" to CommandRule(CommandWho.ADMIN),
)

/**
 * Who may run each command that a handler claims: the rule [configured] names for it, else [DEFAULT_COMMAND_RULES],
 * else the forms its spec leaves to anyone when it keeps some for operators, else admins alone. The forms its spec
 * keeps for operators stay with admins whatever the rule.
 *
 * Nothing else checks this. The agent's control takes the operator of a change as a parameter and does not judge it,
 * so a channel that does not ask this question lets anyone who can write in a chat change the agent's model, its
 * language, and the agent a chat speaks to.
 */
public class ChannelCommandRules(
    handlers: List<CommandHandler>,
    private val links: ChatLinks?,
    private val configured: Map<String, CommandWho> = emptyMap(),
    private val isDirect: (ChatAddress) -> Boolean,
) {
    private val operatorsOnly =
        handlers.flatMap { it.commands }.associate { it.name.lowercase(Locale.ROOT) to it.operatorsOnly }

    /** Why [invocation]'s issuer may not run it in a chat of [kind], null when it may. */
    public fun refusal(invocation: CommandInvocation, kind: ChatKind): CommandRefusal? {
        if (invocation.issuer.isOperator) return null
        val name = invocation.name.lowercase(Locale.ROOT)
        return refusal(name, invocation.arguments.isNotBlank(), kind == ChatKind.DIRECT) { linked(invocation.chat) }
    }

    /** Whether an admin, or a member when not [admin], may run [command] in [chat], bare or [withArguments]. */
    public fun mayRun(chat: ChatAddress, admin: Boolean, command: String, withArguments: Boolean): Boolean {
        if (admin) return true
        return refusal(command.lowercase(Locale.ROOT), withArguments, isDirect(chat)) { linked(chat) } == null
    }

    private fun refusal(name: String, withArguments: Boolean, direct: Boolean, linked: () -> Boolean): CommandRefusal? {
        val kept = operatorsOnly[name] ?: return null
        if (kept.includes(withArguments)) return CommandRefusal.ADMIN
        val who = configured[name]
            ?: DEFAULT_COMMAND_RULES[name]?.let { if (withArguments) it.withArguments else it.bare }
            ?: if (kept == CommandForms.NONE) CommandWho.ANYONE else CommandWho.ADMIN
        return when (who) {
            CommandWho.ANYONE -> null
            CommandWho.DIRECT -> CommandRefusal.ADMIN_HERE.takeUnless { direct }
            CommandWho.UNLINKED_DIRECT -> CommandRefusal.ADMIN_HERE.takeUnless { direct && !linked() }
            CommandWho.ADMIN -> CommandRefusal.ADMIN
        }
    }

    private fun linked(chat: ChatAddress): Boolean = links?.groups().orEmpty().any { group ->
        (group.members + group.anchor).any { it.parent == chat.parent }
    }
}
