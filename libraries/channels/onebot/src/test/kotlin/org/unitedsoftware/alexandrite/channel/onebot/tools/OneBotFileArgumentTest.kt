package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a call that names a file of the workspace does with it.
 *
 * The platform is usually on another machine, so what such a call carries is the bytes rather than the path, and
 * whether they may be read at all is the floor's to say. What is asserted here is that the floor is asked and that
 * its answer is acted on, rather than the floor's own policy, which is decided and tested where the floor lives.
 */
class OneBotFileArgumentTest {
    private val upload = row("upload_group_file")

    /** What a call of the streaming action answers, which a file small enough to carry never reaches. */
    private val stream: suspend (JsonObject) -> OneBotResult<JsonElement> = { OneBotResult.Ok(JsonPrimitive("ok")) }
    private val home: Path = createTempDirectory("onebot-file")

    @Test
    fun `a file the floor allows is carried as the bytes the platform takes`() = runBlocking {
        val file = home.resolve("notes.zip")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val context = testToolContext()
        val prepared = assertIs<Prepared.Ready>(withFiles(upload, given(file), allows(), context, stream))
        val carried = prepared.params.getValue("file").jsonPrimitive.content
        assertTrue(carried.startsWith("base64://"), carried)
        assertContentEquals(byteArrayOf(1, 2, 3), Base64.getDecoder().decode(carried.removePrefix("base64://")))
        // A file that was read is reported the way the tools for files report one.
        assertTrue(context.touchedPaths.contains(file), context.touchedPaths.toString())
    }

    @Test
    fun `a file the floor refuses is refused with the reason the floor gave`() = runBlocking {
        val file = home.resolve("secret.zip")
        file.writeBytes(byteArrayOf(1))
        val refused =
            assertIs<Prepared.Refused>(
                withFiles(upload, given(file), denies("a place the test protects"), testToolContext(), stream),
            )
        assertTrue(refused.said.contains("a place the test protects"), refused.said)
        assertTrue(refused.said.contains("may not read"), refused.said)
    }

    @Test
    fun `a file too large to carry in one request goes to the implementation in pieces, which names it`() =
        runBlocking {
            val file = home.resolve("big.zip")
            file.writeBytes(ByteArray((FILE_LIMIT + 1).toInt()))
            val calls = mutableListOf<JsonObject>()
            val prepared = assertIs<Prepared.Ready>(
                withFiles(upload, given(file), allows(), testToolContext()) { call ->
                    calls += call
                    if (call["is_complete"] != null) {
                        OneBotResult.Ok(
                            kotlinx.serialization.json.buildJsonObject {
                                put("file_path", JsonPrimitive("streamed"))
                            },
                        )
                    } else {
                        OneBotResult.Ok(JsonPrimitive("stream"))
                    }
                },
            )

            assertEquals("streamed", prepared.params.getValue("file").jsonPrimitive.content)
            assertTrue(calls.size > 1, "a file this large takes more than one call")
        }

    @Test
    fun `a file the implementation took no pieces of is refused rather than sent on`() = runBlocking {
        val file = home.resolve("big.zip")
        file.writeBytes(ByteArray((FILE_LIMIT + 1).toInt()))

        val refused = assertIs<Prepared.Refused>(
            withFiles(upload, given(file), allows(), testToolContext()) { _ -> OneBotResult.Failed(retcode = 200) },
        )

        assertTrue(refused.said.contains("took no file in pieces"), refused.said)
    }

    @Test
    fun `a call that needs a file and a runtime with no floor is refused rather than read on its own`() = runBlocking {
        val file = home.resolve("notes.zip")
        file.writeBytes(byteArrayOf(1))
        val refused = assertIs<Prepared.Refused>(withFiles(upload, given(file), null, testToolContext(), stream))
        assertTrue(refused.said.contains("no file is sent"), refused.said)
    }

    @Test
    fun `a call that names no file is refused, naming the argument`() = runBlocking {
        val given = buildJsonObject { put("group_id", JsonPrimitive("987654")) }
        val refused = assertIs<Prepared.Refused>(withFiles(upload, given, allows(), testToolContext(), stream))
        assertTrue(refused.said.contains("file"), refused.said)
    }

    @Test
    fun `a call that needs no file is left as it is, whatever the floor says`() = runBlocking {
        val mute = row("set_group_ban")
        val given = buildJsonObject {
            put("group_id", JsonPrimitive("987654"))
            put("user_id", JsonPrimitive("2222"))
            put("duration", JsonPrimitive("60"))
        }
        val prepared = assertIs<Prepared.Ready>(withFiles(mute, given, null, testToolContext(), stream))
        assertTrue(prepared.params.containsKey("duration"), prepared.params.toString())
    }

    @Test
    fun `a path relative to the workspace is resolved against it, as a file tool resolves one`() = runBlocking {
        val file = home.resolve("relative.txt")
        file.writeBytes(byteArrayOf(7, 8))
        val context = testToolContext(workspace = home)

        val prepared = assertIs<Prepared.Ready>(
            withFiles(upload, given(Path.of("relative.txt")), allows(), context, stream),
        )

        val carried = prepared.params.getValue("file").jsonPrimitive.content
        assertContentEquals(byteArrayOf(7, 8), Base64.getDecoder().decode(carried.removePrefix("base64://")))
    }

    @Test
    fun `a relative path with no workspace is refused rather than resolved against something else`() = runBlocking {
        val refused = assertIs<Prepared.Refused>(
            withFiles(upload, given(Path.of("relative.txt")), allows(), testToolContext(workspace = null), stream),
        )
        assertTrue(refused.said.contains("no workspace"), refused.said)
    }

    /** What the action was given, which names [file] as a call of a model would. */
    private fun given(file: Path) = buildJsonObject {
        put("group_id", JsonPrimitive("987654"))
        put("file", JsonPrimitive(file.toString()))
        put("name", JsonPrimitive("notes.zip"))
    }

    /** A floor that allows every path, which is the answer this test wants. */
    private fun allows(): HardFloor = StubFloor { FloorCheck.Allowed(it) }

    /** A floor that refuses every path, saying [reason]. */
    private fun denies(reason: String): HardFloor = StubFloor { FloorCheck.Denied(reason) }

    /** The row of [name], which the test expects the table to hold. */
    private fun row(name: String): OneBotActions.Row =
        checkNotNull(OneBotActions.of(name)) { "the table holds no action $name" }

    /** A floor whose answer is [decision], which stands in for the one the runtime provides. */
    @OptIn(InternalAlexandriteApi::class)
    private class StubFloor(private val decision: (Path) -> FloorCheck) : HardFloor {
        override fun checkRead(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck = decision(path)

        override fun checkWrite(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            FloorCheck.Denied("a test floor writes none")

        override fun checkTree(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
            FloorCheck.Denied("a test floor walks none")
    }
}
