package org.unitedsoftware.alexandrite.channel.onebot.mapping

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.SelfId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.sdk.channel.ChannelNoticeKind
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a report that is not a message becomes. */
class OneBotNoticesTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")
    private val bot = SelfId("10001000")
    private val group = GroupId("987654")
    private val member = UserId("2222")
    private val other = UserId("3333")

    @Test
    fun `a member joining names who joined and who let them in`() {
        val notice = notice(
            OneBotEvent.Notice.GroupIncrease(
                time = 1515204254,
                selfId = bot,
                groupId = group,
                userId = member,
                operatorId = other,
                subType = OneBotEvent.Notice.APPROVE,
                raw = JsonObject(emptyMap()),
            ),
        )
        assertEquals(ChannelNoticeKind.MEMBER_JOINED, notice.kind)
        assertEquals("group:987654", notice.chat.chat)
        assertEquals(other.value, notice.actor?.address?.user)
        assertEquals(member.value, notice.target?.address?.user)
        assertEquals(listOf("how" to "approve"), facts(notice))
    }

    @Test
    fun `a member who joined on their own names no actor`() {
        val notice = notice(
            OneBotEvent.Notice.GroupIncrease(1515204254, bot, group, member, member, null, JsonObject(emptyMap())),
        )
        assertNull(notice.actor)
        assertEquals(member.value, notice.target?.address?.user)
    }

    @Test
    fun `a member leaving by a kick names the one who kicked`() {
        val notice = notice(
            OneBotEvent.Notice.GroupDecrease(
                1515204254,
                bot,
                group,
                member,
                other,
                OneBotEvent.Notice.KICK,
                JsonObject(emptyMap()),
            ),
        )
        assertEquals(ChannelNoticeKind.MEMBER_LEFT, notice.kind)
        assertEquals(other.value, notice.actor?.address?.user)
        assertEquals(member.value, notice.target?.address?.user)
    }

    @Test
    fun `a mute says how long it lasts`() {
        val notice = notice(
            OneBotEvent.Notice.GroupBan(1515204254, bot, group, member, other, true, 600, null, JsonObject(emptyMap())),
        )
        assertEquals(ChannelNoticeKind.MEMBER_BANNED, notice.kind)
        assertEquals(listOf("muted" to "yes", "seconds" to "600"), facts(notice))
    }

    @Test
    fun `an administrator set says which way it went`() {
        val notice = notice(
            OneBotEvent.Notice.GroupAdmin(1515204254, bot, group, member, false, JsonObject(emptyMap())),
        )
        assertEquals(ChannelNoticeKind.ROLE_CHANGED, notice.kind)
        assertEquals(listOf("administrator" to "no"), facts(notice))
    }

    @Test
    fun `a recall names the message that was taken back`() {
        val notice = notice(
            OneBotEvent.Notice.GroupRecall(
                1515204254,
                bot,
                group,
                member,
                other,
                MessageId("77"),
                null,
                JsonObject(emptyMap()),
            ),
        )
        assertEquals(ChannelNoticeKind.MESSAGE_RECALLED, notice.kind)
        assertEquals("77", notice.message?.id)
        assertEquals(other.value, notice.actor?.address?.user)
        assertEquals(member.value, notice.target?.address?.user)
    }

    @Test
    fun `a friend recalled in a direct chat`() {
        val notice = notice(
            OneBotEvent.Notice.FriendRecall(1515204254, bot, member, MessageId("88"), null, JsonObject(emptyMap())),
        )
        assertEquals(ChannelNoticeKind.MESSAGE_RECALLED, notice.kind)
        assertEquals("private:2222", notice.chat.chat)
        assertEquals("88", notice.message?.id)
    }

    @Test
    fun `a new friend is said in the direct chat of that friend`() {
        val notice = notice(
            OneBotEvent.Notice.FriendAdd(1515204254, bot, member, null, JsonObject(emptyMap())),
        )
        assertEquals(ChannelNoticeKind.FRIEND_ADDED, notice.kind)
        assertEquals("private:2222", notice.chat.chat)
    }

    @Test
    fun `a friend request carries what was said and what answers it`() {
        val notice = notice(
            OneBotEvent.Request.Friend(1515204254, bot, "flag-1", "hello", member, JsonObject(emptyMap())),
        )
        assertEquals(ChannelNoticeKind.FRIEND_REQUESTED, notice.kind)
        assertEquals("private:2222", notice.chat.chat)
        assertEquals(listOf("answer with" to "flag-1", "said" to "hello"), facts(notice))
    }

    @Test
    fun `a request to join a group is said in that group`() {
        val notice = notice(
            OneBotEvent.Request.Group(
                1515204254,
                bot,
                "flag-2",
                "let me in",
                member,
                group,
                OneBotEvent.Request.INVITE,
                JsonObject(emptyMap()),
            ),
        )
        assertEquals(ChannelNoticeKind.JOIN_REQUESTED, notice.kind)
        assertEquals("group:987654", notice.chat.chat)
        assertEquals(listOf("answer with" to "flag-2", "said" to "let me in", "how" to "invite"), facts(notice))
    }

    @Test
    fun `a poke says who was poked`() {
        val notice = notice(
            OneBotEvent.Notice.Notify(
                1515204254,
                bot,
                group,
                member,
                other,
                OneBotEvent.Notice.POKE,
                null,
                JsonObject(emptyMap()),
            ),
        )
        assertEquals("notice.poke", notice.kind.id)
        assertEquals(member.value, notice.actor?.address?.user)
        assertEquals(other.value, notice.target?.address?.user)
    }

    @Test
    fun `an upload says which file and how large`() {
        val notice = notice(
            OneBotEvent.Notice.GroupUpload(
                1515204254,
                bot,
                group,
                member,
                OneBotEvent.Notice.File("f1", "notes.zip", 2048, null),
                JsonObject(emptyMap()),
            ),
        )
        assertEquals("group.file_uploaded", notice.kind.id)
        assertEquals(listOf("file" to "notes.zip", "bytes" to "2048"), facts(notice))
    }

    @Test
    fun `a notice this version does not model keeps the name the implementation gave it`() {
        val notice = notice(
            OneBotEvent.Notice.Other(
                time = 1515204254,
                selfId = bot,
                noticeType = "group_msg_emoji_like",
                raw = kotlinx.serialization.json.buildJsonObject {
                    put("group_id", kotlinx.serialization.json.JsonPrimitive(987654))
                    put("user_id", kotlinx.serialization.json.JsonPrimitive(2222))
                },
                subType = null,
            ),
        )
        assertEquals("group_msg_emoji_like", notice.kind.id)
        assertEquals("group:987654", notice.chat.chat)
        assertEquals(member.value, notice.actor?.address?.user)
    }

    @Test
    fun `a notice this version does not model and that names no chat is published as nothing`() {
        val notice = OneBotNotices.of(
            instance,
            OneBotEvent.Notice.Other(1515204254, bot, "something_new", JsonObject(emptyMap()), null),
            bot.value,
        )
        assertNull(notice)
    }

    @Test
    fun `a report that is not a notice or a request is published as nothing`() {
        val heartbeat = OneBotEvent.Meta.Lifecycle(
            time = 1515204254,
            selfId = bot,
            subType = "connect",
            raw = JsonObject(emptyMap()),
        )
        assertNull(OneBotNotices.of(instance, heartbeat, bot.value))
    }

    /** The notice a report of [event] becomes, which the test expects one of. */
    private fun notice(event: OneBotEvent): org.unitedsoftware.alexandrite.sdk.channel.ChannelNotice =
        kotlin.test.assertNotNull(OneBotNotices.of(instance, event, bot.value), "no notice was published")

    /** The facts of [notice] as pairs of label and value. */
    private fun facts(notice: org.unitedsoftware.alexandrite.sdk.channel.ChannelNotice): List<Pair<String, String>> =
        notice.facts.map { it.label to it.value }

    @Test
    fun `the notice of a group names the group chat`() {
        val notice = notice(
            OneBotEvent.Notice.GroupAdmin(1515204254, bot, group, member, true, JsonObject(emptyMap())),
        )
        assertTrue(notice.chat.chat.startsWith("group:"))
    }
}
