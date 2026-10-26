package org.unitedsoftware.alexandrite.channel.onebot.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.SendPrivateMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEventCodec
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegmentCodec
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneBotMessagesTest {
    private val instance = ChannelInstanceId(ChannelType("onebot"), "main")

    @Test
    fun `a private message becomes a message of a direct chat`() {
        val message = OneBotMessages.incoming(instance, privateEvent())
        assertEquals("private:12345678", message.chat.chat)
        assertEquals("12", message.ref.id)
        assertEquals("12345678", message.sender.address.user)
        assertEquals(ChatKind.DIRECT, message.chatInfo.kind)
        assertEquals("someone", message.sender.displayName)
        assertEquals("hello there", message.text)
        assertEquals(0, message.media.size)
    }

    @Test
    fun `a group message keeps its group and takes no operator from a platform role`() {
        val message = OneBotMessages.incoming(instance, groupEvent(role = "admin"))
        assertEquals("group:100100", message.chat.chat)
        assertEquals(ChatKind.GROUP, message.chatInfo.kind)
        assertFalse(message.sender.isOperator, "a platform administrator was treated as an operator")
        assertEquals("the card", message.sender.displayName)
    }

    @Test
    fun `an instance admin is an admin even when the platform reports a plain member`() {
        val message = OneBotMessages.incoming(instance, groupEvent(role = "member"), admins = setOf("12345678"))
        assertTrue(message.sender.isOperator)
    }

    @Test
    fun `an operator is named by the instance and not by the group owner role`() {
        val owner = OneBotMessages.incoming(instance, groupEvent(role = "owner"))
        assertFalse(owner.sender.isOperator, "a group owner was treated as an operator")
        val named = OneBotMessages.incoming(instance, groupEvent(role = "owner"), admins = setOf("12345678"))
        assertTrue(named.sender.isOperator)
    }

    @Test
    fun `an anonymous group message is a fact of the message`() {
        val message = OneBotMessages.incoming(instance, groupEvent(anonymous = true))
        assertTrue(message.facts.any { it.label == "anonymous" && it.value == "no one" })
    }

    @Test
    fun `the segments the SDK has no shape for become facts`() {
        val message = OneBotMessages.incoming(instance, privateEvent(message = "[CQ:napcat_thing,key=value]"))
        assertTrue(message.facts.any { it.label == "unsupported segment" && it.value == "napcat_thing" })
    }

    @Test
    fun `a reply names the message it answers`() {
        val message = OneBotMessages.incoming(instance, privateEvent(message = "[CQ:reply,id=99]hello"))
        assertEquals("99", message.quote?.target?.id)
        assertEquals("private:12345678", message.quote?.target?.chat?.chat)
    }

    @Test
    fun `an image becomes a media attachment and a fact`() {
        val message = OneBotMessages.incoming(
            instance,
            privateEvent(message = "[CQ:image,file=1.jpg,url=http://x/1.jpg]"),
        )
        assertEquals(1, message.media.size)
        assertEquals("1.jpg", message.media.single().name)
        assertTrue(message.facts.any { it.label == "image" })
    }

    @Test
    fun `a temporary session keeps the group as its chat and the sender as its thread`() {
        val event =
            assertIs<OneBotEvent.Message.Private>(
                OneBotEventCodec.decode(parse(privateJson(subType = "group", groupId = 100100))),
            )
        val chat = OneBotChats.addressOf(instance, event)
        assertEquals("group:100100", chat.chat)
        assertEquals("12345678", chat.thread)
        assertEquals("12345678", OneBotChats.addressOf(instance, event).thread)
    }

    @Test
    fun `a temporary session answers its sender and not its group`() {
        val event =
            assertIs<OneBotEvent.Message.Private>(
                OneBotEventCodec.decode(parse(privateJson(subType = "group", groupId = 100100))),
            )
        val chat = OneBotChats.addressOf(instance, event)

        // A reply to this chat belongs to the user who sent it: the group it came through is not where an answer to a
        // private message goes, and the address says so by keeping the sender as its thread.
        assertEquals(UserId("12345678"), OneBotMessages.privateTemporary(chat))
        assertNull(OneBotMessages.privateUser(chat), "the chat itself names the group, not the sender")
    }

    @Test
    fun `plain text leaves as one text segment`() {
        val message = OutboundMessage.builder(
            "hello",
            org.unitedsoftware.alexandrite.sdk.channel.MessageKind.REPLY,
        ).build()
        val segments = OneBotMessages.outgoing(chat(), message).segments
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("hello")), segments)
    }

    @Test
    fun `a message keeps the shape it was written in when it asks to be taken literally`() {
        // A whole message as one piece of text is sent as text, so that `auto_escape` can say to take the CQ codes
        // inside it as the characters they are. A message of segments is sent as segments and asks for no escaping,
        // because an implementation that read the text of one as an escape would send something else.
        val literal = SendPrivateMessage(UserId("1"), OneBotMessage.StringValue("hello [CQ:face]"), autoEscape = true)
        assertEquals("hello [CQ:face]", literal.toJson()["message"]?.jsonPrimitive?.content)
        assertEquals(true, literal.toJson()["auto_escape"]?.jsonPrimitive?.boolean)

        val segments = SendPrivateMessage(UserId("1"), OneBotMessage.ArrayValue(listOf(OneBotSegment.Text("hi"))))
        assertIs<JsonArray>(segments.toJson()["message"])
        assertNull(segments.toJson()["auto_escape"])
    }

    @Test
    fun `markdown leaves its images as image segments`() {
        val message = OutboundMessage.builder(
            "see ![a](http://x/1.jpg) done",
            org.unitedsoftware.alexandrite.sdk.channel.MessageKind.REPLY,
        )
            .markup(Markup.MARKDOWN)
            .build()
        val segments = OneBotMessages.outgoing(chat(), message).segments
        assertEquals(3, segments.size)
        assertEquals("see ", assertIs<OneBotSegment.Text>(segments[0]).text)
        assertEquals("http://x/1.jpg", assertIs<OneBotSegment.Image>(segments[1]).file)
        assertEquals(" done", assertIs<OneBotSegment.Text>(segments[2]).text)
    }

    @Test
    fun `markdown leaves as the plain text a chat of this platform reads`() {
        val message = OutboundMessage.builder(
            "## Result\n\n**done** in `main`, see [docs](https://x/y)",
            org.unitedsoftware.alexandrite.sdk.channel.MessageKind.REPLY,
        )
            .markup(Markup.MARKDOWN)
            .build()

        val segments = OneBotMessages.outgoing(chat(), message).segments

        assertEquals(
            listOf<OneBotSegment>(OneBotSegment.Text("Result\n\ndone in main, see docs (https://x/y)")),
            segments,
        )
    }

    @Test
    fun `a reply to a message whose id is not one of the implementation is sent without the reply segment`() {
        // The delivery of a message this version could not name is referenced as `sent` or `async`, which names no
        // message of the implementation. Answering one has to send the answer rather than fail before sending it.
        val message = OutboundMessage.builder("hi", org.unitedsoftware.alexandrite.sdk.channel.MessageKind.REPLY)
            .replyTo(org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef(chat(), "sent"))
            .build()

        val segments = OneBotMessages.outgoing(chat(), message).segments
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("hi")), segments)
    }

    @Test
    fun `a reply to another message leads with the reply segment`() {
        val message = OutboundMessage.builder("hi", org.unitedsoftware.alexandrite.sdk.channel.MessageKind.REPLY)
            .replyTo(org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef(chat(), "42"))
            .build()
        val segments = OneBotMessages.outgoing(chat(), message).segments
        assertEquals(
            OneBotSegment.Reply(org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId("42")),
            segments[0],
        )
        assertEquals(OneBotSegment.Text("hi"), segments[1])
    }

    @Test
    fun `a chat address is read back into the user or the group it names`() {
        assertEquals("12345678", OneBotMessages.privateUser(chat())?.value)
        assertNull(OneBotMessages.group(chat()))
        assertEquals("100100", OneBotMessages.group(ChatAddress(instance, "group:100100"))?.value)
        assertNull(OneBotMessages.privateUser(ChatAddress(instance, "group:100100")))
        assertTrue(OneBotMessages.isGroup(ChatAddress(instance, "group:100100")))
    }

    @Test
    fun `a delivered message names what the implementation answered`() {
        val answer = kotlinx.serialization.json.buildJsonObject { put("message_id", 7) }
        val delivery = OneBotMessages.delivery(OneBotResult.Ok(answer), chat())
        assertEquals("7", assertIs<Delivery.Delivered>(delivery).messages.single().id)
    }

    @Test
    fun `a refused send becomes the failure the retcode means`() {
        val limited = OneBotMessages.delivery(OneBotResult.Failed(1004), chat())
        assertEquals(DeliveryFailure.RATE_LIMITED, assertIs<Delivery.NotDelivered>(limited).kind)
        val forbidden = OneBotMessages.delivery(OneBotResult.Failed(1003), chat())
        assertEquals(DeliveryFailure.FORBIDDEN, assertIs<Delivery.NotDelivered>(forbidden).kind)
    }

    @Test
    fun `an unreachable peer is a transient failure`() {
        val delivery = OneBotMessages.delivery(
            OneBotResult.Unreachable(
                org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotFailure(
                    org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotFailureKind.TIMEOUT,
                    "no answer",
                ),
            ),
            chat(),
        )
        val notDelivered = assertIs<Delivery.NotDelivered>(delivery)
        assertEquals(DeliveryFailure.TRANSIENT, notDelivered.kind)
        assertTrue(notDelivered.retryable)
    }

    @Test
    fun `the capabilities of the channel name what it can do`() {
        val capabilities = OneBotMessages.capabilities(proactive = true)
        assertTrue(capabilities.markups.contains(Markup.PLAIN))
        assertTrue(capabilities.markups.contains(Markup.MARKDOWN))
        assertTrue(capabilities.proactive)
        assertTrue(!OneBotMessages.capabilities(proactive = false).proactive)
        assertTrue(capabilities.streaming)
        assertTrue(!OneBotMessages.capabilities(proactive = true, streaming = false).streaming)
        assertNull(capabilities.maxPartsPerReply)
    }

    private fun chat(): ChatAddress = ChatAddress(instance, "private:12345678")

    /** The JSON of a report, as the codec reads it. */
    private fun parse(json: String): kotlinx.serialization.json.JsonElement = Json.parseToJsonElement(json)

    private fun privateEvent(message: String = "hello there"): OneBotEvent.Message =
        assertIs(OneBotEventCodec.decode(parse(privateJson(message = message))))

    private fun privateJson(subType: String = "friend", groupId: Int? = null, message: String = "hello there"): String =
        buildString {
            append("""{"time":1515204254,"self_id":10001000,"post_type":"message","message_type":"private",""")
            append(""""sub_type":"$subType","message_id":12,"user_id":12345678,""")
            groupId?.let { append(""""group_id":$it,""") }
            append(""""message":"$message","raw_message":"$message",""")
            append(""""sender":{"user_id":12345678,"nickname":"someone","sex":"male","age":18}}""")
        }

    private fun groupEvent(role: String = "member", anonymous: Boolean = false): OneBotEvent.Message = assertIs(
        OneBotEventCodec.decode(
            parse(
                buildString {
                    append(
                        """{"time":1515204254,"self_id":10001000,"post_type":"message","message_type":"group",""",
                    )
                    append(""""sub_type":"normal","message_id":12,"group_id":100100,"user_id":12345678,""")
                    append(""""message":"hi","raw_message":"hi",""")
                    if (anonymous) append(""""anonymous":{"id":1,"name":"no one","flag":"abc"},""")
                    append(
                        """"sender":{"user_id":12345678,"nickname":"someone","card":"the card","role":"$role"}}""",
                    )
                },
            ),
        ),
    )
}
