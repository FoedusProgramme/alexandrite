package org.unitedsoftware.alexandrite.channel.onebot.protocol.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId

/**
 * The answer `get_msg` gives.
 *
 * Fields an implementation adds are not kept here; a caller that needs one reads the raw response the result
 * carries.
 */
@Serializable
public class OneBotMessageInfo(
    @SerialName("time") public val time: Long? = null,
    @SerialName("message_type") public val messageType: String? = null,
    @SerialName("message_id") public val messageId: MessageId? = null,
    @SerialName("real_id") public val realId: MessageId? = null,
    @SerialName("sender") public val sender: OneBotSenderInfo? = null,
    @SerialName("message") public val message: kotlinx.serialization.json.JsonElement? = null,
)

/** The sender fields `get_msg` and the message events share. */
@Serializable
public class OneBotSenderInfo(
    @SerialName("user_id") public val userId: UserId? = null,
    @SerialName("nickname") public val nickname: String? = null,
    @SerialName("sex") public val sex: String? = null,
    @SerialName("age") public val age: Int? = null,
    @SerialName("card") public val card: String? = null,
    @SerialName("area") public val area: String? = null,
    @SerialName("level") public val level: String? = null,
    @SerialName("role") public val role: String? = null,
    @SerialName("title") public val title: String? = null,
)

/** One friend, as `get_friend_list` reports it. */
@Serializable
public class OneBotFriend(
    @SerialName("user_id") public val userId: UserId,
    @SerialName("nickname") public val nickname: String = "",
    @SerialName("remark") public val remark: String? = null,
)

/** A stranger, as `get_stranger_info` reports them. */
@Serializable
public class OneBotStrangerInfo(
    @SerialName("user_id") public val userId: UserId? = null,
    @SerialName("nickname") public val nickname: String? = null,
    @SerialName("sex") public val sex: String? = null,
    @SerialName("age") public val age: Int? = null,
)

/** A group, as `get_group_info` and `get_group_list` report it. */
@Serializable
public class OneBotGroupInfo(
    @SerialName("group_id") public val groupId: GroupId? = null,
    @SerialName("group_name") public val groupName: String? = null,
    @SerialName("member_count") public val memberCount: Int? = null,
    @SerialName("max_member_count") public val maxMemberCount: Int? = null,
)

/** One group member, as `get_group_member_info` and `get_group_member_list` report them. */
@Serializable
public class OneBotGroupMemberInfo(
    @SerialName("group_id") public val groupId: GroupId? = null,
    @SerialName("user_id") public val userId: UserId? = null,
    @SerialName("nickname") public val nickname: String? = null,
    @SerialName("card") public val card: String? = null,
    @SerialName("sex") public val sex: String? = null,
    @SerialName("age") public val age: Int? = null,
    @SerialName("area") public val area: String? = null,
    @SerialName("join_time") public val joinTime: Long? = null,
    @SerialName("last_sent_time") public val lastSentTime: Long? = null,
    @SerialName("level") public val level: String? = null,
    @SerialName("role") public val role: String? = null,
    @SerialName("unfriendly") public val unfriendly: Boolean? = null,
    @SerialName("title") public val title: String? = null,
    @SerialName("title_expire_time") public val titleExpireTime: Long? = null,
    @SerialName("card_changeable") public val cardChangeable: Boolean? = null,
)

/** The honors of a group, as `get_group_honor_info` reports them. */
@Serializable
public class OneBotGroupHonorInfo(
    @SerialName("group_id") public val groupId: GroupId? = null,
    @SerialName("current_talkative") public val currentTalkative: OneBotTalkative? = null,
    @SerialName("talkative_list") public val talkativeList: List<OneBotHonor> = emptyList(),
    @SerialName("performer_list") public val performerList: List<OneBotHonor> = emptyList(),
    @SerialName("legend_list") public val legendList: List<OneBotHonor> = emptyList(),
    @SerialName("strong_newbie_list") public val strongNewbieList: List<OneBotHonor> = emptyList(),
    @SerialName("emotion_list") public val emotionList: List<OneBotHonor> = emptyList(),
)

/** The current 龙王 of a group. */
@Serializable
public class OneBotTalkative(
    @SerialName("user_id") public val userId: UserId? = null,
    @SerialName("nickname") public val nickname: String? = null,
    @SerialName("avatar") public val avatar: String? = null,
    @SerialName("day_count") public val dayCount: Int? = null,
)

/** One entry of a group's honor lists. */
@Serializable
public class OneBotHonor(
    @SerialName("user_id") public val userId: UserId? = null,
    @SerialName("nickname") public val nickname: String? = null,
    @SerialName("avatar") public val avatar: String? = null,
    @SerialName("description") public val description: String? = null,
)

/** The cookies of a domain. */
@Serializable
public class OneBotCookies(@SerialName("cookies") public val cookies: String = "")

/** A CSRF token. */
@Serializable
public class OneBotCsrfToken(@SerialName("token") public val token: Long? = null)

/** The cookies and CSRF token of a domain. */
@Serializable
public class OneBotCredentials(
    @SerialName("cookies") public val cookies: String = "",
    @SerialName("csrf_token") public val csrfToken: Long? = null,
)

/** The path of a file an implementation converted or downloaded. */
@Serializable
public class OneBotFile(@SerialName("file") public val file: String = "")

/** Whether an implementation can send a kind of media. */
@Serializable
public class OneBotYesNo(@SerialName("yes") public val yes: Boolean = false)

/** The status of an implementation, whose extra fields stay in the raw response. */
@Serializable
public class OneBotStatusInfo(
    /** Whether the account is online, null when the implementation cannot tell. */
    @SerialName("online") public val online: Boolean? = null,
    /** Whether every module of the implementation works and the account is online. */
    @SerialName("good") public val good: Boolean? = null,
)

/** What an implementation says it is. */
@Serializable
public class OneBotVersionInfo(
    @SerialName("app_name") public val appName: String? = null,
    @SerialName("app_version") public val appVersion: String? = null,
    @SerialName("protocol_version") public val protocolVersion: String? = null,
)

/** The login of an implementation. */
@Serializable
public class OneBotLoginInfo(
    @SerialName("user_id") public val userId: UserId? = null,
    @SerialName("nickname") public val nickname: String? = null,
)

/** The data of a message that an implementation sent. */
@Serializable
public class OneBotMessageSent(@SerialName("message_id") public val messageId: MessageId? = null)

/** The result of `get_forward_msg`, whose nodes are the segments of the message. */
@Serializable
public class OneBotForwardMessage(
    @SerialName("message") public val message: kotlinx.serialization.json.JsonElement? = null,
)
