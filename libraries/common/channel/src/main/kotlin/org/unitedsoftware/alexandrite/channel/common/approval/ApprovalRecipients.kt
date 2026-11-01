package org.unitedsoftware.alexandrite.channel.common.approval

import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo

/**
 * The chats a question of [turn] is shown in: the chat of an operator's own turn, else the paired private chats of
 * [admins], each built by [privateChatOf] and kept only when [isPaired].
 *
 * A member's turn is never shown in the chat it came from: an approval carries the call's arguments and the reason
 * it is asked about, which a member whose own call it gates has no more business reading than any other member of
 * that chat. [turn] is a plain [TurnInfo] rather than an [org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest]
 * so a trust offer's recipients, which answer the same question, are found the same way.
 */
public fun <T> approvalRecipients(
    turn: TurnInfo,
    admins: Collection<T>,
    isPaired: (ChatAddress) -> Boolean,
    privateChatOf: (T) -> ChatAddress,
): List<ChatAddress> = if (turn.actorIsOperator) {
    listOf(turn.chat)
} else {
    admins.distinct().map(privateChatOf).filter(isPaired)
}
