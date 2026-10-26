package org.unitedsoftware.alexandrite.channel.qqbot.channel

/**
 * The commands a message of this channel carries.
 *
 * The platform has no command entity, so a command is the same shape the Telegram channel reads from `bot_command`:
 * a slash, then a name of 1 to 32 ASCII letters, digits or underscores, optionally followed by `@target`, and the
 * arguments after it. A slash that does not start such a name is ordinary text.
 */
internal object QqBotCommands {
    private val TOKEN = Regex("^/([A-Za-z0-9_]{1,32})(?:@[A-Za-z0-9_]{1,32})?$")

    /** The command [text] carries, null when its slash does not start one. */
    fun of(text: String): Parsed? {
        val trimmed = text.trimStart()
        val token = trimmed.takeWhile { !it.isWhitespace() }
        val name = TOKEN.matchEntire(token)?.groupValues?.get(1) ?: return null
        return Parsed(name, trimmed.removePrefix(token).trim())
    }

    /** The parts of a command: its [name] and its [arguments], which may be empty. */
    class Parsed(val name: String, val arguments: String)
}
