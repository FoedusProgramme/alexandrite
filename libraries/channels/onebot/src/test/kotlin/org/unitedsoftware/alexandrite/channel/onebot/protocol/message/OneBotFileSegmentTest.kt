package org.unitedsoftware.alexandrite.channel.onebot.protocol.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file segment, which the standard does not declare and an implementation may report.
 *
 * A file reaches a message as a segment of its own where the implementation offers one, so a report carrying it is
 * read rather than kept as something unknown, and what the channel was told of it is written back the way it came.
 */
class OneBotFileSegmentTest {
    private val file = OneBotSegment.File(file = "a1b2", name = "notes.zip", url = "http://host/notes.zip")

    @Test
    fun `a file is written with what it is known by, its name and where it can be read`() {
        val written = OneBotSegmentCodec.encodeSegment(file)
        assertEquals("file", written.getValue("type").jsonPrimitive.content)
        val data = written.getValue("data").jsonObject
        assertEquals("a1b2", data.getValue("file").jsonPrimitive.content)
        assertEquals("notes.zip", data.getValue("name").jsonPrimitive.content)
        assertEquals("http://host/notes.zip", data.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `a file that is written is one that is read back`() {
        assertEquals(file, OneBotSegmentCodec.decodeSegment(OneBotSegmentCodec.encodeSegment(file)))
    }

    @Test
    fun `a file that names only what it is known by is read without a name or a place`() {
        val bare = OneBotSegmentCodec.decodeSegment(
            buildJsonObject {
                put("type", JsonPrimitive("file"))
                put("data", buildJsonObject { put("file", JsonPrimitive("a1b2")) })
            },
        )
        val read = assertIs<OneBotSegment.File>(bare)
        assertEquals("a1b2", read.file)
        assertNull(read.name)
        assertNull(read.url)
    }

    @Test
    fun `what an implementation says of a file that this version does not model is left behind`() {
        val read = assertIs<OneBotSegment.File>(
            OneBotSegmentCodec.decodeSegment(
                buildJsonObject {
                    put("type", JsonPrimitive("file"))
                    put(
                        "data",
                        buildJsonObject {
                            put("file", JsonPrimitive("a1b2"))
                            put("something_new", JsonPrimitive(1))
                        },
                    )
                },
            ),
        )
        assertEquals("a1b2", read.file)
    }

    @Test
    fun `a file is spelled as a CQ code of its own`() {
        val spelled = CqCode.encode(file)
        assertTrue(spelled.startsWith("[CQ:file,"), spelled)
        assertTrue(spelled.contains("file=a1b2"), spelled)
        assertTrue(spelled.contains("name=notes.zip"), spelled)
    }

    @Test
    fun `a message that carries a file holds it beside its words`() {
        val segments = OneBotSegmentCodec.decodeMessageSegments(
            buildJsonArray {
                add(OneBotSegmentCodec.encodeSegment(OneBotSegment.Text("here")))
                add(OneBotSegmentCodec.encodeSegment(file))
            },
        )
        assertEquals(listOf("here"), segments.filterIsInstance<OneBotSegment.Text>().map { it.text })
        assertEquals(file, segments.filterIsInstance<OneBotSegment.File>().single())
    }

    @Test
    fun `what is not a file is still unknown`() {
        val unknown = OneBotSegmentCodec.decodeSegment(
            JsonObject(mapOf("type" to JsonPrimitive("something_else"), "data" to JsonObject(emptyMap()))),
        )
        assertIs<OneBotSegment.Unknown>(unknown)
    }
}
