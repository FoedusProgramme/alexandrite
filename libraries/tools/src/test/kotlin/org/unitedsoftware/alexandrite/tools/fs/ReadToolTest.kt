package org.unitedsoftware.alexandrite.tools.fs

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.tools.media.AWT_CODEC
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadToolTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: FsFixture
    private lateinit var ledger: ReadLedger
    private lateinit var tool: ReadTool

    @BeforeEach
    fun setUp() {
        fs = FsFixture(directory)
        ledger = ReadLedger()
        tool = ReadTool(fs.floor, ledger, AWT_CODEC)
    }

    @Test
    fun `a file's lines are returned numbered, and the file is reported as touched`() {
        val file = fs.write("repo/src/Main.kt", "fun main() {\r\n    println(\"hi\")\n}\n")
        val context = fs.context()

        val result = tool.call(context, "path" to file)

        assertEquals("1\tfun main() {\n2\t    println(\"hi\")\n3\t}\n", result.text)
        assertFalse(result.isError)
        assertEquals(listOf(file), context.touchedPaths)
    }

    @Test
    fun `a relative path is read from the workspace, and refused without one`() {
        val notes = fs.write("workspace/notes/todo.md", "\uFEFFBuy tea.")

        val context = fs.context()
        assertEquals("1\tBuy tea.\n", tool.call(context, "path" to "notes/./todo.md").text)
        assertEquals(listOf(notes), context.touchedPaths)
        assertRefused(
            "'notes/todo.md' is a relative path, and you have no workspace to resolve it against: give an absolute " +
                "path.",
            tool.call(fs.context(workspace = null), "path" to "notes/todo.md"),
        )
    }

    @Test
    fun `a protected file is refused, also through a symbolic link or a relative path, and is not touched`() {
        val keys = fs.link("workspace/keys", fs.home.resolve(".ssh"))
        val context = fs.context()

        assertRefused(
            "${fs.secret.resolve("key.txt")} is protected (a place the test protects): no file tool may read it.",
            tool.call(context, "path" to fs.secret.resolve("key.txt")),
        )
        assertRefused(
            "${keys.resolve("id_rsa")} is protected (SSH keys): no file tool may read it.",
            tool.call(context, "path" to "keys/id_rsa"),
        )
        assertRefused(
            "${fs.workspace.resolve("../secret/key.txt").normalize()} is protected (a place the test protects): no " +
                "file tool may read it.",
            tool.call(context, "path" to "../secret/key.txt"),
        )
        assertEquals(emptyList(), context.touchedPaths)
    }

    @Test
    fun `a symbolic link to a file that is not protected is read as its target`() {
        val target = fs.write("repo/README.md", "Hello.")
        val link = fs.link("workspace/readme", target)
        val context = fs.context()

        assertEquals("1\tHello.\n", tool.call(context, "path" to link).text)
        assertEquals(listOf(target), context.touchedPaths)
    }

    @Test
    fun `a binary file or one that is no UTF-8 is refused`() {
        val binary = fs.bytes("repo/app.bin", byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0, 1, 2))
        val latin = fs.bytes("repo/latin.txt", "café".toByteArray(Charsets.ISO_8859_1))

        for (file in listOf(binary, latin)) {
            assertRefused(
                "$file is no UTF-8 text (it looks binary): fs.read reads text files only.",
                tool.call(fs.context(), "path" to file),
            )
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a file with more than one hard link is refused while the floor protects anything`() {
        val file = fs.write("repo/notes.md", "Notes.")
        Files.createLink(fs.root.resolve("repo/other.md"), file)

        assertRefused(
            "$file has more than one hard link, so it may be another name of a protected file: it is not read.",
            tool.call(fs.context(), "path" to file),
        )
        assertEquals(
            "1\tNotes.\n",
            ReadTool(fs.unprotected, ReadLedger(), AWT_CODEC).call(fs.context(), "path" to file).text,
        )
    }

    @Test
    fun `directories, missing files and arguments of the wrong kind are refused`() {
        val context = fs.context()

        assertRefused(
            "${fs.repository.resolve("src")} is a directory: list it with fs.glob.",
            tool.call(context, "path" to fs.write("repo/src/A.kt", "").parent),
        )
        assertRefused(
            "Nothing exists at ${fs.repository.resolve("missing.md")}.",
            tool.call(context, "path" to fs.repository.resolve("missing.md")),
        )
        assertRefused("Give 'path' as a non-empty string.", tool.call(context))
        assertRefused(
            "'offset' must be a whole number of at least 1.",
            tool.call(context, "path" to "a", "offset" to 0),
        )
        assertRefused(
            "'limit' must be a whole number of at least 1.",
            tool.call(context, "path" to "a", "limit" to "9"),
        )
    }

    @Test
    fun `offset and limit pick the lines, and a note says where more follow`() {
        val file = fs.write("repo/lines.txt", (1..10).joinToString("\n") { "line $it" })
        val context = fs.context()

        assertEquals(
            "4\tline 4\n5\tline 5\n[More lines follow: read them with offset 6.]\n",
            tool.call(context, "path" to file, "offset" to 4, "limit" to 2).text,
        )
        assertEquals("10\tline 10\n", tool.call(context, "path" to file, "offset" to 10).text)
        assertRefused(
            "$file has 10 lines, so offset 11 lies past its end.",
            tool.call(context, "path" to file, "offset" to 11),
        )
        assertEquals("[The file is empty.]", tool.call(context, "path" to fs.write("repo/empty.txt", "")).text)
    }

    @Test
    fun `at most 2000 lines, 2000 characters a line and 100 000 characters are returned`() {
        val many = fs.write("repo/many.txt", (1..2500).joinToString("\n") { "$it" })
        val long = fs.write("repo/long.txt", "a".repeat(2500) + "\nshort\n" + "b".repeat(2001))
        val wide = fs.write("repo/wide.txt", (1..200).joinToString("\n") { "x".repeat(1000) })
        val context = fs.context()

        val lines = tool.call(context, "path" to many, "limit" to 5000).text.lines()
        assertEquals("2000\t2000", lines[1999])
        assertEquals("[More lines follow: read them with offset 2001.]", lines[2000])
        assertEquals(
            "1\t${"a".repeat(2000)}\n2\tshort\n3\t${"b".repeat(2000)}\n[Lines cut after 2000 characters: 1, 3.]\n",
            tool.call(context, "path" to long).text,
        )
        val shown = tool.call(context, "path" to wide).text.lines()
        assertEquals(99, shown.count { '\t' in it })
        assertEquals("[More lines follow: read them with offset 100.]", shown[99])
    }

    @Test
    fun `a leading tilde names the home directory`() {
        val home = Path.of(System.getProperty("user.home"))
        val missing = "alexandrite-test-${System.nanoTime()}.txt"

        val inHome = tool.call(fs.context(), "path" to "~/$missing")
        val homeItself = tool.call(fs.context(), "path" to "~")

        assertRefused("Nothing exists at ${home.toRealPath().resolve(missing)}.", inHome)
        assertRefused("$home is a directory: list it with fs.glob.", homeItself)
    }

    @Test
    fun `an image within the limits is returned as a media part, next to a short text part`() {
        val png = pngBytes(20, 10)
        val file = fs.bytes("repo/photo.png", png)
        val context = fs.context()

        val result = tool.call(context, "path" to file)

        assertFalse(result.isError)
        val text = assertIs<TextPart>(result.content[0])
        assertTrue("image/png" in text.text && "${png.size} bytes" in text.text && "20x10" in text.text, text.text)
        val media = assertIs<MediaPart>(result.content[1])
        assertEquals(MediaKind.IMAGE, media.kind)
        assertEquals("image/png", media.mediaType)
        assertContentEquals(png, (media.source as InlineMedia).bytes())
        assertEquals("photo.png" to (20 to 10), media.name to (media.width to media.height))
        assertEquals(listOf(file), context.touchedPaths)
    }

    @Test
    fun `a bmp is shown as a media part of its normalized type, not as bmp`() {
        val file = fs.bytes("repo/photo.bmp", bmpBytes(10, 6))
        val context = fs.context()

        val media = assertIs<MediaPart>(tool.call(context, "path" to file).content[1])

        assertTrue(media.mediaType == "image/png" || media.mediaType == "image/jpeg")
        assertEquals(10 to 6, media.width to media.height)
    }

    @Test
    fun `an image this version cannot decode is shown as text, and is not recorded for a later write`() {
        val file = fs.bytes("repo/photo.heic", fakeHeicBytes())
        val context = fs.context()

        val result = tool.call(context, "path" to file)

        assertFalse(result.isError)
        val text = assertIs<TextPart>(result.content.single())
        assertTrue("image/heic" in text.text && "cannot show" in text.text, text.text)
        assertNull(ledger.hash(ConversationId("test"), file))
    }

    @Test
    fun `without an image codec, an image a model takes as it is still is shown unchanged`() {
        val png = pngBytes(20, 10)
        val file = fs.bytes("repo/photo.png", png)

        val media = assertIs<MediaPart>(ReadTool(fs.floor, ledger, null).call(fs.context(), "path" to file).content[1])

        assertContentEquals(png, (media.source as InlineMedia).bytes())
        assertEquals("image/png" to (20 to 10), media.mediaType to (media.width to media.height))
    }

    @Test
    fun `without an image codec, an image that needs one is shown as text that says so`() {
        val file = fs.bytes("repo/photo.bmp", bmpBytes(10, 6))

        val result = ReadTool(fs.floor, ledger, null).call(fs.context(), "path" to file)

        assertFalse(result.isError)
        assertEquals(
            "$file is an image (image/bmp, ${Files.size(file)} bytes) that fs.read cannot show: this host cannot " +
                "process images.",
            assertIs<TextPart>(result.content.single()).text,
        )
    }

    @Test
    fun `a protected image is refused, not shown`() {
        val file = fs.bytes("secret/photo.png", pngBytes(4, 4))

        assertRefused(
            "$file is protected (a place the test protects): no file tool may read it.",
            tool.call(fs.context(), "path" to file),
        )
    }
}

private fun pngBytes(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, 0xFF00FF)
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}

private fun bmpBytes(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, 0x00FF00)
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "bmp", output)
    return output.toByteArray()
}

/** An ISOBMFF `ftyp` box of the `heic` brand with no HEIC data behind it, which no reader in this JVM claims. */
private fun fakeHeicBytes(): ByteArray {
    val bytes = ByteArray(20)
    "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
    "heic".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
    return bytes
}
