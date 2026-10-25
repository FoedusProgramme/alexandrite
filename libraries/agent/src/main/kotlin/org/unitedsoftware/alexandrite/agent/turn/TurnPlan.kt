package org.unitedsoftware.alexandrite.agent.turn

import org.unitedsoftware.alexandrite.agent.delegation.Delegation
import org.unitedsoftware.alexandrite.agent.job.EndedJob
import org.unitedsoftware.alexandrite.agent.messaging.AgentMessage
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.tool.Mandate
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute
import java.time.Instant

/** A turn as its worker queues it. */
internal class TurnPlan(
    val id: TurnId,
    /** The worker's key, at the anchor of a linked group. */
    val key: AgentChatKey,
    /** The chat the turn came from, which its replies and policy follow. */
    val origin: ChatAddress,
    val kind: TurnKind,
    val input: TurnSource,
    val actor: ChatUser?,
    val replyTarget: ReplyTarget,
    val trigger: ChannelMessageRef?,
    val queuedAt: Instant,
    /** Where the final reply of a turn that replies to its caller goes. */
    val route: ReplyRoute = ReplyRoute.None,
    /** The call that the turn makes for its operator before it asks its model anything, null when it makes none. */
    val operatorCall: ToolCallPart? = null,
)

/** What a turn answers. */
internal sealed interface TurnSource {
    class FromAgentMessage(val message: AgentMessage) : TurnSource

    class FromMessage(val message: IncomingMessage) : TurnSource

    /** The [prompt] that the plugin [plugin] started the turn with, under its [mandate] when it has one. */
    class FromInitiated(val plugin: String, val prompt: String, val trust: Trust, val mandate: Mandate? = null) :
        TurnSource

    /** The result of [job], which ended in the background. */
    class FromJob(val job: EndedJob) : TurnSource

    /** The task of the sub-agent run [delegation]. */
    class FromDelegation(val delegation: Delegation) : TurnSource
}
