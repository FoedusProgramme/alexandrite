package org.unitedsoftware.alexandrite.channel.onebot.protocol.api

/**
 * Every action of the OneBot v11 API, and how a call names one.
 *
 * A call is an action plus an [OneBotCallSuffix], so the `_async` and `_rate_limited` variants the standard derives
 * exist without an implementation of their own. An action this version does not know is still a call: [resolve]
 * reports it as unknown and the transport sends its name as it is, which keeps an implementation's own extension
 * usable without a release of this module.
 */
public object OneBotRegistry {
    /** The action that runs a quick operation on an event. */
    public val QUICK_OPERATION: OneBotAction =
        OneBotAction(".handle_quick_operation", OneBotCategory.HIDDEN, hidden = true)

    /** The hidden actions of the standard. */
    public val hiddenActions: List<OneBotAction> = listOf(QUICK_OPERATION)

    /** The actions the standard lists, by name. */
    public val actions: List<OneBotAction> = listOf(
        OneBotAction("send_private_msg", OneBotCategory.MESSAGE),
        OneBotAction("send_group_msg", OneBotCategory.MESSAGE),
        OneBotAction("send_msg", OneBotCategory.MESSAGE),
        OneBotAction("delete_msg", OneBotCategory.MESSAGE),
        OneBotAction("get_msg", OneBotCategory.MESSAGE),
        OneBotAction("get_forward_msg", OneBotCategory.MESSAGE),
        OneBotAction("send_like", OneBotCategory.GROUP),
        OneBotAction("set_group_kick", OneBotCategory.GROUP),
        OneBotAction("set_group_ban", OneBotCategory.GROUP),
        OneBotAction("set_group_anonymous_ban", OneBotCategory.GROUP),
        OneBotAction("set_group_whole_ban", OneBotCategory.GROUP),
        OneBotAction("set_group_admin", OneBotCategory.GROUP),
        OneBotAction("set_group_anonymous", OneBotCategory.GROUP),
        OneBotAction("set_group_card", OneBotCategory.GROUP),
        OneBotAction("set_group_name", OneBotCategory.GROUP),
        OneBotAction("set_group_leave", OneBotCategory.GROUP),
        OneBotAction("set_group_special_title", OneBotCategory.GROUP),
        OneBotAction("set_friend_add_request", OneBotCategory.ACCOUNT),
        OneBotAction("set_group_add_request", OneBotCategory.ACCOUNT),
        OneBotAction("get_login_info", OneBotCategory.ACCOUNT),
        OneBotAction("get_stranger_info", OneBotCategory.ACCOUNT),
        OneBotAction("get_friend_list", OneBotCategory.ACCOUNT),
        OneBotAction("get_group_info", OneBotCategory.GROUP),
        OneBotAction("get_group_list", OneBotCategory.GROUP),
        OneBotAction("get_group_member_info", OneBotCategory.GROUP),
        OneBotAction("get_group_member_list", OneBotCategory.GROUP),
        OneBotAction("get_group_honor_info", OneBotCategory.GROUP),
        OneBotAction("get_cookies", OneBotCategory.ACCOUNT),
        OneBotAction("get_csrf_token", OneBotCategory.ACCOUNT),
        OneBotAction("get_credentials", OneBotCategory.ACCOUNT),
        OneBotAction("get_record", OneBotCategory.ACCOUNT),
        OneBotAction("get_image", OneBotCategory.ACCOUNT),
        OneBotAction("can_send_image", OneBotCategory.SYSTEM),
        OneBotAction("can_send_record", OneBotCategory.SYSTEM),
        OneBotAction("get_status", OneBotCategory.SYSTEM),
        OneBotAction("get_version_info", OneBotCategory.SYSTEM),
        OneBotAction("set_restart", OneBotCategory.SYSTEM),
        OneBotAction("clean_cache", OneBotCategory.SYSTEM),
    )

    private val byName: Map<String, OneBotAction> = (actions + hiddenActions).associateBy(OneBotAction::name)

    /** The action named [name], null when this version does not know it. */
    public fun action(name: String): OneBotAction? = byName[name]

    /** How [call] names an action, unknown when this version does not list the action it names. */
    public fun resolve(call: String): OneBotCall {
        val suffix = OneBotCallSuffix.of(call) ?: OneBotCallSuffix.NONE
        val name = OneBotCallSuffix.strip(call)
        val action = byName[name]
        return if (action != null) {
            OneBotCall.Known(action, suffix)
        } else {
            OneBotCall.Unknown(call, name, suffix)
        }
    }

    /** Whether [call] names an action this version lists. */
    public fun knows(call: String): Boolean = resolve(call) is OneBotCall.Known
}

/** How a call names one action. */
public sealed interface OneBotCall {
    /** The action the call names. */
    public val action: String

    /** The suffix the call carries. */
    public val suffix: OneBotCallSuffix

    /** A call of an action this version lists. */
    public data class Known(public val definition: OneBotAction, override val suffix: OneBotCallSuffix) : OneBotCall {
        override val action: String get() = definition.name

        /** Whether the action exists in the shape the call asks for. */
        public val isSupported: Boolean get() = definition.accepts(suffix)
    }

    /**
     * A call of an action this version does not list.
     *
     * [name] is [action] without the suffix, so a transport sends [action] as the implementation wrote it and can
     * still tell the variants apart.
     */
    public data class Unknown(
        override val action: String,
        public val name: String,
        override val suffix: OneBotCallSuffix,
    ) : OneBotCall
}
