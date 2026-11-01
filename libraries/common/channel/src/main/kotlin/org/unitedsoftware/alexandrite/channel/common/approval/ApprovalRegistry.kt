package org.unitedsoftware.alexandrite.channel.common.approval

import kotlinx.coroutines.CompletableDeferred
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * One approval request on its way, and the chats that currently carry it: those whose message a reply or a written
 * answer may resolve the request by, which is not every chat it was merely meant for — a chat [send] of [carrying]
 * never truly reached carries nothing.
 */
public class PendingApproval(public val request: ApprovalRequest) {
    /** How the request ended, completed once by whichever of a timeout, an answer or a cancellation is first. */
    public val ending: CompletableDeferred<ApprovalEnding> = CompletableDeferred()

    private val chats = ConcurrentHashMap.newKeySet<ChatAddress>()

    /** Marks [chat] as carrying this request, before the platform can take the message that shows it there. */
    public fun carry(chat: ChatAddress) {
        chats += chat
    }

    /** Undoes [carry]: nothing in [chat] ever answers this request until [carry] marks it again. */
    public fun uncarry(chat: ChatAddress) {
        chats -= chat
    }

    /** Whether [chat] carries this request. */
    public fun carries(chat: ChatAddress): Boolean = chat in chats

    /** The chats that carry this request, a snapshot taken now. */
    public val carriedChats: Set<ChatAddress> get() = chats.toSet()
}

/**
 * Marks [chat] as carrying this request before [send] can take it, so a reply or a written answer can never race
 * ahead of the registration — not even when [send] delivers on a later try, or not at all. The mark is undone
 * whenever [send] does not end in `true`, including when it throws: a `finally` runs either way, cancellation
 * included, so a chat [send] leaves half-tried is never offered as one this request can be answered in.
 */
public suspend fun PendingApproval.carrying(chat: ChatAddress, send: suspend () -> Boolean): Boolean {
    carry(chat)
    var sent = false
    try {
        sent = send()
        return sent
    } finally {
        if (!sent) uncarry(chat)
    }
}

/** How a [PendingApproval] ended, besides being answered outright. */
public sealed interface ApprovalEnding {
    /** The answer this ending reports to the caller of the ask that is ending. */
    public val answer: ApprovalAnswer

    /** Nobody answered within the channel's configured wait. */
    public data object Expired : ApprovalEnding {
        override val answer: ApprovalAnswer = ApprovalAnswer.Expired
    }

    /** The turn that asked was cancelled before anybody answered. */
    public data object Withdrawn : ApprovalEnding {
        override val answer: ApprovalAnswer = ApprovalAnswer.Unavailable("the request was withdrawn")
    }

    /** An admin answered, by a press or a written reply. */
    public class Answered(override val answer: ApprovalAnswer) : ApprovalEnding
}

/**
 * The approval requests of one channel instance that are on their way, by id.
 *
 * [values] is read by [resolve] to answer a reply or a written id against every request a chat carries, so an
 * instance keeps one registry across every chat it serves rather than one per chat.
 */
public class ApprovalRegistry {
    private val pending = ConcurrentHashMap<String, PendingApproval>()

    /** Registers [request] as pending, null when another request already holds its id. */
    public fun register(request: ApprovalRequest): PendingApproval? {
        val waiting = PendingApproval(request)
        return if (pending.putIfAbsent(request.id, waiting) == null) waiting else null
    }

    /** Forgets the request [id]: its ask is done, whichever way it ended. */
    public fun forget(id: String) {
        pending.remove(id)
    }

    /** The request [id] still waits as, null once it is answered, expired, withdrawn or never was. */
    public operator fun get(id: String): PendingApproval? = pending[id]

    /** Every request still on its way. */
    public val values: Collection<PendingApproval> get() = pending.values

    /** Ends every request still pending as [ApprovalAnswer.Unavailable] of [reason], e.g. because the instance stopped. */
    public fun stopAll(reason: String) {
        pending.values.forEach { it.ending.complete(ApprovalEnding.Answered(ApprovalAnswer.Unavailable(reason))) }
    }
}
