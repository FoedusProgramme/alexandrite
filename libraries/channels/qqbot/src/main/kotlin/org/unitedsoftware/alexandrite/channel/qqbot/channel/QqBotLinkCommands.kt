package org.unitedsoftware.alexandrite.channel.qqbot.channel

/**
 * The link commands a message of this channel carries: `/link` asks for a code or spends one, `/links` lists an
 * operator's own, and `/unlink` revokes one.
 */
internal object QqBotLinkCommands {
    private val LINK = Regex("^/link(?:@[A-Za-z0-9_]+)?(?:\\s+(\\S+))?\\s*$", RegexOption.IGNORE_CASE)

    private val LINKS = Regex("^/links(?:@[A-Za-z0-9_]+)?\\s*$", RegexOption.IGNORE_CASE)

    private val UNLINK = Regex("^/unlink(?:@[A-Za-z0-9_]+)?(?:\\s+(\\S+))?\\s*$", RegexOption.IGNORE_CASE)

    /** The link command [text] carries, null when it carries none. */
    fun of(text: String): Parsed? {
        val trimmed = text.trim()
        LINK.matchEntire(trimmed)?.let { match ->
            val code = match.groupValues[1].takeIf { it.isNotEmpty() }
            return if (code == null) Parsed.Issue else Parsed.Redeem(code)
        }
        if (LINKS.matches(trimmed)) return Parsed.List
        UNLINK.matchEntire(trimmed)?.let { match ->
            return Parsed.Unlink(match.groupValues[1].takeIf { it.isNotEmpty() })
        }
        return null
    }

    internal sealed interface Parsed {
        /** `/link` bare: a one-time code for the operator who asks. */
        data object Issue : Parsed

        /** `/link <code>`: spends [code] for the id the platform names the sender by here. */
        class Redeem(val code: String) : Parsed

        /** `/links`: the operator's own links, across every chat. */
        data object List : Parsed

        /** `/unlink [chat]`: revokes the operator's own link of [chat], null for no argument at all. */
        class Unlink(val chat: String?) : Parsed
    }
}
