package org.unitedsoftware.alexandrite.channel.onebot.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.DeleteMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetCookies
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetCredentials
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetForwardMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetGroupHonorInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetGroupInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetGroupMemberInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetGroupMemberList
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetImage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetRecord
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetStrangerInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.HandleQuickOperation
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotCallSuffix
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotCookies
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotCredentials
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotCsrfToken
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotFile
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotForwardMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotFriend
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotGroupHonorInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotGroupInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotGroupMemberInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotLoginInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotMessageInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotMessageSent
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotRequest
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotStatusInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotStrangerInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotVersionInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotYesNo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SendGroupMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SendLike
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SendMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SendPrivateMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetFriendAddRequest
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupAddRequest
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupAdmin
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupAnonymous
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupAnonymousBan
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupBan
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupCard
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupKick
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupLeave
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupName
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupSpecialTitle
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetGroupWholeBan
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SetRestart
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult

/**
 * The actions of the OneBot v11 API, as the types of the standard.
 *
 * One instance of this interface belongs to one channel instance and lives as long as its connection, so a plugin
 * that holds it calls the implementation that instance talks to, and no other. The calls an implementation answers
 * with data return the parsed type; the rest answer with nothing, and a call this version does not model goes
 * through [raw].
 *
 * Every call returns a [OneBotResult], which keeps the difference between an implementation that refused the call, a
 * peer that could not be reached and a call that worked.
 */
public interface OneBotApi {
    // Messages

    public suspend fun sendPrivateMsg(request: SendPrivateMessage): OneBotResult<OneBotMessageSent>

    public suspend fun sendGroupMsg(request: SendGroupMessage): OneBotResult<OneBotMessageSent>

    public suspend fun sendMsg(request: SendMessage): OneBotResult<OneBotMessageSent>

    public suspend fun deleteMsg(request: DeleteMessage): OneBotResult<Unit>

    public suspend fun getMsg(request: GetMessage): OneBotResult<OneBotMessageInfo>

    public suspend fun getForwardMsg(request: GetForwardMessage): OneBotResult<OneBotForwardMessage>

    // Groups and friends

    public suspend fun sendLike(request: SendLike): OneBotResult<Unit>

    public suspend fun setGroupKick(request: SetGroupKick): OneBotResult<Unit>

    public suspend fun setGroupBan(request: SetGroupBan): OneBotResult<Unit>

    public suspend fun setGroupAnonymousBan(request: SetGroupAnonymousBan): OneBotResult<Unit>

    public suspend fun setGroupWholeBan(request: SetGroupWholeBan): OneBotResult<Unit>

    public suspend fun setGroupAdmin(request: SetGroupAdmin): OneBotResult<Unit>

    public suspend fun setGroupAnonymous(request: SetGroupAnonymous): OneBotResult<Unit>

    public suspend fun setGroupCard(request: SetGroupCard): OneBotResult<Unit>

    public suspend fun setGroupName(request: SetGroupName): OneBotResult<Unit>

    public suspend fun setGroupLeave(request: SetGroupLeave): OneBotResult<Unit>

    public suspend fun setGroupSpecialTitle(request: SetGroupSpecialTitle): OneBotResult<Unit>

    public suspend fun setFriendAddRequest(request: SetFriendAddRequest): OneBotResult<Unit>

    public suspend fun setGroupAddRequest(request: SetGroupAddRequest): OneBotResult<Unit>

    public suspend fun getLoginInfo(): OneBotResult<OneBotLoginInfo>

    public suspend fun getStrangerInfo(request: GetStrangerInfo): OneBotResult<OneBotStrangerInfo>

    public suspend fun getFriendList(): OneBotResult<List<OneBotFriend>>

    public suspend fun getGroupInfo(request: GetGroupInfo): OneBotResult<OneBotGroupInfo>

    public suspend fun getGroupList(): OneBotResult<List<OneBotGroupInfo>>

    public suspend fun getGroupMemberInfo(request: GetGroupMemberInfo): OneBotResult<OneBotGroupMemberInfo>

    public suspend fun getGroupMemberList(request: GetGroupMemberList): OneBotResult<List<OneBotGroupMemberInfo>>

    public suspend fun getGroupHonorInfo(request: GetGroupHonorInfo): OneBotResult<OneBotGroupHonorInfo>

    // The account and its files

    public suspend fun getCookies(request: GetCookies): OneBotResult<OneBotCookies>

    public suspend fun getCsrfToken(): OneBotResult<OneBotCsrfToken>

    public suspend fun getCredentials(request: GetCredentials): OneBotResult<OneBotCredentials>

    public suspend fun getRecord(request: GetRecord): OneBotResult<OneBotFile>

    public suspend fun getImage(request: GetImage): OneBotResult<OneBotFile>

    public suspend fun canSendImage(): OneBotResult<OneBotYesNo>

    public suspend fun canSendRecord(): OneBotResult<OneBotYesNo>

    // The implementation itself

    public suspend fun getStatus(): OneBotResult<OneBotStatusInfo>

    public suspend fun getVersionInfo(): OneBotResult<OneBotVersionInfo>

    public suspend fun setRestart(request: SetRestart): OneBotResult<Unit>

    public suspend fun cleanCache(): OneBotResult<Unit>

    public suspend fun handleQuickOperation(request: HandleQuickOperation): OneBotResult<Unit>

    /** Calls an action of the implementation that this version does not model, with its answer untouched. */
    public suspend fun raw(action: String, params: JsonObject = JsonObject(emptyMap())): OneBotResult<JsonElement>

    /**
     * Calls one action through this version's own request type, under [suffix].
     *
     * The typed methods above call the plain shape of an action. This is the way to a derived one, such as
     * `send_group_msg_async` or `send_group_msg_rate_limited`, without giving up the request type or the answer's
     * `raw` object.
     */
    public suspend fun call(
        action: String,
        request: OneBotRequest,
        suffix: OneBotCallSuffix = OneBotCallSuffix.NONE,
    ): OneBotResult<JsonElement>
}
