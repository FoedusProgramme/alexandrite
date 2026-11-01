package org.unitedsoftware.alexandrite.agent.turn

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.inbox
import org.unitedsoftware.alexandrite.agent.media.MediaPlan
import org.unitedsoftware.alexandrite.agent.media.MediaReadException
import org.unitedsoftware.alexandrite.agent.media.MediaReader
import org.unitedsoftware.alexandrite.agent.media.MediaReading
import org.unitedsoftware.alexandrite.agent.media.MediaRoute
import org.unitedsoftware.alexandrite.agent.tinyGif
import org.unitedsoftware.alexandrite.agent.tinyPng
import org.unitedsoftware.alexandrite.engine.awtimages.AlexandriteEngineAwtImagesIndex
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.channel.MediaContent
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.media.FittedImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.boundBy
import org.unitedsoftware.alexandrite.testkit.testMessage
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TurnMediaTest {
    @TempDir
    lateinit var directory: Path

    private val data: Path get() = directory.toRealPath().resolve("data")
    private val inbox: Path get() = data.resolve("conversations/main/test/inbox")
    private val memory = MemoryStore()
    private val store = Media(memory.media)
    private val reader = FakeReader()
    private val readerModel = ModelRef.parse("scripted/reader")
    private val png = "png!".encodeToByteArray()

    private fun media(maxBytes: Long = 8, config: String = "{}", codec: ImageCodec? = AWT_CODEC) =
        TurnMedia(TurnId("t-1"), store, maxBytes, inbox(data, config), reader, codec)

    private fun attachment(
        kind: MediaKind = MediaKind.IMAGE,
        mediaType: String = "image/png",
        size: Long? = null,
        name: String? = null,
        read: suspend (Long) -> ByteArray = { png },
    ): MediaAttachment = MediaAttachment.builder(
        kind,
        mediaType,
        MediaContent {
            read(it)
        },
    ).size(size).name(name).build()

    private fun message(attachments: List<MediaAttachment>): IncomingMessage =
        testMessage(id = "40") { media(attachments) }

    private fun note(vararg lines: String): ContextPart = ContextPart(
        "alexandrite.media",
        (listOf("[alexandrite:media]") + lines + "[/alexandrite:media]").joinToString("\n"),
    )

    private fun plan(vararg routes: Pair<MediaKind, MediaRoute>): MediaPlan = MediaPlan(routes.toMap())

    private fun put(media: TurnMedia, plan: MediaPlan, vararg attachments: MediaAttachment): List<UserPart> =
        blocking { media.put(testTurn(), message(attachments.toList()), plan) }

    @Test
    fun `an image taken natively is carried inline, keeping its size, without reading it back`() {
        val bytes = tinyPng(2, 3)
        val image = MediaAttachment.builder(MediaKind.IMAGE, "image/png") { bytes }.name("a.png").build()
        val media = media(maxBytes = 200)
        val native = plan(MediaKind.IMAGE to MediaRoute.Native)

        val parts = put(media, native, image)
        val stored = (parts.first() as MediaPart).source as StoredMedia
        val entry = testUserEntry().let { UserEntry(null, it.parts + parts.first(), it.origin) }
        val inline = blocking { media.inline(listOf(entry), native) }

        assertEquals(MediaPart(MediaKind.IMAGE, "image/png", stored, "a.png", 2, 3), parts.first())
        val carried = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(bytes), "a.png", 2, 3)
        assertEquals(carried, (inline.single() as UserEntry).parts.last())
        assertEquals(emptyList(), store.reads)
        assertEquals(emptyList(), reader.reads)
    }

    @Test
    fun `a route that only saves gets no inline part, while native carries other kinds too`() {
        val blind = put(
            media(maxBytes = 64),
            plan(MediaKind.IMAGE to MediaRoute.Saved("you cannot view images")),
            attachment(name = "cat.png"),
        )
        val seeing = put(
            media(maxBytes = 64),
            plan(MediaKind.AUDIO to MediaRoute.Native),
            attachment(MediaKind.AUDIO, "audio/ogg") { "ogg".encodeToByteArray() },
        )

        assertEquals(
            listOf(
                note(
                    "Saved to ${inbox.resolve("cat.png")}: image \"cat.png\" (image/png, 4 bytes); not shown: you " +
                        "cannot view images",
                ),
            ),
            blind,
        )
        assertEquals(MediaKind.AUDIO, (seeing.first() as MediaPart).kind)
        assertEquals(
            note("Saved to ${inbox.resolve("audio-40-1.ogg")}: audio (audio/ogg, 3 bytes); shown to you"),
            seeing.last(),
        )
    }

    @Test
    fun `an image that cannot be decoded is only saved, even where the route is a reader's`() {
        val parts = put(
            media(maxBytes = 64),
            plan(MediaKind.IMAGE to MediaRoute.Read(readerModel)),
            attachment(name = "broken.png"),
        )

        assertEquals(
            note(
                "Saved to ${inbox.resolve("broken.png")}: image \"broken.png\" (image/png, 4 bytes); not shown: " +
                    "the image could not be decoded",
            ),
            parts.single(),
        )
        assertEquals(emptyList(), reader.reads)
    }

    @Test
    fun `without an image codec, an image a model takes as it is is carried unchanged, another only saved`() {
        val png = tinyPng(2, 3)
        val bmp = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "bmp", it)
        }
            .toByteArray()
        val media = media(maxBytes = 200, codec = null)

        val parts = put(
            media,
            plan(MediaKind.IMAGE to MediaRoute.Native),
            MediaAttachment.builder(MediaKind.IMAGE, "image/png") { png }.name("a.png").build(),
            MediaAttachment.builder(MediaKind.IMAGE, "image/bmp") { bmp }.name("b.bmp").build(),
        )

        val shown = parts.first() as MediaPart
        assertEquals("image/png" to (2 to 3), shown.mediaType to (shown.width to shown.height))
        assertContentEquals(png, blocking { memory.media.read((shown.source as StoredMedia).id) })
        assertEquals(
            note(
                "Saved to ${inbox.resolve("a.png")}: image \"a.png\" (image/png, ${png.size} bytes); shown to you\n" +
                    "Saved to ${inbox.resolve("b.bmp")}: image \"b.bmp\" (image/bmp, ${bmp.size} bytes); not " +
                    "shown: this host cannot process images",
            ),
            parts.last(),
        )
    }

    @Test
    fun `a reader read is noted by its model, and the reader's failure saves the file instead`() {
        reader.failing(MediaKind.AUDIO, "the reader takes no audio")
        val read = plan(
            MediaKind.IMAGE to MediaRoute.Read(readerModel),
            MediaKind.AUDIO to MediaRoute.Read(readerModel),
        )

        val catBytes = tinyPng()
        val parts = put(
            media(maxBytes = 100),
            read,
            attachment(name = "cat.png") { catBytes },
            attachment(MediaKind.AUDIO, "audio/ogg") { "ogg".encodeToByteArray() },
        )

        assertEquals(listOf(MediaKind.IMAGE, MediaKind.AUDIO), parts.dropLast(1).map { (it as MediaPart).kind })
        assertEquals(
            note(
                "Saved to ${inbox.resolve("cat.png")}: image \"cat.png\" (image/png, ${catBytes.size} bytes); " +
                    "read for you by $readerModel (its reading follows in the message)",
                "Saved to ${inbox.resolve("audio-40-2.ogg")}: audio (audio/ogg, 3 bytes); not shown: the " +
                    "reader takes no audio",
            ),
            parts.last(),
        )
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.AUDIO), reader.reads.map { it.kind })
    }

    @Test
    fun `names are sanitized, unnamed media get generated names, and no file is overwritten`() {
        Files.createDirectories(inbox)
        inbox.resolve("report.pdf").writeBytes(byteArrayOf(9))

        val parts = put(
            media(maxBytes = 64),
            MediaPlan(emptyMap()),
            attachment(MediaKind.FILE, "application/pdf", name = "../../etc/report.pdf"),
            attachment(MediaKind.FILE, "application/pdf", name = "report.pdf"),
            attachment(MediaKind.FILE, "text/plain", name = ".‮hidden:notes\u0000.txt"),
            attachment(MediaKind.FILE, "application/gzip", name = "x".repeat(150) + ".tar.gz"),
            attachment(MediaKind.IMAGE, "image/jpeg"),
            attachment(MediaKind.FILE, "application/x-unknown", name = "..."),
        )

        assertEquals(
            listOf(
                "file-40-6.bin",
                "hidden_notes.txt",
                "photo-40-5.jpg",
                "report-2.pdf",
                "report-3.pdf",
                "report.pdf",
                "x".repeat(97) + ".gz",
            ),
            inbox.toFile().list()!!.sorted(),
        )
        assertEquals(listOf<Byte>(9), inbox.resolve("report.pdf").readBytes().toList())
        assertEquals(
            "Saved to ${inbox.resolve("report-2.pdf")}: file \"../../etc/report.pdf\" (application/pdf, 4 bytes); " +
                "not shown: no model takes file media",
            (parts.single() as ContextPart).text.lines()[1],
        )
    }

    @Test
    fun `an attachment too large, unavailable, unreadable, over the quota or refused by the store is noted as such`() {
        store.refusing = "image/gif"
        val gif = tinyGif()
        val media = media(maxBytes = 50, config = """{"conversationDirectory": {"maxBytes": 42}}""")
        val native = plan(MediaKind.IMAGE to MediaRoute.Native)

        val parts = put(
            media,
            native,
            attachment(MediaKind.VIDEO, "video/mp4", size = 60, name = "clip.mp4") { error("Never read.") },
            MediaAttachment.builder(MediaKind.FILE, "application/zip", null)
                .size(21_000_000)
                .unavailable("larger than the 20 MB that a bot may download")
                .fromQuote(true)
                .build(),
            attachment(MediaKind.AUDIO, "audio/ogg") { throw IOException("Gone.") },
            attachment(MediaKind.FILE, "text/plain", name = "big\nfile.txt") { ByteArray(51) },
            attachment(MediaKind.IMAGE, "image/gif", name = "a.gif") { gif },
            attachment(MediaKind.FILE, "text/plain", name = "b.txt") { ByteArray(5) },
        )

        assertEquals(
            note(
                "Not saved: video \"clip.mp4\" (video/mp4, 60 bytes): larger than the 50 bytes a message may carry",
                "Not saved: file (application/zip, 21000000 bytes), from the quoted message: larger than the 20 MB " +
                    "that a bot may download",
                "Not saved: audio (audio/ogg): it could not be loaded",
                "Not saved: file \"big file.txt\" (text/plain, 51 bytes): larger than the 50 bytes a message may " +
                    "carry",
                "Saved to ${inbox.resolve("a.gif")}: image \"a.gif\" (image/gif, ${gif.size} bytes); not shown: it " +
                    "could not be stored",
                "Not saved: file \"b.txt\" (text/plain, 5 bytes): the conversation directory already holds " +
                    "${gif.size} byte(s); this write would take it past its 42 byte quota; not shown: no model " +
                    "takes file media",
            ),
            parts.single(),
        )
    }

    @Test
    fun `stored media of earlier entries are read from the store once per turn`() {
        val stored = blocking { store.put(png, MediaKind.IMAGE, "image/png") }
        val part = MediaPart(MediaKind.IMAGE, "image/png", stored)
        val user = testUserEntry().let { UserEntry(null, it.parts + part, it.origin) }
        val result =
            ToolResultEntry(null, ToolCallId("c1"), "fs.read", listOf(TextPart("Read."), part), ToolOutcome.Succeeded)
        val media = media()
        val native = plan(MediaKind.IMAGE to MediaRoute.Native)

        val first = blocking { media.inline(listOf(user, result), native) }
        val second = blocking { media.inline(listOf(user, result), native) }

        val inline = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(png))
        assertEquals(listOf(UserEntry(null, user.parts.dropLast(1) + inline, user.origin)), first.take(1))
        assertEquals(listOf(TextPart("Read."), inline), (first[1] as ToolResultEntry).content)
        assertEquals(first, second)
        assertEquals(listOf(stored.id), store.reads)
    }

    @Test
    fun `media the store no longer has are named in their place`() {
        val gone = MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("gone")))
        val user = testUserEntry().let { UserEntry(null, it.parts + gone, it.origin) }
        val native = plan(MediaKind.IMAGE to MediaRoute.Native)

        val inline = blocking { media().inline(listOf(user), native) }

        assertEquals(
            listOf(TextPart("hello"), TextPart("(image of type image/png, no longer stored)")),
            (inline.single() as UserEntry).parts,
        )
    }

    @Test
    fun `a read route renders the cached reading, else a marker, and a saved route a marker naming its reason`() {
        val read = MediaPart(MediaKind.IMAGE, "image/png", StoredMedia(MediaId("read")))
        val unread = MediaPart(MediaKind.AUDIO, "audio/ogg", StoredMedia(MediaId("unread")), name = "clip.ogg")
        val saved = MediaPart(MediaKind.VIDEO, "video/mp4", StoredMedia(MediaId("saved")))
        blocking { reader.read(testTurn(), read, readerModel) }
        val user = testUserEntry().let { UserEntry(null, it.parts + listOf(read, unread, saved), it.origin) }
        val routed = plan(
            MediaKind.IMAGE to MediaRoute.Read(readerModel),
            MediaKind.AUDIO to MediaRoute.Read(readerModel),
            MediaKind.VIDEO to MediaRoute.Saved("the model takes no video"),
        )

        val inline = blocking { media().inline(listOf(user), routed) }

        assertEquals(
            listOf(
                TextPart(
                    "[alexandrite:media-reading]\nimage, as $readerModel read it:\n<image read>\n" +
                        "[/alexandrite:media-reading]",
                ),
                TextPart("(audio \"clip.ogg\": not read for this model)"),
                TextPart("(video not shown: the model takes no video)"),
            ),
            (inline.single() as UserEntry).parts.drop(1),
        )
    }

    @Test
    fun `a tool result's media of a read route is read now, warming the cache`() {
        val result = ToolResultEntry(
            null,
            ToolCallId("c1"),
            "fs.read",
            listOf(MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(tinyPng()))),
            ToolOutcome.Succeeded,
        )
        val read = plan(MediaKind.IMAGE to MediaRoute.Read(readerModel))

        blocking { media(maxBytes = 100).stored(testTurn(), result, read) }

        assertEquals(listOf(MediaKind.IMAGE), reader.reads.map { it.kind })
    }
}

/** A [MediaReader] that remembers the parts it was asked to read, failing the kinds [failing] names instead. */
private class FakeReader : MediaReader {
    val reads: MutableList<MediaPart> = CopyOnWriteArrayList()
    private val cache = ConcurrentHashMap<String, MediaReading>()
    private val failing = ConcurrentHashMap<MediaKind, MediaReadException>()

    fun failing(kind: MediaKind, reason: String) {
        failing[kind] = MediaReadException(ModelRef.parse("scripted/reader"), reason)
    }

    override suspend fun read(turn: TurnInfo, media: MediaPart, model: ModelRef, question: String?): MediaReading {
        reads += media
        failing[media.kind]?.let { throw it }
        val reading = MediaReading("<${media.kind} read>", model)
        cache[key(media, model, question)] = reading
        return reading
    }

    override suspend fun cached(media: MediaPart, model: ModelRef, question: String?): MediaReading? =
        cache[key(media, model, question)]

    private fun key(media: MediaPart, model: ModelRef, question: String?): String =
        "${(media.source as? StoredMedia)?.id ?: media.name ?: media.kind}|$model|$question"
}

/** The media of [store], which records the reads and refuses media of the type [refusing]. */
private class Media(private val store: MediaStore) : MediaStore by store {
    val reads: MutableList<MediaId> = CopyOnWriteArrayList()

    @Volatile
    var refusing: String? = null

    override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
        require(mediaType != refusing) { "Media of type $mediaType are refused." }
        return store.put(bytes, kind, mediaType)
    }

    override suspend fun read(id: MediaId): ByteArray? = store.read(id).also { reads += id }
}

/** The codec the desktop host binds. */
private val AWT_CODEC: ImageCodec = boundBy(AlexandriteEngineAwtImagesIndex())
