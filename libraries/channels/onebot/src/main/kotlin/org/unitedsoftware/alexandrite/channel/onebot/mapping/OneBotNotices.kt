package org.unitedsoftware.alexandrite.channel.onebot.mapping

import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.sdk.channel.ChannelNotice
import org.unitedsoftware.alexandrite.sdk.channel.ChannelNoticeKind
import org.unitedsoftware.alexandrite.sdk.channel.DisplayFact
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import java.time.Instant

/**
 * What a report says besides a message, as the channel contract's own notice.
 *
 * A report of an event this version does not model is published all the same, under the name the implementation gave
 * it: a plugin written for an implementation's own vocabulary reads it without this mapping having to know it, and a
 * subscriber sees that something happened.
 */
public object OneBotNotices {
    /** The notice [event] reports, null when it reports none this channel publishes. */
    public fun of(instance: ChannelInstanceId, event: OneBotEvent, selfId: String): ChannelNotice? = when (event) {
        is OneBotEvent.Notice.GroupIncrease -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.MEMBER_JOINED,
            chat = OneBotChats.groupChat(instance, event.groupId),
            actor = event.operatorId.takeIf { it != event.userId }?.let { user(instance, it, selfId) },
            target = user(instance, event.userId, selfId),
            facts = listOfNotNull(subtype(event.subType)),
        )

        is OneBotEvent.Notice.GroupDecrease -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.MEMBER_LEFT,
            chat = OneBotChats.groupChat(instance, event.groupId),
            actor = event.operatorId.takeIf { it != event.userId }?.let { user(instance, it, selfId) },
            target = user(instance, event.userId, selfId),
            facts = listOfNotNull(subtype(event.subType)),
        )

        is OneBotEvent.Notice.GroupBan -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.MEMBER_BANNED,
            chat = OneBotChats.groupChat(instance, event.groupId),
            actor = user(instance, event.operatorId, selfId),
            target = user(instance, event.userId, selfId),
            facts = listOfNotNull(
                DisplayFact("muted", if (event.isBan) "yes" else "no"),
                event.duration?.let { DisplayFact("seconds", "$it") },
                subtype(event.subType),
            ),
        )

        is OneBotEvent.Notice.GroupAdmin -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.ROLE_CHANGED,
            chat = OneBotChats.groupChat(instance, event.groupId),
            target = user(instance, event.userId, selfId),
            facts = listOf(DisplayFact("administrator", if (event.isSet) "yes" else "no")),
        )

        is OneBotEvent.Notice.GroupRecall -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.MESSAGE_RECALLED,
            chat = OneBotChats.groupChat(instance, event.groupId),
            actor = user(instance, event.operatorId, selfId),
            target = user(instance, event.userId, selfId),
            message = ChannelMessageRef(OneBotChats.groupChat(instance, event.groupId), event.messageId.value),
        )

        is OneBotEvent.Notice.FriendRecall -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.MESSAGE_RECALLED,
            chat = OneBotChats.privateChat(instance, event.userId),
            target = user(instance, event.userId, selfId),
            message = ChannelMessageRef(OneBotChats.privateChat(instance, event.userId), event.messageId.value),
        )

        is OneBotEvent.Notice.FriendAdd -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.FRIEND_ADDED,
            chat = OneBotChats.privateChat(instance, event.userId),
            target = user(instance, event.userId, selfId),
            facts = listOfNotNull(subtype(event.subType)),
        )

        is OneBotEvent.Notice.GroupUpload -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind("group.file_uploaded"),
            chat = OneBotChats.groupChat(instance, event.groupId),
            actor = user(instance, event.userId, selfId),
            facts = listOfNotNull(
                DisplayFact("file", event.file.name),
                event.file.size?.let { DisplayFact("bytes", "$it") },
            ),
        )

        is OneBotEvent.Notice.Notify -> notify(instance, event, selfId)

        is OneBotEvent.Request.Friend -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.FRIEND_REQUESTED,
            chat = OneBotChats.privateChat(instance, event.userId),
            target = user(instance, event.userId, selfId),
            facts = requestFacts(event.comment, event.flag, null),
        )

        is OneBotEvent.Request.Group -> notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind.JOIN_REQUESTED,
            chat = OneBotChats.groupChat(instance, event.groupId),
            target = user(instance, event.userId, selfId),
            facts = requestFacts(event.comment, event.flag, event.subType),
        )

        // Everything else keeps the name the implementation gave it, so that a vocabulary this version does not model
        // reaches a subscriber written for that implementation.
        is OneBotEvent.Notice.Other -> chatOf(instance, event)?.let { chat ->
            notice(
                instance = instance,
                event = event,
                kind = ChannelNoticeKind(event.noticeType),
                chat = chat,
                actor = event.userId()?.let { user(instance, it, selfId) },
                facts = listOfNotNull(subtype(event.subType)),
            )
        }

        else -> null
    }

    /** A poke, a lucky king or a member honor, which the implementation reports as one kind of notice. */
    private fun notify(instance: ChannelInstanceId, event: OneBotEvent.Notice.Notify, selfId: String): ChannelNotice {
        val chat = event.groupId?.let { OneBotChats.groupChat(instance, it) }
            ?: OneBotChats.privateChat(instance, event.userId)
        return notice(
            instance = instance,
            event = event,
            kind = ChannelNoticeKind("notice.${event.subType ?: "notify"}"),
            chat = chat,
            actor = user(instance, event.userId, selfId),
            target = event.targetId?.let { user(instance, it, selfId) },
            facts = listOfNotNull(event.honorType?.let { DisplayFact("honor", it) }),
        )
    }

    private fun notice(
        instance: ChannelInstanceId,
        event: OneBotEvent,
        kind: ChannelNoticeKind,
        chat: ChatAddress,
        actor: ChatUser? = null,
        target: ChatUser? = null,
        message: ChannelMessageRef? = null,
        facts: List<DisplayFact> = emptyList(),
    ): ChannelNotice = ChannelNotice(
        chat = chat,
        kind = kind,
        actor = actor,
        target = target,
        message = message,
        receivedAt = Instant.ofEpochSecond(event.time),
        facts = facts,
    )

    private fun requestFacts(comment: String, flag: String, subtype: String?): List<DisplayFact> = listOfNotNull(
        DisplayFact("answer with", flag),
        comment.takeIf { it.isNotBlank() }?.let { DisplayFact("said", it) },
        subtype(subtype),
    )

    private fun subtype(subtype: String?): DisplayFact? = subtype?.let { DisplayFact("how", it) }

    /** The chat of a notice this version does not model, which names a group only when its report carries one. */
    private fun chatOf(instance: ChannelInstanceId, event: OneBotEvent.Notice.Other): ChatAddress? {
        val group = event.raw["group_id"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
        val user = event.raw["user_id"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
        return when {
            group != null -> OneBotChats.groupChat(instance, GroupId(group))
            user != null -> OneBotChats.privateChat(instance, UserId(user))
            else -> null
        }
    }

    /** Whether an unmodelled notice names anybody, which is read from the report it kept. */
    private fun OneBotEvent.Notice.Other.userId(): UserId? =
        raw["user_id"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }?.let(::UserId)

    /** Who [id] is, as far as a report that names them without a profile says. */
    private fun user(instance: ChannelInstanceId, id: UserId, selfId: String): ChatUser = ChatUser(
        address = UserAddress(instance, id.value),
        displayName = id.value,
        username = null,
        isBot = id.value == selfId,
        isOperator = false,
    )
}
