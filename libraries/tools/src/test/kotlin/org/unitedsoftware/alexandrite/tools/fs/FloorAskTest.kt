package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import org.unitedsoftware.alexandrite.testkit.testUser
import org.unitedsoftware.alexandrite.tools.media.AWT_CODEC
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FloorAskTest {
    @TempDir
    lateinit var directory: Path

    private val fs by lazy { FsFixture(directory) }
    private val asked by lazy { fs.root.resolve("asked") }
    private val floor by lazy { testHardFloor(listOf(fs.secret), fs.home, asked = listOf(asked, fs.secret)) }

    private fun person(path: Path) = "$path is in a place the floor asks a person about (a place the test asks about)"

    private fun approved(snapshot: String? = null) =
        fs.context(clearance = Clearance(ClearanceKind.HUMAN, testUser("operator", isOperator = true), snapshot))

    @Test
    fun `a read of a place the floor asks about names it for a person`() {
        val notes = fs.write("asked/notes.txt", "hello\n")
        val read = ReadTool(floor, ReadLedger(), AWT_CODEC)

        val review = runBlocking { read.review(arguments("path" to notes), fs.context()) }

        assertEquals(person(notes), review.person)
        assertEquals(listOf(notes), review.reads)
        assertEquals("1\thello\n", read.call(approved(), "path" to notes).text)
        assertNull(runBlocking { read.review(arguments("path" to fs.workspace.resolve("a.txt")), fs.context()) }.person)
    }

    @Test
    fun `a place both refused and asked about is refused`() {
        val read = ReadTool(floor, ReadLedger(), AWT_CODEC)
        val key = fs.secret.resolve("key.txt")

        assertNull(runBlocking { read.review(arguments("path" to key), fs.context()) }.person)
        assertRefused(
            "$key is protected (a place the test protects): no file tool may read it.",
            read.call(approved(), "path" to key),
        )
    }

    @Test
    fun `a search skips the places the floor asks about unless a person allowed the search of one`() {
        fs.write("asked/notes.txt", "needle\n")
        fs.write("open/notes.txt", "needle\n")
        val glob = GlobTool(floor)
        val grep = GrepTool(floor)

        val listed = glob.call(fs.context(), "pattern" to "**/notes.txt", "path" to fs.root).text
        val inside = runBlocking { glob.review(arguments("pattern" to "*", "path" to asked), fs.context()) }

        assertEquals(
            "${fs.root.resolve("open/notes.txt")}\n" +
                "[Skipped protected places: ${fs.home.resolve(".ssh")} (SSH keys), ${fs.secret} (a place the test " +
                "protects).]\n" +
                "[Skipped places that a person must allow (name one as 'path' to ask): $asked (a place the test " +
                "asks about).]",
            listed,
        )
        assertEquals(person(asked), inside.person)
        assertEquals("${asked.resolve("notes.txt")}", glob.call(approved(), "pattern" to "*", "path" to asked).text)
        assertEquals(
            person(asked),
            runBlocking { grep.review(arguments("pattern" to "needle", "path" to asked), fs.context()) }.person,
        )
    }

    @Test
    fun `a write into a place the floor asks about shows its diff to a person`() {
        val file = asked.resolve("new.txt")
        Files.createDirectories(asked)
        val write = WriteTool(floor, ReadLedger(), fs.places)

        val review = runBlocking { write.review(arguments("path" to file, "content" to "hi\n"), fs.context()) }

        assertEquals(person(file), review.person)
        assertIs<ReviewPreview.Diff>(review.preview)
        assertEquals(
            "Created $file: +1 -0 lines.",
            write.call(approved(review.snapshot), "path" to file, "content" to "hi\n").text,
        )
    }
}
