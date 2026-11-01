package org.unitedsoftware.alexandrite.store.sqlite

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptCodec
import org.unitedsoftware.alexandrite.sdk.transcript.UnknownEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteTranscriptStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `entries are stored with their type and format, at the millisecond of the store's clock`() {
        val clock = MutableClock(TEST_TIME.plusNanos(123_456_789))
        val model = ModelRef(EndpointId("anthropic"), "claude-opus")
        val entries = listOf(
            message("hello"),
            AssistantEntry(null, listOf(TextPart("Hi.")), model),
            ToolResultEntry(null, ToolCallId("c"), "notes.add", listOf(TextPart("Saved.")), ToolOutcome.Succeeded),
            SummaryEntry(null, "The guest said hello.", EntryId(1)),
            NoticeEntry(null, "The model answered with nothing.", NoticeKind.BLANK_REPLY),
            UnknownEntry(null, "poll", json("""{"type":"poll","question":"Lunch?"}""")),
        )

        withStore(directory, clock) {
            val conversation = conversations.current(main)
            val turn = turn(conversation)

            val stored = transcripts.append(turn.id, entries)

            val time = TEST_TIME.plusMillis(123)
            assertEquals(
                entries.mapIndexed { index, entry ->
                    entry.withRecord(EntryRecord(EntryId(index + 1L), conversation.id, turn.id, time))
                },
                stored,
            )
            assertEquals(
                listOf("user", "assistant", "tool_result", "summary", "notice", "poll"),
                rows("SELECT type FROM entries ORDER BY id").map { it.single() },
            )
            assertEquals(
                List(entries.size) { TranscriptCodec.FORMAT_VERSION.toLong() },
                rows("SELECT format FROM entries").map { it.single() },
            )
        }
    }

    @Test
    fun `rows of older formats are decoded, and rows of newer formats or that cannot be decoded are unknown`() {
        withStore(directory) {
            val turn = turn(conversations.current(main))
            val older = """{"type":"notice","text":"Done.","kind":"failed"}"""
            database.transaction {
                val rows = listOf(
                    Triple(2, "user", """{"type":"user","v2":true}"""),
                    Triple(1, "summary", """{"type":"summary"}"""),
                    Triple(0, "notice", older),
                    Triple(1, "notice", """["type", "notice"]"""),
                )
                for ((format, type, body) in rows) {
                    execute(
                        "INSERT INTO entries (conversation, turn, type, created_at, format, body) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                        turn.conversation.value,
                        turn.id.value,
                        type,
                        now,
                        format,
                        body,
                    )
                }
            }

            val read = transcripts.entries(turn.conversation)

            assertEquals(
                listOf(
                    UnknownEntry(null, "user", json("""{"type":"user","v2":true}""")),
                    UnknownEntry(null, "summary", json("""{"type":"summary"}""")),
                    NoticeEntry(null, "Done.", NoticeKind.FAILED),
                    UnknownEntry(null, "notice", json("""{"type":"notice","body":"[\"type\", \"notice\"]"}""")),
                ),
                read.map { entry ->
                    when (entry) {
                        is UnknownEntry -> UnknownEntry(null, entry.type, entry.json)
                        is NoticeEntry -> NoticeEntry(null, entry.text, entry.kind)
                        else -> entry
                    }
                },
            )
            assertEquals(listOf(1L, 2L, 3L, 4L), read.map { it.record!!.id.value })
        }
    }

    @Test
    fun `deleting a message deletes the files no other media names`() {
        withStore(directory) {
            val own = media.put(byteArrayOf(4, 5, 6), MediaKind.IMAGE, "image/png")
            val first = media.put(png, MediaKind.IMAGE, "image/png")
            val second = media.put(png, MediaKind.IMAGE, "image/png")
            append(UserEntry(null, listOf(mediaPart(own), mediaPart(first)), message("a").origin))
            val ownFile = mediaFile(media.info(own.id)!!.sha256)
            val sharedFile = mediaFile(media.info(first.id)!!.sha256)

            transcripts.deleteMessage(ChannelMessageRef(chat, "m-a"))

            assertTrue(Files.notExists(ownFile))
            assertNull(media.info(first.id))
            assertTrue(Files.exists(sharedFile))
            assertEquals(png.toList(), media.read(second.id)!!.toList())
        }
    }

    private fun mediaFile(sha256: String): Path =
        directory.resolve(SqliteMediaStore.DIRECTORY).resolve(sha256.take(2)).resolve(sha256)
}
