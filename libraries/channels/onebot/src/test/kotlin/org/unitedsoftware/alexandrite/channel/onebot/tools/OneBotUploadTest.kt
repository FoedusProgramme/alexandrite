package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A file handed to an implementation in pieces, which is what a file too large to send in one request needs.
 *
 * What is asserted here is the protocol an implementation takes: the pieces, each with what it is checked by, the
 * answer that ends the sending, and that a file only partly sent is never answered with as if it were whole.
 */
class OneBotUploadTest {
    private val home = createTempDirectory("onebot-stream")
    private val bytes = ByteArray(STREAM_CHUNK * 2 + 5) { (it % 251).toByte() }

    @Test
    fun `a file goes in pieces, each with what the implementation checks it by`() = runBlocking {
        val file = home.resolve("notes.bin")
        file.writeBytes(bytes)
        val calls = mutableListOf<JsonObject>()

        val reference = streamFile(file, "notes.bin") { call ->
            calls += call
            answer(call)
        }

        assertEquals("file-path", reference)
        assertEquals(4, calls.size, "three pieces and the answer that ends the sending")
        val pieces = calls.dropLast(1)
        assertEquals(listOf(0, 1, 2), pieces.map { it["chunk_index"]!!.jsonPrimitive.contentOrNull?.toInt() })
        assertEquals(listOf(3, 3, 3), pieces.map { it["total_chunks"]!!.jsonPrimitive.contentOrNull?.toInt() })
        assertEquals(1, calls.map { it["stream_id"]!!.jsonPrimitive.content }.toSet().size, "one stream")
        pieces.forEach { call ->
            assertEquals(bytes.size.toString(), call["file_size"]!!.jsonPrimitive.content)
            assertEquals(digest(bytes), call["expected_sha256"]!!.jsonPrimitive.content)
            assertEquals("notes.bin", call["filename"]!!.jsonPrimitive.content)
        }
        val sent = pieces.map { Base64.getDecoder().decode(it["chunk_data"]!!.jsonPrimitive.content) }
        assertContentEquals(bytes, sent.reduce { all, piece -> all + piece })
        assertTrue(sent.dropLast(1).all { it.size == STREAM_CHUNK }, "every piece but the last is a whole one")
    }

    @Test
    fun `the sending ends with the call that says the file is complete, and carries no piece`() = runBlocking {
        val file = home.resolve("small.bin")
        file.writeBytes(bytes)
        val calls = mutableListOf<JsonObject>()

        streamFile(file, "small.bin") { call ->
            calls += call
            answer(call)
        }

        val last = calls.last()
        assertEquals("true", last["is_complete"]!!.jsonPrimitive.content)
        assertNull(last["chunk_data"])
        assertEquals(calls.first()["stream_id"]!!.jsonPrimitive.content, last["stream_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a file that is only partly sent is not answered with as if it were whole`() = runBlocking {
        val file = home.resolve("broken.bin")
        file.writeBytes(bytes)
        val calls = mutableListOf<JsonObject>()

        val reference = streamFile(file, "broken.bin") { call ->
            calls += call
            if (calls.size == 1) OneBotResult.Failed(retcode = 200) else answer(call)
        }

        assertNull(reference)
        assertEquals(1, calls.size, "nothing is sent after a piece the implementation refused")
    }

    @Test
    fun `an answer that names the file under another of its names is read as well`() = runBlocking {
        val file = home.resolve("a.bin")
        file.writeBytes(bytes)

        val reference = streamFile(file, "a.bin") { call ->
            if (call["is_complete"] != null) {
                OneBotResult.Ok(buildJsonObject { put("url", "http://host/a") })
            } else {
                OneBotResult.Ok(JsonPrimitive("ok"))
            }
        }

        assertEquals("http://host/a", reference)
    }

    @Test
    fun `an answer that names no file leaves nothing to send it on by`() = runBlocking {
        val file = home.resolve("b.bin")
        file.writeBytes(bytes)

        val reference = streamFile(file, "b.bin") { call ->
            if (call["is_complete"] !=
                null
            ) {
                OneBotResult.Ok(JsonPrimitive("done"))
            } else {
                OneBotResult.Ok(JsonPrimitive("ok"))
            }
        }

        assertNull(reference)
    }

    @Test
    fun `a file that holds nothing is still ended, with no piece sent`() = runBlocking {
        val file = home.resolve("empty.bin")
        file.writeBytes(ByteArray(0))
        val calls = mutableListOf<JsonObject>()

        val reference = streamFile(file, "empty.bin") { call ->
            calls += call
            answer(call)
        }

        assertEquals(1, calls.size)
        val only = calls.single()
        assertEquals("true", only["is_complete"]!!.jsonPrimitive.content)
        assertFalse(only.containsKey("chunk_data"))
        assertEquals("file-path", reference)
    }

    @Test
    fun `a call cancelled while a piece is being sent ends cancelled rather than answered`() = runBlocking<Unit> {
        val file = home.resolve("cancelled.bin")
        file.writeBytes(bytes)

        // The file is read on another dispatcher so that reading it does not hold the one the turn runs on, and a
        // call ended while it waits is cancelled rather than answered, since nothing is known of what it did.
        assertFailsWith<CancellationException> {
            withTimeout(50L) { streamFile(file, "cancelled.bin") { awaitCancellation() } }
        }
    }

    /** What an implementation answers to [call], naming the file it was sent when [call] ends the sending. */
    private fun answer(call: JsonObject): OneBotResult<JsonElement> = if (call["is_complete"] != null) {
        OneBotResult.Ok(buildJsonObject { put("file_path", "file-path") })
    } else {
        OneBotResult.Ok(buildJsonObject { put("type", "stream") })
    }

    /** The digest of [bytes], which is what the implementation is told to check what it was sent by. */
    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
