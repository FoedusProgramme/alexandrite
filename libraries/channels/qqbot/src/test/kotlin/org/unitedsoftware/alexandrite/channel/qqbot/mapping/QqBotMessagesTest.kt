package org.unitedsoftware.alexandrite.channel.qqbot.mapping

import org.unitedsoftware.alexandrite.channel.qqbot.channel.QqBotMessageIndex
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QqBotMessagesTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")

    private val http = QqBotHttp(testConfig("http://127.0.0.1:1"))
    private val media = QqBotMedia(http)

    @Test
    fun `a message of a single chat is the direct chat of its author`() {
        val event = eventOf(c2c())
        val message = QqBotMessages.incoming(instance, event, isOperator = false, media = media)

        assertEquals("private:u1", message.chat.chat)
        assertEquals(ChatKind.DIRECT, message.chatInfo.kind)
        assertEquals("msg1", message.ref.id)
        assertEquals("Ada", message.sender.displayName)
        assertEquals(" hello ", message.text)
        assertEquals(1, message.media.size)
        assertEquals(MediaKind.IMAGE, message.media.single().kind)
        assertEquals("a.jpg", message.media.single().name)
        assertEquals(10L, message.media.single().size)
    }

    @Test
    fun `a message of a group keeps its group and the member id of its author`() {
        val message = QqBotMessages.incoming(instance, eventOf(group()), isOperator = false, media = media)

        assertEquals("group:g1", message.chat.chat)
        assertEquals(ChatKind.GROUP, message.chatInfo.kind)
        assertEquals("member1", message.sender.address.user)
        assertFalse(message.sender.isOperator, "a group's own owner or admin role grants nothing by itself")
    }

    @Test
    fun `the caller alone says whether the sender operates the instance`() {
        val message = QqBotMessages.incoming(instance, eventOf(c2c()), isOperator = true, media = media)

        assertTrue(message.sender.isOperator)
    }

    @Test
    fun `the account authorId reads is what a group names its member by`() {
        assertEquals("member1", QqBotMessages.authorId(eventOf(group())))
        assertEquals("u1", QqBotMessages.authorId(eventOf(c2c())))
    }

    @Test
    fun `a card message reaches the model as a card`() {
        val message =
            QqBotMessages.incoming(instance, eventOf(framed("C2C_MESSAGE_CREATE", card())), false, media)

        val fact = message.facts.singleOrNull { it.label == "card" }
        assertNotNull(fact)
        assertTrue("小程序" in fact.value, fact.value)
    }

    @Test
    fun `a quote message carries what it quoted`() {
        val message = QqBotMessages.incoming(
            instance,
            eventOf(framed("C2C_MESSAGE_CREATE", quoted())),
            false,
            media,
        )

        val quote = assertNotNull(message.quote)
        assertTrue("每天坚持阅读" in quote.text, quote.text)
    }

    @Test
    fun `a quote resolves its reference to the message id`() {
        val index = QqBotMessageIndex()
        val chat = ChatAddress(instance, "private:u1")
        index.record(chat, "REF2", "original")
        val message = QqBotMessages.incoming(
            instance,
            eventOf(framed("C2C_MESSAGE_CREATE", quoted())),
            false,
            media,
            index,
        )

        assertEquals("original", message.quote?.target?.id)
    }

    private fun eventOf(frame: org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFrame) =
        assertNotNull(QqBotWire.message(frame.d))

    private fun framed(name: String, data: String): org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFrame =
        assertNotNull(QqBotWire.frame("""{"id":"event1","op":0,"s":2,"t":"$name","d":$data}"""))

    private fun c2c(): org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFrame = framed(
        "C2C_MESSAGE_CREATE",
        """
        {
          "id": "msg1",
          "author": {"id": "u1", "user_openid": "u1", "username": "Ada", "bot": false},
          "content": " hello ",
          "message_type": 0,
          "timestamp": "2026-07-21T10:00:00+08:00",
          "message_scene": {"source": "default", "ext": ["msg_idx=REF1"]},
          "attachments": [
            {
              "url": "https://multimedia.example.com/1.jpg",
              "filename": "a.jpg",
              "content_type": "image/jpeg",
              "size": 10,
              "width": 2,
              "height": 3
            }
          ]
        }
        """.trimIndent(),
    )

    private fun group(): org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFrame = framed(
        "GROUP_AT_MESSAGE_CREATE",
        """
        {
          "id": "msg2",
          "author": {"id": "m1", "member_openid": "member1", "member_role": "owner", "username": "小明"},
          "content": "hi",
          "group_openid": "g1",
          "message_type": 0,
          "timestamp": "2026-07-21T10:01:00+08:00"
        }
        """.trimIndent(),
    )

    private fun card(): String = """
        {
          "id": "msg3",
          "author": {"id": "u1", "user_openid": "u1", "username": "Ada"},
          "content": "[卡片消息]",
          "message_type": 3,
          "ark_data": {"prompt": "[每日打卡]", "ark_type": "miniapp", "ark_name": "小程序"},
          "timestamp": "2026-07-21T10:02:00+08:00"
        }
    """.trimIndent()

    private fun quoted(): String = """
        {
          "id": "msg4",
          "author": {"id": "u1", "user_openid": "u1", "username": "Ada"},
          "content": "",
          "message_type": 103,
          "message_scene": {"source": "default", "ext": ["msg_idx=REF3", "ref_msg_idx=REF2"]},
          "msg_elements": [
            {"msg_idx": "REF2", "message_type": 0, "content": "每天坚持阅读半小时"}
          ],
          "timestamp": "2026-07-21T10:03:00+08:00"
        }
    """.trimIndent()
}
