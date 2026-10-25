package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.unitedsoftware.alexandrite.agent.tool.DelegatingTurn
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.ReplyActivity
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.rebuild
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity

/**
 * What a background run takes from the turn that delegated it, which may end first: the turn's words, tools and
 * approvals, but its own activity, handed over from the turn's reply or else shown in a reply of the run's own.
 */
internal class BackgroundParent(
    private val parent: DelegatingTurn,
    private val channels: ChannelDirectory,
    private val run: DelegatedRun,
) : DelegatingTurn by parent {
    private val words = parent.ownWords().toList()
    private val approvals = Mutex()
    private var sink: ReplySink? = null
    private val lifecycle = Mutex()
    private var closed = false
    private var activity: ReplyActivity? = null

    override val overBudget: Boolean get() = false
    override val callable: Set<String> = parent.callable.toSet()
    override fun spend(tokens: Long) = Unit
    override fun followedUp(): Boolean = false
    override fun ownWords(): List<String> = words

    override suspend fun <T : Any> asking(ask: suspend () -> T): T = approvals.withLock { ask() }

    override suspend fun show(event: TurnEvent) {
        activity?.event(event) ?: sink?.event(event)
    }

    /** Takes the run's activity over from the delegating turn's reply, while the turn still shows its call. */
    suspend fun prepare(): Unit = lifecycle.withLock {
        if (!closed && activityVisible) activity = parent.detachActivity(run)
    }

    /**
     * Opens a reply of the run's own when its activity could not be taken over, unless the channel gives each run a
     * reply of its own, which the run's turn opens.
     */
    suspend fun open(): Unit = lifecycle.withLock {
        if (closed || !activityVisible || activity != null) return
        val channel = channels.channel(turn.chat.instance) ?: return
        if (channel.capabilities(turn.chat).runReplies) return
        // A run below a run shows in the root chat, though its parent's turn replies to its own caller.
        sink = channel.openReply(ReplyRequest(turn.rebuild { replyTarget(ReplyTarget.CHAT) }, null, run))
        show(TurnEvent.ToolCallStarted(run.call, DELEGATE_TOOL, ToolActivity.DELEGATE, "${run.agent}", run.under))
    }

    suspend fun close(status: ToolCallStatus): Unit = lifecycle.withLock {
        if (closed) return
        closed = true
        activity?.let {
            it.close(status)
            return
        }
        try {
            show(TurnEvent.ToolCallUpdated(run.call, status, run = run.under))
        } finally {
            sink?.abandon(
                when (status) {
                    ToolCallStatus.COMPLETED -> ReplyEnd.COMPLETED
                    ToolCallStatus.FAILED -> ReplyEnd.FAILED
                    else -> ReplyEnd.CANCELLED
                },
            )
        }
    }
}
