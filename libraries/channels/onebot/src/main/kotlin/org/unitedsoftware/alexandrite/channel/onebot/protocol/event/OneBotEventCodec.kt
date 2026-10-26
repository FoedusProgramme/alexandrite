package org.unitedsoftware.alexandrite.channel.onebot.protocol.event

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.SelfId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegmentCodec

/**
 * Reads an event object.
 *
 * The kind of an event is read from `post_type` and then its own type field, the same way the standard separates
 * them. An event whose kind this version does not model becomes [OneBotEvent.Unknown] with its JSON untouched, and
 * so does an event whose kind it models but whose own type it does not.
 */
public object OneBotEventCodec {
    /** The event of [element], which throws when it is no object, an event of the standard being one. */
    public fun decode(element: JsonElement): OneBotEvent {
        // A scalar or an array is no event: making one of it would tell a caller that a report naming nothing is a
        // report worth reading, and would lose what actually arrived.
        val raw = element as? JsonObject
            ?: throw IllegalArgumentException("An event is a JSON object, was ${element::class.simpleName}.")
        val time = raw.number("time") ?: 0L
        val selfId = SelfId(raw.numberText("self_id") ?: "0")
        return when (val postType = raw.string("post_type")) {
            OneBotEvent.Message.POST_TYPE -> decodeMessage(raw, time, selfId)
            OneBotEvent.Notice.POST_TYPE -> decodeNotice(raw, time, selfId)
            OneBotEvent.Request.POST_TYPE -> decodeRequest(raw, time, selfId)
            OneBotEvent.Meta.POST_TYPE -> decodeMeta(raw, time, selfId)
            else -> OneBotEvent.Unknown(time, selfId, postType.orEmpty(), raw)
        }
    }

    private fun decodeMessage(raw: JsonObject, time: Long, selfId: SelfId): OneBotEvent = when (
        val messageType = raw.string("message_type")
    ) {
        "private" -> OneBotEvent.Message.Private(
            time = time,
            selfId = selfId,
            message = message(raw),
            rawMessage = raw.string("raw_message").orEmpty(),
            messageId = MessageId(raw.numberText("message_id") ?: "0"),
            userId = UserId(raw.numberText("user_id") ?: "0"),
            sender = sender(raw),
            subType = raw.string("sub_type").orEmpty(),
            raw = raw,
        )

        "group" -> OneBotEvent.Message.Group(
            time = time,
            selfId = selfId,
            message = message(raw),
            rawMessage = raw.string("raw_message").orEmpty(),
            messageId = MessageId(raw.numberText("message_id") ?: "0"),
            userId = UserId(raw.numberText("user_id") ?: "0"),
            sender = sender(raw),
            subType = raw.string("sub_type").orEmpty(),
            groupId = GroupId(raw.numberText("group_id") ?: "0"),
            anonymous = anonymous(raw),
            raw = raw,
        )

        else -> OneBotEvent.Unknown(time, selfId, OneBotEvent.Message.POST_TYPE, raw)
    }

    private fun decodeNotice(raw: JsonObject, time: Long, selfId: SelfId): OneBotEvent {
        val noticeType = raw.string("notice_type").orEmpty()
        val subType = raw.string("sub_type")
        val groupId = { raw.numberText("group_id")?.let(::GroupId) }
        val userId = { UserId(raw.numberText("user_id") ?: "0") }
        val operatorId = { UserId(raw.numberText("operator_id") ?: "0") }
        return when (noticeType) {
            OneBotEvent.Notice.GROUP_UPLOAD -> OneBotEvent.Notice.GroupUpload(
                time,
                selfId,
                groupId() ?: GroupId("0"),
                userId(),
                file(raw["file"] as? JsonObject ?: JsonObject(emptyMap())),
                raw,
            )

            OneBotEvent.Notice.GROUP_ADMIN -> OneBotEvent.Notice.GroupAdmin(
                time,
                selfId,
                groupId() ?: GroupId("0"),
                userId(),
                subType == OneBotEvent.Notice.SET,
                raw,
            )

            OneBotEvent.Notice.GROUP_DECREASE -> OneBotEvent.Notice.GroupDecrease(
                time,
                selfId,
                groupId() ?: GroupId("0"),
                userId(),
                operatorId(),
                subType.orEmpty(),
                raw,
            )

            OneBotEvent.Notice.GROUP_INCREASE -> OneBotEvent.Notice.GroupIncrease(
                time,
                selfId,
                groupId() ?: GroupId("0"),
                userId(),
                operatorId(),
                subType.orEmpty(),
                raw,
            )

            OneBotEvent.Notice.GROUP_BAN -> OneBotEvent.Notice.GroupBan(
                time = time,
                selfId = selfId,
                groupId = groupId() ?: GroupId("0"),
                userId = userId(),
                operatorId = operatorId(),
                isBan = subType == OneBotEvent.Notice.BAN,
                duration = raw.number("duration"),
                subType = subType,
                raw = raw,
            )

            OneBotEvent.Notice.FRIEND_ADD ->
                OneBotEvent.Notice.FriendAdd(time, selfId, userId(), raw = raw)

            OneBotEvent.Notice.GROUP_RECALL -> OneBotEvent.Notice.GroupRecall(
                time = time,
                selfId = selfId,
                groupId = groupId() ?: GroupId("0"),
                userId = userId(),
                operatorId = operatorId(),
                messageId = MessageId(raw.numberText("message_id") ?: "0"),
                raw = raw,
            )

            OneBotEvent.Notice.FRIEND_RECALL -> OneBotEvent.Notice.FriendRecall(
                time = time,
                selfId = selfId,
                userId = userId(),
                messageId = MessageId(raw.numberText("message_id") ?: "0"),
                raw = raw,
            )

            OneBotEvent.Notice.NOTIFY -> OneBotEvent.Notice.Notify(
                time = time,
                selfId = selfId,
                groupId = groupId(),
                userId = userId(),
                targetId = raw.numberText("target_id")?.let(::UserId),
                subType = subType.orEmpty(),
                honorType = raw.string("honor_type"),
                raw = raw,
            )

            else -> OneBotEvent.Notice.Other(
                time = time,
                selfId = selfId,
                noticeType = noticeType,
                raw = raw,
                subType = subType,
            )
        }
    }

    private fun decodeRequest(raw: JsonObject, time: Long, selfId: SelfId): OneBotEvent {
        val requestType = raw.string("request_type").orEmpty()
        val flag = raw.string("flag").orEmpty()
        val comment = raw.string("comment").orEmpty()
        val userId = UserId(raw.numberText("user_id") ?: "0")
        return when (requestType) {
            OneBotEvent.Request.FRIEND ->
                OneBotEvent.Request.Friend(time, selfId, flag, comment, userId, raw)

            OneBotEvent.Request.GROUP -> OneBotEvent.Request.Group(
                time,
                selfId,
                flag,
                comment,
                userId,
                GroupId(raw.numberText("group_id") ?: "0"),
                raw.string("sub_type").orEmpty(),
                raw,
            )

            else -> OneBotEvent.Request.Other(
                time = time,
                selfId = selfId,
                requestType = requestType,
                flag = flag,
                comment = comment,
                userId = userId,
                raw = raw,
                subType = raw.string("sub_type"),
            )
        }
    }

    private fun decodeMeta(raw: JsonObject, time: Long, selfId: SelfId): OneBotEvent {
        val metaEventType = raw.string("meta_event_type").orEmpty()
        val subType = raw.string("sub_type")
        return when (metaEventType) {
            OneBotEvent.Meta.LIFECYCLE ->
                OneBotEvent.Meta.Lifecycle(time, selfId, subType.orEmpty(), raw)

            OneBotEvent.Meta.HEARTBEAT -> OneBotEvent.Meta.Heartbeat(
                time,
                selfId,
                raw["status"] as? JsonObject ?: JsonObject(emptyMap()),
                raw.number("interval"),
                raw,
            )

            else -> OneBotEvent.Meta.Other(
                time = time,
                selfId = selfId,
                metaEventType = metaEventType,
                raw = raw,
                subType = subType,
            )
        }
    }

    private fun message(raw: JsonObject): OneBotMessage = OneBotSegmentCodec.decodeMessage(raw["message"] ?: JsonNull)

    private fun sender(raw: JsonObject): OneBotEvent.Sender {
        val sender = raw["sender"] as? JsonObject ?: JsonObject(emptyMap())
        return OneBotEvent.Sender(
            userId = sender.numberText("user_id"),
            nickname = sender.string("nickname"),
            sex = sender.string("sex"),
            age = sender.number("age"),
            card = sender.string("card"),
            area = sender.string("area"),
            level = sender.string("level"),
            role = sender.string("role"),
            title = sender.string("title"),
        )
    }

    private fun anonymous(raw: JsonObject): OneBotEvent.Anonymous? {
        val anonymous = raw["anonymous"] as? JsonObject ?: return null
        return OneBotEvent.Anonymous(
            id = anonymous.numberText("id"),
            name = anonymous.string("name"),
            flag = anonymous.string("flag"),
        )
    }

    private fun file(raw: JsonObject): OneBotEvent.Notice.File = OneBotEvent.Notice.File(
        id = raw.string("id").orEmpty(),
        name = raw.string("name").orEmpty(),
        size = raw.number("size"),
        busid = raw.number("busid"),
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.numberText(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
}
