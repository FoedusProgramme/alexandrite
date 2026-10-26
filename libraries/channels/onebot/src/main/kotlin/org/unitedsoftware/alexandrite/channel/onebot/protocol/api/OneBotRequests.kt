package org.unitedsoftware.alexandrite.channel.onebot.protocol.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegmentCodec

/**
 * The parameters of one API call.
 *
 * A request writes only the fields it holds, because the standard gives several of them defaults that an
 * implementation applies itself, and writing a default would take that choice away from it.
 */
public sealed interface OneBotRequest {
    /** The parameters of the call. */
    public fun toJson(): JsonObject
}

/** `send_private_msg`: sends a message to one user. */
public class SendPrivateMessage(
    public val userId: UserId,
    public val message: OneBotMessage,
    /** Whether the implementation sends the message as plain text, which only a string message allows. */
    public val autoEscape: Boolean = false,
) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("user_id", OneBotSegmentCodec.number(userId.value))
        put("message", OneBotSegmentCodec.encodeMessage(message))
        // Only a whole message as one piece of text can be taken as it is written, which is what an implementation
        // reads `auto_escape` as. A message of segments is sent as segments, so that the CQ text one of them holds
        // is not read as an escape it never asked for.
        if (autoEscape && message is OneBotMessage.StringValue) put("auto_escape", true)
    }
}

/** `send_group_msg`: sends a message to one group. */
public class SendGroupMessage(
    public val groupId: GroupId,
    public val message: OneBotMessage,
    public val autoEscape: Boolean = false,
) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("message", OneBotSegmentCodec.encodeMessage(message))
        // Only a whole message as one piece of text can be taken as it is written, which is what an implementation
        // reads `auto_escape` as. A message of segments is sent as segments, so that the CQ text one of them holds
        // is not read as an escape it never asked for.
        if (autoEscape && message is OneBotMessage.StringValue) put("auto_escape", true)
    }
}

/** `send_msg`: sends a message, whose chat the implementation takes from whichever id the request holds. */
public class SendMessage(
    public val message: OneBotMessage,
    /** `private` or `group`, null to let the implementation decide from the ids. */
    public val messageType: String? = null,
    public val userId: UserId? = null,
    public val groupId: GroupId? = null,
    public val autoEscape: Boolean = false,
) : OneBotRequest {
    init {
        require(userId != null || groupId != null) { "A message needs a user id or a group id." }
        require(messageType == null || messageType == PRIVATE || messageType == GROUP) {
            "A message type is '$PRIVATE' or '$GROUP', was '$messageType'."
        }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        messageType?.let { put("message_type", it) }
        userId?.let { put("user_id", OneBotSegmentCodec.number(it.value)) }
        groupId?.let { put("group_id", OneBotSegmentCodec.number(it.value)) }
        put("message", OneBotSegmentCodec.encodeMessage(message))
        // Only a whole message as one piece of text can be taken as it is written, which is what an implementation
        // reads `auto_escape` as. A message of segments is sent as segments, so that the CQ text one of them holds
        // is not read as an escape it never asked for.
        if (autoEscape && message is OneBotMessage.StringValue) put("auto_escape", true)
    }

    public companion object {
        public const val PRIVATE: String = "private"
        public const val GROUP: String = "group"
    }
}

/** `delete_msg`: recalls a message. */
public class DeleteMessage(public val messageId: MessageId) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("message_id", OneBotSegmentCodec.number(messageId.value))
    }
}

/** `get_msg`: reads one message. */
public class GetMessage(public val messageId: MessageId) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("message_id", OneBotSegmentCodec.number(messageId.value))
    }
}

/** `get_forward_msg`: reads the nodes of a forwarded message. */
public class GetForwardMessage(public val id: String) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject { put("id", id) }
}

/** `send_like`: gives a friend a thumbs up. */
public class SendLike(public val userId: UserId, public val times: Int = 1) : OneBotRequest {
    init {
        require(times > 0) { "A like is sent at least once, was $times." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("user_id", OneBotSegmentCodec.number(userId.value))
        put("times", times)
    }
}

/** `set_group_kick`: removes a member from a group. */
public class SetGroupKick(
    public val groupId: GroupId,
    public val userId: UserId,
    /** Whether the implementation also refuses that member's pending request to join. */
    public val rejectAddRequest: Boolean = false,
) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("user_id", OneBotSegmentCodec.number(userId.value))
        if (rejectAddRequest) put("reject_add_request", true)
    }
}

/** `set_group_ban`: mutes one member of a group, or lifts their mute with a duration of 0. */
public class SetGroupBan(
    public val groupId: GroupId,
    public val userId: UserId,
    /** The mute lasts this many seconds, 0 lifts it. */
    public val duration: Long = DEFAULT_DURATION,
) : OneBotRequest {
    init {
        require(duration >= 0) { "A mute lasts at least no time, was $duration." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("user_id", OneBotSegmentCodec.number(userId.value))
        put("duration", duration)
    }

    public companion object {
        /** The mute the standard gives a call that names none: 30 minutes. */
        public const val DEFAULT_DURATION: Long = 30L * 60L
    }
}

/** `set_group_anonymous_ban`: mutes one anonymous member of a group. */
public class SetGroupAnonymousBan(
    public val groupId: GroupId,
    /** The flag the message event reported, which identifies the anonymous member. */
    public val flag: String,
    public val duration: Long = SetGroupBan.DEFAULT_DURATION,
) : OneBotRequest {
    init {
        require(flag.isNotBlank()) { "An anonymous ban needs the flag of the anonymous member." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("anonymous_flag", flag)
        put("duration", duration)
    }
}

/** `set_group_whole_ban`: mutes everyone in a group, or lifts it. */
public class SetGroupWholeBan(public val groupId: GroupId, public val enable: Boolean = true) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("enable", enable)
    }
}

/** `set_group_admin`: makes a member of a group an administrator, or takes the role away. */
public class SetGroupAdmin(public val groupId: GroupId, public val userId: UserId, public val enable: Boolean = true) :
    OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("user_id", OneBotSegmentCodec.number(userId.value))
        put("enable", enable)
    }
}

/** `set_group_anonymous`: allows or forbids anonymous messages in a group. */
public class SetGroupAnonymous(public val groupId: GroupId, public val enable: Boolean = true) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("enable", enable)
    }
}

/** `set_group_card`: writes a member's card in a group, or removes it with an empty card. */
public class SetGroupCard(public val groupId: GroupId, public val userId: UserId, public val card: String = "") :
    OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("user_id", OneBotSegmentCodec.number(userId.value))
        put("card", card)
    }
}

/** `set_group_name`: renames a group. */
public class SetGroupName(public val groupId: GroupId, public val groupName: String) : OneBotRequest {
    init {
        require(groupName.isNotBlank()) { "A group name may not be blank." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("group_name", groupName)
    }
}

/** `set_group_leave`: leaves a group, or dismisses it when the account owns it and [isDismiss] is set. */
public class SetGroupLeave(public val groupId: GroupId, public val isDismiss: Boolean = false) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        if (isDismiss) put("is_dismiss", true)
    }
}

/** `set_group_special_title`: writes a member's special title, or removes it with an empty title. */
public class SetGroupSpecialTitle(
    public val groupId: GroupId,
    public val userId: UserId,
    public val specialTitle: String = "",
    /** The title lasts this many seconds, -1 for good. */
    public val duration: Long = PERMANENT,
) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("user_id", OneBotSegmentCodec.number(userId.value))
        put("special_title", specialTitle)
        put("duration", duration)
    }

    public companion object {
        public const val PERMANENT: Long = -1L
    }
}

/** `set_friend_add_request`: answers a friend request. */
public class SetFriendAddRequest(
    /** The flag the request event reported. */
    public val flag: String,
    public val approve: Boolean = true,
    /** The remark to save with a friend the call accepts. */
    public val remark: String = "",
) : OneBotRequest {
    init {
        require(flag.isNotBlank()) { "A request answer needs the flag of the request." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("flag", flag)
        put("approve", approve)
        if (approve && remark.isNotEmpty()) put("remark", remark)
    }
}

/** `set_group_add_request`: answers a request to join a group, or an invitation to one. */
public class SetGroupAddRequest(
    public val flag: String,
    /** `add` or `invite`, which must match the subtype the request event reported. */
    public val subType: String,
    public val approve: Boolean = true,
    /** The reason to give a request the call refuses. */
    public val reason: String = "",
) : OneBotRequest {
    init {
        require(flag.isNotBlank()) { "A request answer needs the flag of the request." }
        require(subType == ADD || subType == INVITE) { "A group request is '$ADD' or '$INVITE', was '$subType'." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("flag", flag)
        put("sub_type", subType)
        put("approve", approve)
        if (!approve && reason.isNotEmpty()) put("reason", reason)
    }

    public companion object {
        public const val ADD: String = "add"
        public const val INVITE: String = "invite"
    }
}

/** `get_stranger_info`: reads a user's information. */
public class GetStrangerInfo(public val userId: UserId, public val noCache: Boolean = false) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("user_id", OneBotSegmentCodec.number(userId.value))
        if (noCache) put("no_cache", true)
    }
}

/** `get_group_info`: reads a group's information. */
public class GetGroupInfo(public val groupId: GroupId, public val noCache: Boolean = false) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        if (noCache) put("no_cache", true)
    }
}

/** `get_group_member_info`: reads one member's information in a group. */
public class GetGroupMemberInfo(
    public val groupId: GroupId,
    public val userId: UserId,
    public val noCache: Boolean = false,
) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("user_id", OneBotSegmentCodec.number(userId.value))
        if (noCache) put("no_cache", true)
    }
}

/** `get_group_member_list`: reads the members of a group. */
public class GetGroupMemberList(public val groupId: GroupId) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject { put("group_id", OneBotSegmentCodec.number(groupId.value)) }
}

/** `get_group_honor_info`: reads the honors of a group. */
public class GetGroupHonorInfo(public val groupId: GroupId, public val type: String = ALL) : OneBotRequest {
    init {
        require(type.isNotEmpty()) { "A honor type may not be empty." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("group_id", OneBotSegmentCodec.number(groupId.value))
        put("type", type)
    }

    public companion object {
        public const val TALKATIVE: String = "talkative"
        public const val PERFORMER: String = "performer"
        public const val LEGEND: String = "legend"
        public const val STRONG_NEWBIE: String = "strong_newbie"
        public const val EMOTION: String = "emotion"
        public const val ALL: String = "all"
    }
}

/** `get_cookies`: reads the cookies of a domain. */
public class GetCookies(public val domain: String = "") : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        if (domain.isNotEmpty()) put("domain", domain)
    }
}

/** `get_credentials`: reads the cookies and the CSRF token of a domain. */
public class GetCredentials(public val domain: String = "") : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        if (domain.isNotEmpty()) put("domain", domain)
    }
}

/** `get_record`: asks the implementation to convert a voice message. */
public class GetRecord(public val file: String, public val outFormat: String) : OneBotRequest {
    init {
        require(file.isNotBlank()) { "A record needs the file the message segment named." }
        require(outFormat.isNotBlank()) { "A record conversion needs its output format." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        put("file", file)
        put("out_format", outFormat)
    }
}

/** `get_image`: asks the implementation to download an image. */
public class GetImage(public val file: String) : OneBotRequest {
    init {
        require(file.isNotBlank()) { "An image needs the file the message segment named." }
    }

    override fun toJson(): JsonObject = buildJsonObject { put("file", file) }
}

/** `set_restart`: asks the implementation to restart itself. */
public class SetRestart(public val delayMillis: Long = 0) : OneBotRequest {
    init {
        require(delayMillis >= 0) { "A restart waits at least no time, was $delayMillis." }
    }

    override fun toJson(): JsonObject = buildJsonObject {
        if (delayMillis > 0) put("delay", delayMillis)
    }
}

/** `.handle_quick_operation`: runs a quick operation as if an event's report had asked for it. */
public class HandleQuickOperation(
    /** The event object, which may name only the fields the operation reads. */
    public val context: JsonObject,
    /** The operation, such as `{"ban": true, "reply": "please stop"}`. */
    public val operation: JsonObject,
) : OneBotRequest {
    override fun toJson(): JsonObject = buildJsonObject {
        put("context", context)
        put("operation", operation)
    }
}

/** The calls that take no parameters at all. */
public enum class OneBotNoParameters : OneBotRequest {
    /** `get_login_info`. */
    GET_LOGIN_INFO,

    /** `get_friend_list`. */
    GET_FRIEND_LIST,

    /** `get_group_list`. */
    GET_GROUP_LIST,

    /** `get_csrf_token`. */
    GET_CSRF_TOKEN,

    /** `can_send_image`. */
    CAN_SEND_IMAGE,

    /** `can_send_record`. */
    CAN_SEND_RECORD,

    /** `get_status`. */
    GET_STATUS,

    /** `get_version_info`. */
    GET_VERSION_INFO,

    /** `clean_cache`. */
    CLEAN_CACHE,
    ;

    override fun toJson(): JsonObject = JsonObject(emptyMap())
}
