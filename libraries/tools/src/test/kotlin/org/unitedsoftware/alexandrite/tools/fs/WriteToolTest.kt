package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.tools.media.AWT_CODEC
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WriteToolTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: FsFixture
    private lateinit var ledger: ReadLedger
    private lateinit var tool: WriteTool

    @BeforeEach
    fun setUp() {
        fs = FsFixture(directory)
        ledger = ReadLedger()
        tool = WriteTool(fs.floor, ledger, fs.places)
    }

    private fun read(file: Path) {
        val result =
            runBlocking { ReadTool(fs.floor, ledger, AWT_CODEC).execute(arguments("path" to file), fs.context()) }
        assertFalse(result.isError, result.text)
    }

    private fun write(file: Any, content: String): String =
        fs.reviewedRun(tool, arguments("path" to file, "content" to content))

    @Test
    fun `a new file needs no read and is shown whole in the review`() {
        val file = fs.repository.resolve("NOTES.md")
        fs.repository.createDirectories()

        val review = runBlocking { tool.review(arguments("path" to file, "content" to "a\nb\n"), fs.context()) }
        val result = write(file, "a\nb\n")

        assertEquals(
            CallReview.builder()
                .preview(ReviewPreview.Diff(file, "--- /dev/null\n+++ $file\n@@ -0,0 +1,2 @@\n+a\n+b\n"))
                .snapshot("absent")
                .reads(listOf(file))
                .build(),
            review,
        )
        assertEquals("Created $file: +2 -0 lines." to "a\nb\n", result to file.readText())
    }

    @Test
    fun `a write inside the conversation directory is exempt from gating, a symlink out of it is not`() {
        fs.repository.createDirectories()
        val inside = fs.conversation.resolve("notes.md")
        val escaped = fs.link("conversation/escape.md", fs.repository.resolve("notes.md"))

        val reviews = listOf(inside, escaped).map {
            runBlocking { tool.review(arguments("path" to it, "content" to "Hi.\n"), fs.context()) }
        }

        assertEquals(listOf(true, false), reviews.map { it.exempt })
    }

    @Test
    fun `a write in the conversation directory creates it and the subdirectories it needs`() {
        val directory = fs.root.resolve("later")
        val file = directory.resolve("games/answer.txt")
        val arguments = arguments("path" to file, "content" to "Apple.\n")

        val review = runBlocking { tool.review(arguments, fs.context(conversationDirectory = directory)) }
        val cleared = fs.context(
            conversationDirectory = directory,
            clearance = Clearance(ClearanceKind.NONE, null, review.snapshot),
        )
        val result = runBlocking { tool.execute(arguments, cleared) }

        assertEquals(Triple(true, false, "Apple.\n"), Triple(review.exempt, result.isError, file.readText()))
    }

    @Test
    fun `a new file creates the directories missing on its path, and a relative path goes into the workspace`() {
        val missing = fs.workspace.resolve("notes/today/todo.md")
        val blocked = fs.write("workspace/plain.txt", "Plain.\n")

        val written = write("notes/today/todo.md", "Tea.\n")
        val under = write("plain.txt/todo.md", "Tea.\n")
        val protected = write(fs.secret.resolve("new/todo.md"), "Tea.\n")

        assertEquals(
            listOf(
                "Created $missing: +1 -0 lines.",
                "problem: $blocked is no directory, so ${blocked.resolve("todo.md")} cannot be written.",
                "refused: ${fs.secret.resolve("new/todo.md")} is protected (a place the test protects): no file tool " +
                    "may write it.",
            ),
            listOf(written, under, protected),
        )
        assertEquals("Tea.\n", missing.readText())
        assertEquals(false, Files.exists(fs.secret.resolve("new")))
    }

    @Test
    fun `a new file of the agent's own persona changes lasting state`() {
        val persona = fs.persona.resolve("NOTES.md")

        val review = runBlocking { tool.review(arguments("path" to persona, "content" to "Tea.\n"), fs.context()) }

        assertEquals(ToolRisk.PERSISTENT_STATE, review.risk)
        assertEquals("Created $persona: +1 -0 lines.", write(persona, "Tea.\n"))
    }

    @Test
    fun `an existing file is replaced only once this conversation read it as it is`() {
        val file = fs.write("repo/notes.md", "Old.\n")

        val unread = write(file, "New.\n")
        read(file)
        val same = write(file, "Old.\n")
        val replaced = write(file, "New.\n")

        assertEquals(
            listOf(
                "problem: You have not read $file in this conversation: read it with fs.read first, then write it.",
                "problem: $file holds this text already.",
                "Wrote $file: +1 -1 lines.",
            ),
            listOf(unread, same, replaced),
        )
        assertEquals("New.\n", file.readText())
    }

    @Test
    fun `a written file can be edited without reading it again`() {
        val file = fs.repository.resolve("plan.md").also { it.parent.createDirectories() }
        write(file, "Step one.\n")
        val edits = JsonArray(
            listOf(
                buildJsonObject {
                    put("old", "one")
                    put("new", "two")
                },
            ),
        )

        val edited = fs.reviewedRun(EditTool(fs.floor, ledger, fs.places), arguments("path" to file, "edits" to edits))

        assertEquals("Edited $file: +1 -1 lines." to "Step two.\n", edited to file.readText())
    }

    @Test
    fun `protected places, directories and files that are no UTF-8 are refused`() {
        val dangling = fs.link("workspace/key", fs.secret.resolve("new.txt"))
        val latin = fs.bytes("repo/latin.txt", "café".toByteArray(Charsets.ISO_8859_1))

        assertEquals(
            listOf(
                "refused: ${fs.secret.resolve("key.txt")} is protected (a place the test protects): no file tool may " +
                    "write it.",
                "refused: $dangling is protected (a place the test protects): no file tool may write it.",
                "problem: ${fs.workspace} is a directory.",
                "problem: $latin is no UTF-8 text (it looks binary): the write tools change text files only.",
                "problem: Give 'content' as a string, the whole text of the file.",
            ),
            listOf(
                write(fs.secret.resolve("key.txt"), "x"),
                write("key", "x"),
                write(fs.workspace, "x"),
                write(latin, "x"),
                fs.reviewedRun(tool, arguments("path" to latin)),
            ),
        )
        assertEquals(
            "SECRET" to false,
            fs.secret.resolve("key.txt").readText() to fs.secret.resolve("new.txt").exists(),
        )
    }

    @Test
    fun `a file created after the review is not replaced`() {
        val file = fs.repository.resolve("new.md").also { it.parent.createDirectories() }
        val arguments = arguments("path" to file, "content" to "Mine.\n")
        val review = runBlocking { tool.review(arguments, fs.context()) }
        Files.writeString(file, "Theirs.\n")

        val result = runBlocking {
            tool.execute(arguments, fs.context(clearance = Clearance(ClearanceKind.MODE, null, review.snapshot)))
        }

        assertEquals(
            "$file changed since this call was reviewed: read it again with fs.read, then repeat the call.",
            result.text,
        )
        assertEquals("Theirs.\n", file.readText())
    }
}
