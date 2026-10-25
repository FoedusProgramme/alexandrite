package org.unitedsoftware.alexandrite.agent.turn

import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import java.time.Instant

/** The messages and job results queued at a running turn's key, which the turn may take as follow-ups. */
internal interface FollowUps {
    /** Takes the messages that [principal] sent in [chat] since [since] off the queue, in arrival order. */
    fun take(principal: UserAddress, chat: ChatAddress, since: Instant): List<FollowUp>

    /** Puts [followUps], taken and not absorbed, back at the front of the queue. */
    fun restore(followUps: List<FollowUp>)

    /** Whether [take] would return a message now. */
    fun queued(principal: UserAddress, chat: ChatAddress, since: Instant): Boolean

    /** Returns once [take] would return a message. */
    suspend fun await(principal: UserAddress, chat: ChatAddress, since: Instant)

    /** Takes the messages that people sent to the sub-agent run itself off the queue, in arrival order. */
    fun takeSent(): List<FollowUp> = emptyList()

    /** Takes the results of the jobs that turns from [chat] started in [conversation] off the queue, in queue order. */
    fun takeJobs(chat: ChatAddress, conversation: ConversationId): List<FollowUp>

    /**
     * Waits until a result of a job that [conversation] started, or a message sent to its run, is queued and returns
     * true, or returns false once no such job runs and no message is queued; false at once where results start turns
     * of their own, as at a top-level turn.
     */
    suspend fun awaitJobs(conversation: ConversationId): Boolean = false
}

/** A queued message or job result that a running turn took, whose own turn ends with what [complete] is given. */
internal class FollowUp(val plan: TurnPlan, val complete: (TurnOutcome) -> Unit)

/** The permit that lets a turn run beside the others, which it gives up while it waits for a person. */
internal interface TurnPermit {
    /** Runs [block] without the permit, and takes it again before returning unless the turn was cancelled. */
    suspend fun <T> released(block: suspend () -> T): T
}
