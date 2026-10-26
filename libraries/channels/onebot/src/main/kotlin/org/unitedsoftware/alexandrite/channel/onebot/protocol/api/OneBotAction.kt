package org.unitedsoftware.alexandrite.channel.onebot.protocol.api

/**
 * One action of the OneBot API.
 *
 * The suffix of a call is not part of the action: `send_private_msg_async` calls [name] with [OneBotCallSuffix.ASYNC],
 * so an implementation never has to copy an action for the variants the standard derives.
 */
public data class OneBotAction(
    /** The action name, such as `send_private_msg`. */
    public val name: String,
    /** Which part of the API the action belongs to. */
    public val category: OneBotCategory,
    /** Whether the action is one the standard hides from ordinary users, such as `.handle_quick_operation`. */
    public val hidden: Boolean = false,
    /** Whether an implementation runs the action anyway, so the `async` suffix adds nothing. */
    public val inherentlyAsync: Boolean = false,
) {
    init {
        require(name.isNotEmpty()) { "An action needs a name." }
    }

    /** Whether the action exists under [suffix]. */
    public fun accepts(suffix: OneBotCallSuffix): Boolean = when (suffix) {
        OneBotCallSuffix.NONE -> true
        OneBotCallSuffix.ASYNC, OneBotCallSuffix.RATE_LIMITED -> category.derivable
    }

    /** The action name of a call with [suffix]. */
    public fun actionName(suffix: OneBotCallSuffix): String = name + suffix.suffix

    public companion object {
        /** The prefix every hidden action carries. */
        public const val HIDDEN_PREFIX: String = "."
    }
}

/** A variant of an action the standard derives with a suffix. */
public enum class OneBotCallSuffix(public val suffix: String) {
    /** The plain call. */
    NONE(""),

    /** The call the implementation answers at once and carries out later. */
    ASYNC("_async"),

    /** The call the implementation queues behind its own rate limit. */
    RATE_LIMITED("_rate_limited"),
    ;

    public companion object {
        /** The suffix [action] carries, null when it carries none. */
        public fun of(action: String): OneBotCallSuffix? =
            entries.firstOrNull { it != NONE && action.endsWith(it.suffix) }

        /** [action] without the suffix [this] names. */
        public fun strip(action: String): String =
            entries.filter { it != NONE }.firstOrNull { action.endsWith(it.suffix) }
                ?.let { action.dropLast(it.suffix.length) }
                ?: action
    }
}

/** Which part of the API an action belongs to, which decides whether the derived suffixes exist. */
public enum class OneBotCategory(
    /** Whether the standard derives the `_async` and `_rate_limited` calls of the actions. */
    public val derivable: Boolean,
) {
    /** Messages: sending, recalling and reading them. */
    MESSAGE(true),

    /** Friends, groups and their members. */
    GROUP(true),

    /** The credentials and the files of the implementation. */
    ACCOUNT(true),

    /** The implementation itself: its status, its version, its restart. */
    SYSTEM(true),

    /** What an implementation does on its own, such as running a quick operation. */
    HIDDEN(false),
}
