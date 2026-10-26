package org.unitedsoftware.alexandrite.channel.onebot.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.onebot.channel.OneBotLink
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
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotNoParameters
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
import org.unitedsoftware.alexandrite.channel.onebot.transport.OneBotWire
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/**
 * The actions of one connection, sent over whichever transport the instance configured.
 *
 * The answer of an action that returns data is read into the type of the standard; an action this version models as
 * returning nothing answers with [Unit] once the implementation accepted it. An answer whose data does not fit the
 * type is reported as a malformed answer.
 */
@ChannelInstanceScoped
@Binds(OneBotApi::class)
internal class OneBotApiImpl(private val link: OneBotLink) : OneBotApi {
    override suspend fun sendPrivateMsg(request: SendPrivateMessage): OneBotResult<OneBotMessageSent> =
        action("send_private_msg", request, OneBotMessageSent.serializer())

    override suspend fun sendGroupMsg(request: SendGroupMessage): OneBotResult<OneBotMessageSent> =
        action("send_group_msg", request, OneBotMessageSent.serializer())

    override suspend fun sendMsg(request: SendMessage): OneBotResult<OneBotMessageSent> =
        action("send_msg", request, OneBotMessageSent.serializer())

    override suspend fun deleteMsg(request: DeleteMessage): OneBotResult<Unit> = nothing("delete_msg", request)

    override suspend fun getMsg(request: GetMessage): OneBotResult<OneBotMessageInfo> =
        action("get_msg", request, OneBotMessageInfo.serializer())

    override suspend fun getForwardMsg(request: GetForwardMessage): OneBotResult<OneBotForwardMessage> =
        action("get_forward_msg", request, OneBotForwardMessage.serializer())

    override suspend fun sendLike(request: SendLike): OneBotResult<Unit> = nothing("send_like", request)

    override suspend fun setGroupKick(request: SetGroupKick): OneBotResult<Unit> = nothing("set_group_kick", request)

    override suspend fun setGroupBan(request: SetGroupBan): OneBotResult<Unit> = nothing("set_group_ban", request)

    override suspend fun setGroupAnonymousBan(request: SetGroupAnonymousBan): OneBotResult<Unit> =
        nothing("set_group_anonymous_ban", request)

    override suspend fun setGroupWholeBan(request: SetGroupWholeBan): OneBotResult<Unit> =
        nothing("set_group_whole_ban", request)

    override suspend fun setGroupAdmin(request: SetGroupAdmin): OneBotResult<Unit> = nothing("set_group_admin", request)

    override suspend fun setGroupAnonymous(request: SetGroupAnonymous): OneBotResult<Unit> =
        nothing("set_group_anonymous", request)

    override suspend fun setGroupCard(request: SetGroupCard): OneBotResult<Unit> = nothing("set_group_card", request)

    override suspend fun setGroupName(request: SetGroupName): OneBotResult<Unit> = nothing("set_group_name", request)

    override suspend fun setGroupLeave(request: SetGroupLeave): OneBotResult<Unit> = nothing("set_group_leave", request)

    override suspend fun setGroupSpecialTitle(request: SetGroupSpecialTitle): OneBotResult<Unit> =
        nothing("set_group_special_title", request)

    override suspend fun setFriendAddRequest(request: SetFriendAddRequest): OneBotResult<Unit> =
        nothing("set_friend_add_request", request)

    override suspend fun setGroupAddRequest(request: SetGroupAddRequest): OneBotResult<Unit> =
        nothing("set_group_add_request", request)

    override suspend fun getLoginInfo(): OneBotResult<OneBotLoginInfo> =
        action("get_login_info", OneBotNoParameters.GET_LOGIN_INFO, OneBotLoginInfo.serializer())

    override suspend fun getStrangerInfo(request: GetStrangerInfo): OneBotResult<OneBotStrangerInfo> =
        action("get_stranger_info", request, OneBotStrangerInfo.serializer())

    override suspend fun getFriendList(): OneBotResult<List<OneBotFriend>> =
        action("get_friend_list", OneBotNoParameters.GET_FRIEND_LIST, ListSerializer(OneBotFriend.serializer()))

    override suspend fun getGroupInfo(request: GetGroupInfo): OneBotResult<OneBotGroupInfo> =
        action("get_group_info", request, OneBotGroupInfo.serializer())

    override suspend fun getGroupList(): OneBotResult<List<OneBotGroupInfo>> =
        action("get_group_list", OneBotNoParameters.GET_GROUP_LIST, ListSerializer(OneBotGroupInfo.serializer()))

    override suspend fun getGroupMemberInfo(request: GetGroupMemberInfo): OneBotResult<OneBotGroupMemberInfo> =
        action("get_group_member_info", request, OneBotGroupMemberInfo.serializer())

    override suspend fun getGroupMemberList(request: GetGroupMemberList): OneBotResult<List<OneBotGroupMemberInfo>> =
        action("get_group_member_list", request, ListSerializer(OneBotGroupMemberInfo.serializer()))

    override suspend fun getGroupHonorInfo(request: GetGroupHonorInfo): OneBotResult<OneBotGroupHonorInfo> =
        action("get_group_honor_info", request, OneBotGroupHonorInfo.serializer())

    override suspend fun getCookies(request: GetCookies): OneBotResult<OneBotCookies> =
        action("get_cookies", request, OneBotCookies.serializer())

    override suspend fun getCsrfToken(): OneBotResult<OneBotCsrfToken> =
        action("get_csrf_token", OneBotNoParameters.GET_CSRF_TOKEN, OneBotCsrfToken.serializer())

    override suspend fun getCredentials(request: GetCredentials): OneBotResult<OneBotCredentials> =
        action("get_credentials", request, OneBotCredentials.serializer())

    override suspend fun getRecord(request: GetRecord): OneBotResult<OneBotFile> =
        action("get_record", request, OneBotFile.serializer())

    override suspend fun getImage(request: GetImage): OneBotResult<OneBotFile> =
        action("get_image", request, OneBotFile.serializer())

    override suspend fun canSendImage(): OneBotResult<OneBotYesNo> =
        action("can_send_image", OneBotNoParameters.CAN_SEND_IMAGE, OneBotYesNo.serializer())

    override suspend fun canSendRecord(): OneBotResult<OneBotYesNo> =
        action("can_send_record", OneBotNoParameters.CAN_SEND_RECORD, OneBotYesNo.serializer())

    override suspend fun getStatus(): OneBotResult<OneBotStatusInfo> =
        action("get_status", OneBotNoParameters.GET_STATUS, OneBotStatusInfo.serializer())

    override suspend fun getVersionInfo(): OneBotResult<OneBotVersionInfo> =
        action("get_version_info", OneBotNoParameters.GET_VERSION_INFO, OneBotVersionInfo.serializer())

    override suspend fun setRestart(request: SetRestart): OneBotResult<Unit> = nothing("set_restart", request)

    override suspend fun cleanCache(): OneBotResult<Unit> = nothing("clean_cache", OneBotNoParameters.CLEAN_CACHE)

    override suspend fun handleQuickOperation(request: HandleQuickOperation): OneBotResult<Unit> =
        nothing(".handle_quick_operation", request)

    override suspend fun raw(action: String, params: JsonObject): OneBotResult<JsonElement> = link.call(action, params)

    override suspend fun call(
        action: String,
        request: OneBotRequest,
        suffix: OneBotCallSuffix,
    ): OneBotResult<JsonElement> = link.call(action + suffix.suffix, request.toJson())

    /** Runs an action whose data is one object of the standard. */
    private suspend fun <T> action(
        name: String,
        request: OneBotRequest,
        deserializer: DeserializationStrategy<T>,
    ): OneBotResult<T> {
        val answer = link.call(name, request.toJson())
        val data = when (answer) {
            is OneBotResult.Ok -> answer.data
            is OneBotResult.Async -> return answer
            is OneBotResult.Failed -> return answer
            is OneBotResult.Unreachable -> return answer
            is OneBotResult.Malformed -> return answer
        }
        return try {
            OneBotResult.Ok(
                OneBotWire.decodeFromJsonElement(deserializer, data),
                answer.retcode,
                answer.echo,
                answer.raw,
            )
        } catch (e: Exception) {
            // The answer goes along, so that a caller who meets an implementation whose data this version misreads
            // can read the field itself, without rebuilding the call to see it.
            OneBotResult.Malformed(
                "The data of $name does not fit its type: ${e.message}",
                answer.echo,
                answer.raw,
            )
        }
    }

    /** Runs an action of the standard that answers with nothing. */
    private suspend fun nothing(name: String, request: OneBotRequest): OneBotResult<Unit> =
        link.call(name, request.toJson()).asUnit()

    /** Turns an answer into one that carries no data. */
    private fun OneBotResult<JsonElement>.asUnit(): OneBotResult<Unit> = when (this) {
        is OneBotResult.Ok -> OneBotResult.Ok(Unit, retcode, echo, raw)
        is OneBotResult.Async -> OneBotResult.Async(retcode, echo, raw)
        is OneBotResult.Failed -> this
        is OneBotResult.Unreachable -> this
        is OneBotResult.Malformed -> this
    }
}
