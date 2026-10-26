package org.unitedsoftware.alexandrite.channel.onebot.protocol.event

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.SelfId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneBotEventCodecTest {
    @Test
    fun `a private message event keeps its sender and its message`() {
        val event = assertIs<OneBotEvent.Message.Private>(OneBotEventCodec.decode(privateMessage()))
        assertEquals(1515204254L, event.time)
        assertEquals(SelfId("10001000"), event.selfId)
        assertEquals(MessageId("12"), event.messageId)
        assertEquals(UserId("12345678"), event.userId)
        assertEquals(OneBotEvent.Message.FRIEND, event.subType)
        assertEquals("hello [CQ:at,qq=1]", event.rawMessage)
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("hello "), OneBotSegment.At("1")), event.message.segments)
        assertEquals("someone", event.sender.nickname)
        assertEquals(18L, event.sender.age)
        assertFalse(event.sender.isOperator)
    }

    @Test
    fun `a group message event keeps the group and who sent it`() {
        val raw = base("message") {
            put("message_type", "group")
            put("sub_type", "normal")
            put("message_id", 12)
            put("group_id", 100100)
            put("user_id", 12345678)
            put("message", "hi")
            put("raw_message", "hi")
            putJsonObject("sender") {
                put("nickname", "someone")
                put("card", "the card")
                put("role", "admin")
            }
        }
        val event = assertIs<OneBotEvent.Message.Group>(OneBotEventCodec.decode(raw))
        assertEquals(GroupId("100100"), event.groupId)
        assertEquals("the card", event.sender.card)
        assertTrue(event.sender.isOperator)
        assertFalse(event.sender.isOwner)
        assertNull(event.anonymous)
        assertFalse(event.isAnonymous)
    }

    @Test
    fun `an anonymous group message keeps the flag the ban API takes`() {
        val raw = base("message") {
            put("message_type", "group")
            put("sub_type", "anonymous")
            put("group_id", 100100)
            put("user_id", 12345678)
            put("message", "hi")
            putJsonObject("anonymous") {
                put("id", 1)
                put("name", "no one")
                put("flag", "abc")
            }
        }
        val event = assertIs<OneBotEvent.Message.Group>(OneBotEventCodec.decode(raw))
        assertTrue(event.isAnonymous)
        assertEquals("abc", event.anonymous?.flag)
        assertEquals("no one", event.anonymous?.name)
    }

    @Test
    fun `an unknown field of an implementation stays in the raw event`() {
        val raw = base("message") {
            put("message_type", "private")
            put("sub_type", "friend")
            put("message_id", 12)
            put("user_id", 12345678)
            put("message", "hi")
            put("napcat_extra", "kept")
        }
        val event = OneBotEventCodec.decode(raw)
        assertEquals("kept", event.raw["napcat_extra"]?.let { assertIs<JsonPrimitive>(it).content })
        assertEquals(raw, event.raw)
    }

    @Test
    fun `every notice the standard lists decodes to its own type`() {
        val group = { block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit ->
            base("notice") {
                put("group_id", 100100)
                put("user_id", 12345678)
                block()
            }
        }

        val upload =
            group {
                put("notice_type", "group_upload")
                putJsonObject("file") {
                    put("id", "f")
                    put("name", "n")
                    put("size", 3)
                    put("busid", 1)
                }
            }
        val uploadEvent = assertIs<OneBotEvent.Notice.GroupUpload>(OneBotEventCodec.decode(upload))
        assertEquals("f", uploadEvent.file.id)
        assertEquals(3L, uploadEvent.file.size)

        val admin = group {
            put("notice_type", "group_admin")
            put("sub_type", "set")
        }
        assertTrue(assertIs<OneBotEvent.Notice.GroupAdmin>(OneBotEventCodec.decode(admin)).isSet)

        val decrease = group {
            put("notice_type", "group_decrease")
            put("sub_type", "kick")
            put("operator_id", 1)
        }
        assertEquals("kick", assertIs<OneBotEvent.Notice.GroupDecrease>(OneBotEventCodec.decode(decrease)).subType)

        val increase = group {
            put("notice_type", "group_increase")
            put("sub_type", "approve")
            put("operator_id", 1)
        }
        assertEquals("approve", assertIs<OneBotEvent.Notice.GroupIncrease>(OneBotEventCodec.decode(increase)).subType)

        val ban =
            group {
                put("notice_type", "group_ban")
                put("sub_type", "ban")
                put("operator_id", 1)
                put("duration", 60)
            }
        val banEvent = assertIs<OneBotEvent.Notice.GroupBan>(OneBotEventCodec.decode(ban))
        assertTrue(banEvent.isBan)
        assertEquals(60L, banEvent.duration)

        val lift = group {
            put("notice_type", "group_ban")
            put("sub_type", "lift_ban")
            put("operator_id", 1)
        }
        assertFalse(assertIs<OneBotEvent.Notice.GroupBan>(OneBotEventCodec.decode(lift)).isBan)

        val friend = base("notice") {
            put("notice_type", "friend_add")
            put("user_id", 1)
        }
        assertEquals(UserId("1"), assertIs<OneBotEvent.Notice.FriendAdd>(OneBotEventCodec.decode(friend)).userId)

        val recall = group {
            put("notice_type", "group_recall")
            put("operator_id", 1)
            put("message_id", 7)
        }
        assertEquals(
            MessageId("7"),
            assertIs<OneBotEvent.Notice.GroupRecall>(OneBotEventCodec.decode(recall)).messageId,
        )

        val friendRecall =
            base("notice") {
                put("notice_type", "friend_recall")
                put("user_id", 1)
                put("message_id", 7)
            }
        assertEquals(
            MessageId("7"),
            assertIs<OneBotEvent.Notice.FriendRecall>(OneBotEventCodec.decode(friendRecall)).messageId,
        )

        val poke = group {
            put("notice_type", "notify")
            put("sub_type", "poke")
            put("target_id", 2)
        }
        val pokeEvent = assertIs<OneBotEvent.Notice.Notify>(OneBotEventCodec.decode(poke))
        assertEquals(OneBotEvent.Notice.POKE, pokeEvent.subType)
        assertEquals(UserId("2"), pokeEvent.targetId)
        assertNull(pokeEvent.honorType)

        val king = group {
            put("notice_type", "notify")
            put("sub_type", "lucky_king")
            put("target_id", 2)
        }
        assertEquals(
            OneBotEvent.Notice.LUCKY_KING,
            assertIs<OneBotEvent.Notice.Notify>(OneBotEventCodec.decode(king)).subType,
        )

        val honor = group {
            put("notice_type", "notify")
            put("sub_type", "honor")
            put("honor_type", "talkative")
        }
        assertEquals("talkative", assertIs<OneBotEvent.Notice.Notify>(OneBotEventCodec.decode(honor)).honorType)

        val other = group {
            put("notice_type", "napcat_notice")
            put("sub_type", "x")
        }
        val otherEvent = assertIs<OneBotEvent.Notice.Other>(OneBotEventCodec.decode(other))
        assertEquals("napcat_notice", otherEvent.noticeType)
        assertEquals("x", otherEvent.subType)
    }

    @Test
    fun `every request the standard lists decodes to its own type`() {
        val friend = base("request") {
            put("request_type", "friend")
            put("user_id", 1)
            put("comment", "hi")
            put("flag", "f")
        }
        val friendEvent = assertIs<OneBotEvent.Request.Friend>(OneBotEventCodec.decode(friend))
        assertEquals("f", friendEvent.flag)
        assertEquals("hi", friendEvent.comment)
        assertEquals(setOf("approve", "remark", "reason"), friendEvent.quickOperations)

        val add = base("request") {
            put("request_type", "group")
            put("sub_type", "add")
            put("group_id", 100100)
            put("user_id", 1)
            put("flag", "f")
        }
        val addEvent = assertIs<OneBotEvent.Request.Group>(OneBotEventCodec.decode(add))
        assertEquals(GroupId("100100"), addEvent.groupId)
        assertEquals(OneBotEvent.Request.ADD, addEvent.subType)

        val invite =
            base("request") {
                put("request_type", "group")
                put("sub_type", "invite")
                put("group_id", 1)
                put("user_id", 1)
            }
        assertEquals(
            OneBotEvent.Request.INVITE,
            assertIs<OneBotEvent.Request.Group>(OneBotEventCodec.decode(invite)).subType,
        )

        val other = base("request") {
            put("request_type", "napcat_request")
            put("user_id", 1)
            put("flag", "f")
        }
        assertEquals("napcat_request", assertIs<OneBotEvent.Request.Other>(OneBotEventCodec.decode(other)).requestType)
    }

    @Test
    fun `every meta event the standard lists decodes to its own type`() {
        val lifecycle = base("meta_event") {
            put("meta_event_type", "lifecycle")
            put("sub_type", "connect")
        }
        assertEquals(
            OneBotEvent.Meta.CONNECT,
            assertIs<OneBotEvent.Meta.Lifecycle>(OneBotEventCodec.decode(lifecycle)).subType,
        )

        val heartbeat = base("meta_event") {
            put("meta_event_type", "heartbeat")
            putJsonObject("status") {
                put("online", true)
                put("good", true)
            }
            put("interval", 15000)
        }
        val heartbeatEvent = assertIs<OneBotEvent.Meta.Heartbeat>(OneBotEventCodec.decode(heartbeat))
        assertEquals(15000L, heartbeatEvent.interval)
        assertEquals(JsonPrimitive(true), heartbeatEvent.status["online"])

        val other = base("meta_event") { put("meta_event_type", "napcat_meta") }
        assertEquals("napcat_meta", assertIs<OneBotEvent.Meta.Other>(OneBotEventCodec.decode(other)).metaEventType)
    }

    @Test
    fun `an event of a kind this version does not model keeps its JSON`() {
        val raw = base("napcat_post") { put("whatever", 1) }
        val event = assertIs<OneBotEvent.Unknown>(OneBotEventCodec.decode(raw))
        assertEquals("napcat_post", event.postType)
        assertEquals(raw, event.raw)
    }

    @Test
    fun `a message event of a type this version does not model stays unknown`() {
        val raw = base("message") {
            put("message_type", "channel")
            put("message", "hi")
        }
        val event = assertIs<OneBotEvent.Unknown>(OneBotEventCodec.decode(raw))
        // `postType` is the top-level `post_type`, not the subtype this version cannot read, which stays in the raw
        // JSON: a consumer that asks what kind of event this is has to be told that it is a message.
        assertEquals("message", event.postType)
        assertEquals("channel", (raw["message_type"] as? JsonPrimitive)?.content)
        assertEquals(raw, event.raw)
    }

    @Test
    fun `the quick operations of a group message are the ones the standard lists`() {
        val raw =
            base("message") {
                put("message_type", "group")
                put("group_id", 1)
                put("user_id", 1)
                put("message", "hi")
            }
        val event = assertIs<OneBotEvent.Message.Group>(OneBotEventCodec.decode(raw))
        assertEquals(
            setOf("reply", "auto_escape", "at_sender", "delete", "kick", "ban", "ban_duration"),
            event.quickOperations,
        )
    }

    @Test
    fun `a string message and an array message decode the same way`() {
        val asString =
            base("message") {
                put("message_type", "private")
                put("user_id", 1)
                put("message", "hi [CQ:at,qq=2]")
            }
        val asArray = base("message") {
            put("message_type", "private")
            put("user_id", 1)
            putJsonArray("message") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        putJsonObject("data") { put("text", "hi ") }
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "at")
                        putJsonObject("data") { put("qq", "2") }
                    },
                )
            }
        }
        val expected = listOf<OneBotSegment>(OneBotSegment.Text("hi "), OneBotSegment.At("2"))
        assertEquals(
            expected,
            assertIs<OneBotEvent.Message.Private>(OneBotEventCodec.decode(asString)).message.segments,
        )
        assertEquals(
            expected,
            assertIs<OneBotEvent.Message.Private>(OneBotEventCodec.decode(asArray)).message.segments,
        )
        assertEquals(OneBotMessage.StringValue("hi [CQ:at,qq=2]").segments, expected)
    }

    private fun base(postType: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("time", 1515204254L)
            put("self_id", 10001000L)
            put("post_type", postType)
            block()
        }

    private fun privateMessage(): JsonObject = base("message") {
        put("message_type", "private")
        put("sub_type", "friend")
        put("message_id", 12)
        put("user_id", 12345678)
        put("message", "hello [CQ:at,qq=1]")
        put("raw_message", "hello [CQ:at,qq=1]")
        put("font", 456)
        putJsonObject("sender") {
            put("user_id", 12345678)
            put("nickname", "someone")
            put("sex", "male")
            put("age", 18)
        }
    }
}
