package org.unitedsoftware.alexandrite.store.memory

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.testChat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemoryMediaStoreTest {
    private val clock = object : Clock() {
        var instant: Instant = TEST_TIME

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }
    private val data = StoreData(clock)
    private val conversations = MemoryConversationStore(data)
    private val transcripts = MemoryTranscriptStore(data)
    private val media = MemoryMediaStore(data)

    @Test
    fun `media larger than the store takes is refused`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { media.put(ByteArray(MemoryMediaStore.MAX_BYTES + 1), MediaKind.FILE, "application/pdf") }
        }
    }

    @Test
    fun `the store keeps a copy of the bytes it was given and hands out copies`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val stored = media.put(bytes, MediaKind.FILE, "application/pdf")
        bytes[0] = 9
        media.read(stored.id)!![1] = 9

        assertContentEquals(byteArrayOf(1, 2, 3), media.read(stored.id))
    }

    @Test
    fun `a put a day later removes the media that no entry refers to, and keeps the rest`() = runBlocking {
        val kept = media.put(byteArrayOf(1), MediaKind.IMAGE, "image/png")
        val unused = media.put(byteArrayOf(2), MediaKind.IMAGE, "image/png")
        val conversation = conversations.current(AgentChatKey(AgentId.MAIN, testChat()))
        val turn = TurnInfo.builder(TurnId("t"), conversation.key.chat, conversation.id, TurnKind.MESSAGE).build()
        conversations.startTurn(turn)
        val part = MediaPart(MediaKind.IMAGE, "image/png", kept, null, null, null)
        transcripts.append(
            turn.id,
            listOf(UserEntry(null, listOf(part), UserOrigin.Initiated("probe", TurnKind.of("task")))),
        )

        clock.instant += Duration.ofHours(23)
        media.put(byteArrayOf(3), MediaKind.IMAGE, "image/png")
        assertNotNull(media.info(unused.id))

        clock.instant += Duration.ofHours(2)
        val fresh = media.put(byteArrayOf(4), MediaKind.IMAGE, "image/png")

        assertNull(media.info(unused.id))
        assertNotNull(media.info(kept.id))
        assertNotNull(media.info(fresh.id))
    }
}
