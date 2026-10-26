package org.unitedsoftware.alexandrite.channel.onebot.protocol.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.GroupId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CqCodeTest {
    @Test
    fun `plain text is one segment and is escaped`() {
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("hello")), CqCode.decode("hello"))
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("a[b]c")), CqCode.decode("a&#91;b&#93;c"))
        // A comma means nothing in the text outside a code, so the entity is only a comma inside a parameter: plain
        // text keeps `&#44;` as the characters it is.
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("x,y")), CqCode.decode("x,y"))
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("x&#44;y")), CqCode.decode("x&#44;y"))
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("a&b")), CqCode.decode("a&amp;b"))
        assertEquals(emptyList(), CqCode.decode(""))
    }

    @Test
    fun `a code becomes its segment and the text around it becomes text segments`() {
        assertEquals(
            listOf(
                OneBotSegment.Text("hi "),
                OneBotSegment.At("10001000"),
                OneBotSegment.Text(" there"),
            ),
            CqCode.decode("hi [CQ:at,qq=10001000] there"),
        )
    }

    @Test
    fun `a code is escaped when it is written again`() {
        assertEquals(
            "hi [CQ:at,qq=10001000] there",
            CqCode.encode(CqCode.decode("hi [CQ:at,qq=10001000] there")),
        )
        assertEquals("[CQ:at,qq=all]", CqCode.encode(OneBotSegment.At(OneBotSegment.At.ALL)))
        assertEquals("[CQ:face,id=123]", CqCode.encode(OneBotSegment.Face("123")))
        assertEquals("[CQ:reply,id=123456]", CqCode.encode(OneBotSegment.Reply(MessageId("123456"))))
    }

    @Test
    fun `a text keeps the CQ code entities in it`() {
        val text = "look [CQ:face,id=1] and &#91;CQ:not a code&#93;&#44; ok&amp;"
        val decoded = CqCode.decode(text)
        assertEquals(OneBotSegment.Text("look "), decoded[0])
        assertEquals(OneBotSegment.Face("1"), decoded[1])
        assertEquals(" and [CQ:not a code]&#44; ok&", assertIs<OneBotSegment.Text>(decoded[2]).text)
        // A comma is not an entity of plain text, so writing this text escapes only its `&`, and reading that back
        // returns the text the segments hold.
        assertEquals(decoded, CqCode.decode(CqCode.encode(decoded)))
    }

    @Test
    fun `a code that names a type this version does not know keeps its parameters`() {
        val decoded = CqCode.decode("[CQ:napcat_thing,key=value]")
        val unknown = assertIs<OneBotSegment.Unknown>(decoded.single())
        assertEquals("napcat_thing", unknown.type)
        assertEquals("value", unknown.data["key"]?.jsonPrimitive?.content)
        assertEquals("[CQ:napcat_thing,key=value]", CqCode.encode(unknown))
    }

    @Test
    fun `an unterminated code stays plain text`() {
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("[CQ:at,qq=1")), CqCode.decode("[CQ:at,qq=1"))
    }

    @Test
    fun `the segments of the standard survive a CQ round trip`() {
        for (type in OneBotSegment.KNOWN_TYPES) {
            if (type == OneBotSegment.TEXT) continue
            val segment = CqCode.decode(sample(type)).single()
            assertEquals(type, segment.type, "$type did not decode to itself")
            val encoded = CqCode.encode(segment)
            val again = CqCode.decode(encoded).single()
            assertEquals(segment, again, "$type did not survive its CQ code '$encoded'")
        }
    }

    @Test
    fun `every segment type of the standard has a sample, except the plain text one`() {
        val expected = OneBotSegment.KNOWN_TYPES.toSet() - OneBotSegment.TEXT
        assertEquals(expected, OneBotSegment.KNOWN_TYPES.filter { sample(it).isNotEmpty() }.toSet())
    }

    private fun sample(type: String): String = when (type) {
        OneBotSegment.TEXT -> ""

        OneBotSegment.FACE -> "[CQ:face,id=123]"

        OneBotSegment.IMAGE ->
            "[CQ:image,file=1.jpg,type=flash,url=http://example.test/1.jpg,cache=1,proxy=0,timeout=5]"

        OneBotSegment.RECORD -> "[CQ:record,file=1.mp3,magic=1,url=http://example.test/1.mp3]"

        OneBotSegment.VIDEO -> "[CQ:video,file=1.mp4,url=http://example.test/1.mp4]"

        OneBotSegment.AT -> "[CQ:at,qq=10001000]"

        OneBotSegment.RPS -> "[CQ:rps]"

        OneBotSegment.DICE -> "[CQ:dice]"

        OneBotSegment.SHAKE -> "[CQ:shake]"

        OneBotSegment.POKE -> "[CQ:poke,type=126,id=2003,name=hello]"

        OneBotSegment.ANONYMOUS -> "[CQ:anonymous,ignore=1]"

        OneBotSegment.SHARE -> "[CQ:share,url=http://baidu.com,title=baidu]"

        OneBotSegment.CONTACT -> "[CQ:contact,type=qq,id=10001000]"

        OneBotSegment.LOCATION -> "[CQ:location,lat=39.9,lon=116.3]"

        OneBotSegment.MUSIC -> "[CQ:music,type=163,id=28949129]"

        OneBotSegment.REPLY -> "[CQ:reply,id=123456]"

        OneBotSegment.FORWARD -> "[CQ:forward,id=abc]"

        OneBotSegment.NODE -> "[CQ:node,user_id=10001000,nickname=someone,content=hello]"

        OneBotSegment.XML -> "[CQ:xml,data=body]"

        OneBotSegment.JSON -> "[CQ:json,data=body]"

        else -> ""
    }

    @Test
    fun `the codec reads the three shapes of a message`() {
        val text = JsonPrimitive("hi [CQ:at,qq=1]")
        val array = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    putJsonObject("data") { put("text", "hi ") }
                },
                buildJsonObject {
                    put("type", "at")
                    putJsonObject("data") { put("qq", "1") }
                },
            ),
        )
        val single = buildJsonObject {
            put("type", "at")
            putJsonObject("data") { put("qq", "1") }
        }

        val string = assertIs<OneBotMessage.StringValue>(OneBotSegmentCodec.decodeMessage(text))
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("hi "), OneBotSegment.At("1")), string.segments)

        val list = assertIs<OneBotMessage.ArrayValue>(OneBotSegmentCodec.decodeMessage(array))
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("hi "), OneBotSegment.At("1")), list.segments)

        val one = assertIs<OneBotMessage.SingleSegment>(OneBotSegmentCodec.decodeMessage(single))
        assertEquals(OneBotSegment.At("1"), one.segment)
    }

    @Test
    fun `an optional parameter of a segment is left out when it is absent`() {
        // The standard's request contract has no room for a parameter that is present and null: an implementation
        // reading `type` as a string meets a null, and one reading `timeout` as a number does too.
        val written = OneBotSegmentCodec.encodeSegment(OneBotSegment.Image(file = "1.jpg"))

        val data = assertIs<JsonObject>(written["data"])
        assertEquals(setOf("file"), data.keys)
    }

    @Test
    fun `a segment keeps its data and writes type once`() {
        val element = buildJsonObject {
            put("type", "image")
            putJsonObject("data") {
                put("file", "1.jpg")
                put("type", "flash")
                put("url", "http://example.test/1.jpg")
            }
        }
        val segment = assertIs<OneBotSegment.Image>(OneBotSegmentCodec.decodeSegment(element))
        assertEquals("1.jpg", segment.file)
        assertEquals("flash", segment.subtype)

        val written = OneBotSegmentCodec.encodeSegment(segment)
        assertEquals("image", written["type"]?.jsonPrimitive?.content)
        val data = assertIs<JsonObject>(written["data"])
        assertEquals("flash", data["type"]?.jsonPrimitive?.content)
        assertEquals("1.jpg", data["file"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a segment of an unknown type keeps its whole data object`() {
        val element = buildJsonObject {
            put("type", "napcat_thing")
            putJsonObject("data") {
                put("key", "value")
                putJsonObject("nested") { put("deep", 1) }
            }
        }
        val unknown = assertIs<OneBotSegment.Unknown>(OneBotSegmentCodec.decodeSegment(element))
        assertEquals("napcat_thing", unknown.type)
        assertEquals(element, OneBotSegmentCodec.encodeSegment(unknown))

        val message = OneBotSegmentCodec.decodeMessage(element)
        assertEquals(listOf<OneBotSegment>(unknown), message.segments)
    }

    @Test
    fun `an image message writes its segments as an array`() {
        val message = OneBotMessage.text("hi")
        val written = OneBotSegmentCodec.encodeMessageArray(message)
        assertEquals(1, written.size)
        assertTrue(assertIs<JsonObject>(written[0]).containsKey("type"))
    }

    @Test
    fun `a string message stays a string in the shape it is written in`() {
        val message = OneBotMessage.StringValue("[CQ:at,qq=1]")
        assertEquals(JsonPrimitive("[CQ:at,qq=1]"), OneBotSegmentCodec.encodeMessage(message))
        assertEquals(listOf<OneBotSegment>(OneBotSegment.At("1")), message.segments)
    }

    @Test
    fun `a node of a forwarded message keeps the message inside it`() {
        val element = buildJsonObject {
            put("type", "node")
            putJsonObject("data") {
                put("user_id", 10001000)
                put("nickname", "someone")
                put(
                    "content",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "text")
                                putJsonObject("data") { put("text", "hi") }
                            },
                        ),
                    ),
                )
            }
        }
        val node = assertIs<OneBotSegment.Node>(OneBotSegmentCodec.decodeSegment(element))
        assertEquals(UserId("10001000"), node.userId)
        assertEquals("someone", node.nickname)
        assertEquals(listOf<OneBotSegment>(OneBotSegment.Text("hi")), node.content?.segments)
    }

    @Test
    fun `a number of the platform is read as a string and written as a number`() {
        val element = buildJsonObject {
            put("type", "at")
            putJsonObject("data") { put("qq", 10001000) }
        }
        val at = assertIs<OneBotSegment.At>(OneBotSegmentCodec.decodeSegment(element))
        assertEquals("10001000", at.userId)
        assertEquals(element, OneBotSegmentCodec.encodeSegment(at))
        assertEquals(GroupId("1").number, 1L)
    }
}
