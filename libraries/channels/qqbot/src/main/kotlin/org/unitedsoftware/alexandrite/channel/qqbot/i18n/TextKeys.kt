package org.unitedsoftware.alexandrite.channel.qqbot.i18n

/** The texts of this channel for people, as keys of its own bundle. */
internal object TextKeys {
    const val APPROVAL_TITLE: String = "approval.title"
    const val APPROVAL_TOOL: String = "approval.tool"
    const val APPROVAL_REASON: String = "approval.reason"
    const val APPROVAL_ARGUMENTS: String = "approval.arguments"
    const val APPROVAL_ALLOW: String = "approval.allow"
    const val APPROVAL_DENY: String = "approval.deny"
    const val APPROVAL_ALWAYS: String = "approval.always"
    const val APPROVAL_ALWAYS_SHORT: String = "approval.always_short"
    const val APPROVAL_ALLOWED: String = "approval.allowed"
    const val APPROVAL_DENIED: String = "approval.denied"
    const val APPROVAL_EXPIRED: String = "approval.expired"
    const val APPROVAL_ADMINS_ONLY: String = "approval.admins_only"
    const val APPROVAL_NOTHING_WAITING: String = "approval.nothing_waiting"
    const val APPROVAL_UNREACHABLE: String = "approval.unreachable"
    const val REFUSED_ADMIN: String = "refused.admin"
    const val REFUSED_ADMIN_HERE: String = "refused.admin_here"

    /** Takes `{code}` and `{minutes}`. */
    const val PAIRING_CODE: String = "pairing.code"

    /** Takes `{code}` and `{minutes}`. */
    const val LINK_CODE: String = "link.code"
    const val LINK_LINKED: String = "link.linked"
    const val LINK_OWN_CODE: String = "link.own_code"
    const val LINK_MALFORMED: String = "link.malformed"

    /** Takes `{tries}`. */
    const val LINK_REJECTED: String = "link.rejected"

    /** Takes `{minutes}`. */
    const val LINK_LOCKED: String = "link.locked"
    const val LINK_FAILED: String = "link.failed"
    const val LINKS_EMPTY: String = "links.empty"

    /** Takes `{chat}` and `{alias}`, one line per link. */
    const val LINKS_ENTRY: String = "links.entry"
    const val UNLINK_USAGE: String = "unlink.usage"
    const val UNLINK_DONE: String = "unlink.done"
    const val UNLINK_NONE: String = "unlink.none"
}
