package org.unitedsoftware.alexandrite.channel.common.approval

import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

/** What a written answer in a chat names among the requests it carries. */
public sealed interface ApprovalResolution {
    /** The chat carries no request this names. */
    public data object None : ApprovalResolution

    /** The one request this names. */
    public class One(public val id: String) : ApprovalResolution

    /** More than one request's id starts with the code written, naming none of them in particular. */
    public class Ambiguous(public val ids: List<String>) : ApprovalResolution

    /** No code was written: every request the chat carries, to list rather than answer. */
    public class Listing(public val requests: List<ApprovalRequest>) : ApprovalResolution
}

/**
 * What a [code] written in [chat] names among [pending]'s requests that [chat] carries: every one of them when
 * [code] is null, else the one whose id [code] names exactly, else every one whose id starts with it, so typing
 * less than the whole id still answers the request it is the unique start of.
 */
public fun resolveApproval(pending: Collection<PendingApproval>, chat: ChatAddress, code: String?): ApprovalResolution {
    val waiting = pending.filter { it.carries(chat) }
    if (code == null) {
        return if (waiting.isEmpty()) {
            ApprovalResolution.None
        } else {
            ApprovalResolution.Listing(
                waiting.map {
                    it.request
                },
            )
        }
    }
    // An id written out in full is matched first: the request it names exactly is the one meant, even when another
    // request's id happens to start with the same letters.
    waiting.firstOrNull { it.request.id == code }?.let { return ApprovalResolution.One(it.request.id) }
    val matched = waiting.filter { it.request.id.startsWith(code) }
    return when (matched.size) {
        1 -> ApprovalResolution.One(matched.single().request.id)
        0 -> ApprovalResolution.None
        else -> ApprovalResolution.Ambiguous(matched.map { it.request.id })
    }
}
