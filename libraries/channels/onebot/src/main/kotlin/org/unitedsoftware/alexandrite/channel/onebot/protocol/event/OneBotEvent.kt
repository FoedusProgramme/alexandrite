package org.unitedsoftware.alexandrite.channel.onebot.protocol.event

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.SelfId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotMessage

/**
 * One event an OneBot implementation reported.
 *
 * Every event carries the fields the standard gives every event, and [raw] keeps the whole JSON object, so a
 * consumer reads a field of an implementation's own extension without this module knowing it.
 */
public sealed interface OneBotEvent {
    /** When the event happened, in seconds since the epoch. */
    public val time: Long

    /** The QQ number the implementation reported the event for. */
    public val selfId: SelfId

    /** The `post_type` of the event. */
    public val postType: String

    /** The whole event object as the implementation sent it. */
    public val raw: JsonObject

    /** The quick operation fields this event accepts. */
    public val quickOperations: Set<String>

    /** A message event, private or in a group. */
    public sealed interface Message : OneBotEvent {
        /** The message. */
        public val message: OneBotMessage

        /** The message as the implementation spelled it, with its CQ codes. */
        public val rawMessage: String

        public val messageId: MessageId

        public val userId: UserId

        /** The sender, as far as the implementation could tell. */
        public val sender: Sender

        /** How the message arrived. */
        public val subType: String

        /** A private message. */
        public data class Private(
            override val time: Long,
            override val selfId: SelfId,
            override val message: OneBotMessage,
            override val rawMessage: String,
            override val messageId: MessageId,
            override val userId: UserId,
            override val sender: Sender,
            override val subType: String,
            override val raw: JsonObject,
        ) : Message {
            override val postType: String get() = POST_TYPE
            override val quickOperations: Set<String> get() = REPLY
        }

        /** A group message. */
        public data class Group(
            override val time: Long,
            override val selfId: SelfId,
            override val message: OneBotMessage,
            override val rawMessage: String,
            override val messageId: MessageId,
            override val userId: UserId,
            override val sender: Sender,
            override val subType: String,
            public val groupId: GroupId,
            /** Who sent the message without a name, null when it carried a name. */
            public val anonymous: Anonymous?,
            override val raw: JsonObject,
        ) : Message {
            override val postType: String get() = POST_TYPE
            override val quickOperations: Set<String> get() = GROUP_OPERATIONS

            /** Whether the message was sent without a name. */
            public val isAnonymous: Boolean get() = anonymous != null
        }

        public companion object {
            public const val POST_TYPE: String = "message"

            /** The subtypes a private message reports. */
            public const val FRIEND: String = "friend"
            public const val GROUP_TEMPORARY: String = "group"
            public const val OTHER: String = "other"

            /** The subtypes a group message reports. */
            public const val NORMAL: String = "normal"
            public const val ANONYMOUS: String = "anonymous"
            public const val NOTICE: String = "notice"

            private val REPLY: Set<String> = setOf("reply", "auto_escape")

            private val GROUP_OPERATIONS: Set<String> =
                REPLY + setOf("at_sender", "delete", "kick", "ban", "ban_duration")
        }
    }

    /** A notification, such as a membership change or a recall. */
    public sealed interface Notice : OneBotEvent {
        /** A notification type. */
        public val noticeType: String

        /** A file uploaded to a group. */
        public data class GroupUpload(
            override val time: Long,
            override val selfId: SelfId,
            public val groupId: GroupId,
            public val userId: UserId,
            public val file: File,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = GROUP_UPLOAD
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** An administrator set or unset. */
        public data class GroupAdmin(
            override val time: Long,
            override val selfId: SelfId,
            public val groupId: GroupId,
            public val userId: UserId,
            /** Whether the member became an administrator. */
            public val isSet: Boolean,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = GROUP_ADMIN
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A member left a group. */
        public data class GroupDecrease(
            override val time: Long,
            override val selfId: SelfId,
            public val groupId: GroupId,
            /** Who left. */
            public val userId: UserId,
            /** Who made them leave, the same as [userId] when they left on their own. */
            public val operatorId: UserId,
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = GROUP_DECREASE
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A member joined a group. */
        public data class GroupIncrease(
            override val time: Long,
            override val selfId: SelfId,
            public val groupId: GroupId,
            public val userId: UserId,
            public val operatorId: UserId,
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = GROUP_INCREASE
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A member was muted or unmuted. */
        public data class GroupBan(
            override val time: Long,
            override val selfId: SelfId,
            public val groupId: GroupId,
            public val userId: UserId,
            public val operatorId: UserId,
            /** Whether the member was muted. */
            public val isBan: Boolean,
            /** How long the mute lasts, in seconds. */
            public val duration: Long?,
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = GROUP_BAN
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** The bot got a new friend. */
        public data class FriendAdd(
            override val time: Long,
            override val selfId: SelfId,
            public val userId: UserId,
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = FRIEND_ADD
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A group message was recalled. */
        public data class GroupRecall(
            override val time: Long,
            override val selfId: SelfId,
            public val groupId: GroupId,
            public val userId: UserId,
            public val operatorId: UserId,
            public val messageId: MessageId,
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = GROUP_RECALL
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A private message was recalled. */
        public data class FriendRecall(
            override val time: Long,
            override val selfId: SelfId,
            public val userId: UserId,
            public val messageId: MessageId,
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = FRIEND_RECALL
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A poke, a lucky king or a member honor, which all report the `notify` notice type. */
        public data class Notify(
            override val time: Long,
            override val selfId: SelfId,
            public val groupId: GroupId?,
            public val userId: UserId,
            /** The member a poke or a lucky king names, null for a honor. */
            public val targetId: UserId?,
            val subType: String? = null,
            /** The honor kind, null unless the subtype is `honor`. */
            public val honorType: String?,
            override val raw: JsonObject,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val noticeType: String get() = NOTIFY
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A notification of a type this version does not model. */
        public data class Other(
            override val time: Long,
            override val selfId: SelfId,
            override val noticeType: String,
            override val raw: JsonObject,
            val subType: String? = null,
        ) : Notice {
            override val postType: String get() = POST_TYPE
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** The file a [GroupUpload] reported. */
        public data class File(
            public val id: String,
            public val name: String,
            /** The size in bytes. */
            public val size: Long?,
            public val busid: Long?,
        )

        public companion object {
            public const val POST_TYPE: String = "notice"

            public const val GROUP_UPLOAD: String = "group_upload"
            public const val GROUP_ADMIN: String = "group_admin"
            public const val GROUP_DECREASE: String = "group_decrease"
            public const val GROUP_INCREASE: String = "group_increase"
            public const val GROUP_BAN: String = "group_ban"
            public const val FRIEND_ADD: String = "friend_add"
            public const val GROUP_RECALL: String = "group_recall"
            public const val FRIEND_RECALL: String = "friend_recall"
            public const val NOTIFY: String = "notify"

            /** The subtypes a [Notify] reports. */
            public const val POKE: String = "poke"
            public const val LUCKY_KING: String = "lucky_king"
            public const val HONOR: String = "honor"

            /** The subtypes a [GroupDecrease] reports. */
            public const val LEAVE: String = "leave"
            public const val KICK: String = "kick"
            public const val KICK_ME: String = "kick_me"

            /** The subtypes a [GroupIncrease] reports. */
            public const val APPROVE: String = "approve"
            public const val INVITE: String = "invite"

            /** The subtypes a [GroupBan] reports. */
            public const val BAN: String = "ban"
            public const val LIFT_BAN: String = "lift_ban"

            /** The subtypes a [GroupAdmin] reports. */
            public const val SET: String = "set"
            public const val UNSET: String = "unset"
        }
    }

    /** A request the bot may answer. */
    public sealed interface Request : OneBotEvent {
        /** The request type. */
        public val requestType: String

        /** The flag the API answers the request with. */
        public val flag: String

        /** The verification message. */
        public val comment: String

        public val userId: UserId

        /** A friend request. */
        public data class Friend(
            override val time: Long,
            override val selfId: SelfId,
            override val flag: String,
            override val comment: String,
            override val userId: UserId,
            override val raw: JsonObject,
        ) : Request {
            override val postType: String get() = POST_TYPE
            override val requestType: String get() = FRIEND
            override val quickOperations: Set<String> get() = OPERATIONS
        }

        /** A request to join a group, or an invitation to one. */
        public data class Group(
            override val time: Long,
            override val selfId: SelfId,
            override val flag: String,
            override val comment: String,
            override val userId: UserId,
            public val groupId: GroupId,
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Request {
            override val postType: String get() = POST_TYPE
            override val requestType: String get() = GROUP
            override val quickOperations: Set<String> get() = OPERATIONS
        }

        /** A request of a type this version does not model. */
        public data class Other(
            override val time: Long,
            override val selfId: SelfId,
            override val requestType: String,
            override val flag: String,
            override val comment: String,
            override val userId: UserId,
            override val raw: JsonObject,
            val subType: String? = null,
        ) : Request {
            override val postType: String get() = POST_TYPE
            override val quickOperations: Set<String> get() = emptySet()
        }

        public companion object {
            public const val POST_TYPE: String = "request"

            public const val FRIEND: String = "friend"
            public const val GROUP: String = "group"

            /** The subtypes a group request reports. */
            public const val ADD: String = "add"
            public const val INVITE: String = "invite"

            private val OPERATIONS: Set<String> = setOf("approve", "remark", "reason")
        }
    }

    /** An event about the OneBot implementation itself. */
    public sealed interface Meta : OneBotEvent {
        /** The meta event type. */
        public val metaEventType: String

        /** The implementation started, stopped or a WebSocket connected. */
        public data class Lifecycle(
            override val time: Long,
            override val selfId: SelfId,
            /** `enable`, `disable` or `connect`. */
            val subType: String? = null,
            override val raw: JsonObject,
        ) : Meta {
            override val postType: String get() = POST_TYPE
            override val metaEventType: String get() = LIFECYCLE
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** The implementation reports how it is doing. */
        public data class Heartbeat(
            override val time: Long,
            override val selfId: SelfId,
            /** The status object, as `get_status` returns it. */
            public val status: JsonObject,
            /** The milliseconds until the next heartbeat. */
            public val interval: Long?,
            override val raw: JsonObject,
        ) : Meta {
            override val postType: String get() = POST_TYPE
            override val metaEventType: String get() = HEARTBEAT
            override val quickOperations: Set<String> get() = emptySet()
        }

        /** A meta event of a type this version does not model. */
        public data class Other(
            override val time: Long,
            override val selfId: SelfId,
            override val metaEventType: String,
            override val raw: JsonObject,
            val subType: String? = null,
        ) : Meta {
            override val postType: String get() = POST_TYPE
            override val quickOperations: Set<String> get() = emptySet()
        }

        public companion object {
            public const val POST_TYPE: String = "meta_event"

            public const val LIFECYCLE: String = "lifecycle"
            public const val HEARTBEAT: String = "heartbeat"

            /** The subtypes a lifecycle event reports. */
            public const val ENABLE: String = "enable"
            public const val DISABLE: String = "disable"
            public const val CONNECT: String = "connect"
        }
    }

    /** An event of a kind this version does not model, with its JSON untouched. */
    public data class Unknown(
        override val time: Long,
        override val selfId: SelfId,
        override val postType: String,
        override val raw: JsonObject,
    ) : OneBotEvent {
        override val quickOperations: Set<String> get() = emptySet()
    }

    /** The sender of a message, as far as an implementation could tell. */
    public data class Sender(
        public val userId: String?,
        public val nickname: String?,
        /** `male`, `female` or `unknown`. */
        public val sex: String?,
        public val age: Long?,
        /** The member's card in the group, null outside a group. */
        public val card: String?,
        /** The member's area, null when the implementation did not report one. */
        public val area: String?,
        /** The member's level, null when the implementation did not report one. */
        public val level: String?,
        /** `owner`, `admin` or `member`, null outside a group. */
        public val role: String?,
        /** The member's special title, null when they hold none. */
        public val title: String?,
    ) {
        /** Whether the sender owns the group. */
        public val isOwner: Boolean get() = role == ROLE_OWNER

        /** Whether the sender administers the group. */
        public val isOperator: Boolean get() = role == ROLE_ADMIN || isOwner

        public companion object {
            public const val ROLE_OWNER: String = "owner"
            public const val ROLE_ADMIN: String = "admin"
            public const val ROLE_MEMBER: String = "member"
        }
    }

    /** Who sent a group message without a name. */
    public data class Anonymous(
        public val id: String?,
        public val name: String?,
        /** The flag the ban API takes. */
        public val flag: String?,
    )
}
