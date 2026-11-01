package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.tools.media.AWT_CODEC
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditToolTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var fs: FsFixture
    private lateinit var ledger: ReadLedger
    private lateinit var reader: ReadTool
    private lateinit var tool: EditTool

    @BeforeEach
    fun setUp() {
        fs = FsFixture(directory)
        ledger = ReadLedger()
        reader = ReadTool(fs.floor, ledger, AWT_CODEC)
        tool = EditTool(fs.floor, ledger, fs.places)
    }

    private fun read(file: Any, conversation: String = "test", vararg more: Pair<String, Any>) {
        val result =
            runBlocking { reader.execute(arguments("path" to file, *more), fs.context(conversation = conversation)) }
        assertEquals(false, result.isError, result.text)
    }

    private fun call(file: Any, vararg edits: Edit): JsonObject = arguments(
        "path" to file,
        "edits" to JsonArray(
            edits.map { (old, new, all) ->
                buildJsonObject {
                    put("old", old)
                    put("new", new)
                    if (all) put("all", true)
                }
            },
        ),
    )

    private fun edit(file: Any, vararg edits: Edit, conversation: String = "test"): String =
        fs.reviewedRun(tool, call(file, *edits), conversation)

    private fun review(arguments: JsonObject): CallReview = runBlocking { tool.review(arguments, fs.context()) }

    // The read before the write.

    @Test
    fun `a file is edited only once this conversation read it`() {
        val file = fs.write("repo/notes.md", "Buy tea.\n")
        val unread = "problem: You have not read $file in this conversation: read it with fs.read first, then write it."

        val before = edit(file, Edit("tea", "coffee"))
        read(file, conversation = "other")
        val otherRead = edit(file, Edit("tea", "coffee"))
        read(file)
        val after = edit(file, Edit("tea", "coffee"))

        assertEquals(listOf(unread, unread, "Edited $file: +1 -1 lines."), listOf(before, otherRead, after))
        assertEquals("Buy coffee.\n", file.readText())
    }

    @Test
    fun `a file that changed since it was read is refused until it is read again`() {
        val file = fs.write("repo/notes.md", "Buy tea.\n")
        read(file)
        file.writeText("Buy green tea.\n")

        val stale = edit(file, Edit("green tea", "coffee"))
        read(file)
        val fresh = edit(file, Edit("green tea", "coffee"))

        assertEquals(
            listOf(
                "problem: $file changed since you read it: read it again with fs.read first.",
                "Edited $file: +1 -1 lines.",
            ),
            listOf(stale, fresh),
        )
    }

    @Test
    fun `a read of a few lines lets any line be edited, and so does an edit before the next one`() {
        val file = fs.write("repo/lines.txt", (1..100).joinToString("") { "line $it\n" })
        read(file, "test", "offset" to 1, "limit" to 2)

        val first = edit(file, Edit("line 90\n", "ninety\n"))
        val second = edit(file, Edit("line 91\n", "ninety-one\n"))

        assertEquals(listOf("Edited $file: +1 -1 lines.", "Edited $file: +1 -1 lines."), listOf(first, second))
        assertEquals(listOf("ninety", "ninety-one"), file.readText().lines().subList(89, 91))
    }

    @Test
    fun `the ledger forgets the conversations and the files it used least recently`() {
        val file = Path.of("/repo/a.txt")
        repeat(MAX_CONVERSATIONS) { ledger.record(ConversationId("c$it"), file, "sha256:$it") }
        ledger.hash(ConversationId("c0"), file)
        ledger.record(ConversationId("new"), file, "sha256:new")
        val conversations = listOf("c0", "c1", "c2", "new").map { ledger.hash(ConversationId(it), file) }
        val many = ConversationId("many")
        repeat(MAX_FILES + 1) { ledger.record(many, Path.of("/repo/$it.txt"), "sha256:$it") }
        val files = listOf(0, 1).map { ledger.hash(many, Path.of("/repo/$it.txt")) }

        assertEquals(listOf("sha256:0", null, "sha256:2", "sha256:new"), conversations)
        assertEquals(listOf(null, "sha256:1"), files)
    }

    // Matching.

    @Test
    fun `the old text must occur once, or at least once where every occurrence is replaced`() {
        val file = fs.write("repo/menu.txt", "tea\ncake\ntea\n")
        read(file)

        val missing = edit(file, Edit("coffee", "tea"))
        val twice = edit(file, Edit("tea", "coffee"))
        val missingAll = edit(file, Edit("coffee", "tea", all = true))
        val all = edit(file, Edit("tea", "coffee", all = true))

        val absent = "problem: The old text is not in $file: read the file again with fs.read if you are unsure of " +
            "its text."
        assertEquals(
            listOf(
                absent,
                "problem: The old text occurs 2 times in $file: give more of the text around it so that it occurs " +
                    "once, or set 'all' to replace every occurrence.",
                absent,
                "Edited $file: +2 -2 lines.",
            ),
            listOf(missing, twice, missingAll, all),
        )
        assertEquals("coffee\ncake\ncoffee\n", file.readText())
    }

    @Test
    fun `an empty old text, a missing file and edits that change nothing are refused`() {
        val file = fs.write("repo/menu.txt", "tea\n")
        read(file)

        assertEquals(
            listOf(
                "problem: The 'old' text of edit 1 is empty: give the text to replace, or use fs.write.",
                "problem: Nothing exists at ${fs.repository.resolve("gone.txt")}: create it with fs.write.",
                "problem: The edits leave $file as it is.",
                "problem: Give 'edits' as a list of {\"old\": …, \"new\": …} objects.",
            ),
            listOf(
                edit(file, Edit("", "x")),
                edit(fs.repository.resolve("gone.txt"), Edit("a", "b")),
                edit(file, Edit("tea", "coffee"), Edit("coffee", "tea")),
                fs.reviewedRun(tool, arguments("path" to file, "edits" to JsonArray(emptyList()))),
            ),
        )
    }

    @Test
    fun `the edits apply in order, and one that fails leaves the file untouched also when it runs`() {
        val file = fs.write("repo/notes.txt", "a\nb\n")
        read(file)

        val chained = edit(file, Edit("a", "x"), Edit("x\nb", "y\nz"))
        val failing = call(file, Edit("y", "w"), Edit("y", "v"))
        val reviewed = review(failing)
        val ran = runBlocking {
            tool.execute(failing, fs.context(clearance = Clearance(ClearanceKind.NONE, null, hashOf(file))))
        }

        assertEquals("Edited $file: +2 -2 lines.", chained)
        val problem = "The old text of edit 2 is not in $file once the edits before it are applied: read the file " +
            "again with fs.read if you are unsure of its text."
        assertEquals(problem to (true to problem), reviewed.problem to (ran.isError to ran.text))
        assertEquals("y\nz\n", file.readText())
    }

    // Encoding and line breaks.

    @Test
    fun `a CRLF file keeps its line breaks and its byte order mark`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = fs.bytes("repo/win.txt", bom + "one\r\ntwo\r\nthree\r\n".toByteArray())
        read(file)

        val result = edit(file, Edit("one\ntwo", "uno\ndos\nmas"))

        assertEquals("Edited $file: +3 -2 lines.", result)
        assertContentEquals(bom + "uno\r\ndos\r\nmas\r\nthree\r\n".toByteArray(), file.readBytes())
    }

    // Where it writes.

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a file with more than one hard link is refused while the floor protects anything`() {
        val file = fs.write("repo/notes.md", "Notes.")
        Files.createLink(fs.root.resolve("repo/other.md"), file)
        val unprotected = EditTool(fs.unprotected, ledger, fs.places)
        ReadTool(fs.unprotected, ledger, AWT_CODEC).call(fs.context(), "path" to file)

        assertEquals(
            "refused: $file has more than one hard link, so it may be another name of a protected file: it is not " +
                "written.",
            edit(file, Edit("Notes", "More")),
        )
        assertEquals(
            "Edited $file: +1 -1 lines.",
            fs.reviewedRun(unprotected, call(file, Edit("Notes", "More"))),
        )
        assertEquals("More.", file.readText())
    }

    // The persona.

    @Test
    fun `an edit of the agent's own persona file changes lasting state, outside a delegated run`() {
        val persona = fs.write("persona/PERSONA.md", "I like tea.\n")
        val notes = fs.write("workspace/notes.md", "Tea.\n")
        read(persona)
        read(notes)
        val delegated = testTurn(conversation = ConversationId("test"), kind = TurnKind.DELEGATED)

        val own = review(call(persona, Edit("tea", "coffee")))
        val other = review(call(notes, Edit("Tea", "Coffee")))
        val run = runBlocking {
            tool.review(call(persona, Edit("tea", "coffee")), testToolContext(delegated, workspace = fs.workspace))
        }

        assertEquals(listOf(ToolRisk.PERSISTENT_STATE, null, null), listOf(own.risk, other.risk, run.risk))
        assertEquals(
            "Edited $persona: +1 -1 lines.",
            fs.reviewedRun(tool, call(persona, Edit("tea", "coffee"))),
        )
        assertEquals("I like coffee.\n", persona.readText())
    }

    @Test
    fun `a symbolic link is written at its target when the floor allows the target, and refused when not`() {
        val target = fs.write("repo/README.md", "Hello.\n")
        val link = fs.link("workspace/readme", target)
        val keys = fs.link("workspace/keys", fs.home.resolve(".ssh"))
        read(link)

        val result = edit("readme", Edit("Hello", "Hi"))
        val preview = review(call("readme", Edit("Hi", "Hey"))).preview as ReviewPreview.Diff
        val protected = edit("keys/id_rsa", Edit("KEY", "x"))

        assertEquals("Edited ${fs.workspace.resolve("readme")}: +1 -1 lines.", result)
        assertEquals("Hi.\n" to target, target.readText() to preview.path)
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(
            "refused: ${keys.resolve("id_rsa")} is protected (SSH keys): no file tool may write it.",
            protected,
        )
        assertEquals("KEY", fs.home.resolve(".ssh/id_rsa").readText())
    }

    @Test
    fun `a relative path is edited in the workspace`() {
        val file = fs.write("workspace/plan.md", "Step one.\n")
        read("plan.md")

        assertEquals("Edited $file: +1 -1 lines.", edit("plan.md", Edit("one", "two")))
        assertEquals("Step two.\n", file.readText())
    }

    // Review and execution.

    @Test
    fun `the review shows a unified diff of the canonical file and snapshots its hash`() {
        val file = fs.write("repo/abc.txt", "a\nb\nc\n")
        read(file)

        val review = review(call(file, Edit("b", "B")))

        assertEquals(
            CallReview.builder()
                .preview(ReviewPreview.Diff(file, "--- $file\n+++ $file\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n"))
                .snapshot(hashOf(file))
                .reads(listOf(file))
                .build(),
            review,
        )
        assertEquals("a\nb\nc\n", file.readText())
    }

    @Test
    fun `a file that changed after the review is not written`() {
        val file = fs.write("repo/notes.md", "Buy tea.\n")
        read(file)
        val arguments = call(file, Edit("tea", "coffee"))
        val review = review(arguments)
        file.writeText("Buy more tea.\n")

        val changed = runBlocking {
            tool.execute(arguments, fs.context(clearance = Clearance(ClearanceKind.HUMAN, null, review.snapshot)))
        }

        assertEquals(
            "$file changed since this call was reviewed: read it again with fs.read, then repeat the call.",
            changed.text,
        )
        assertEquals("Buy more tea.\n", file.readText())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a write that fails leaves the file as it was and nothing beside it, and keeps the permissions otherwise`() {
        val file = fs.write("repo/tool.sh", "echo tea\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"))
        read(file)
        Files.setPosixFilePermissions(fs.repository, PosixFilePermissions.fromString("r-x------"))
        try {
            assumeFalse(Files.isWritable(fs.repository), "the directory stays writable (running as root?)")

            val failed = edit(file, Edit("tea", "coffee"))

            assertEquals("failed: $file could not be written: access denied.", failed)
            assertEquals("echo tea\n", file.readText())
            assertEquals(listOf(file), fs.repository.listDirectoryEntries())
        } finally {
            Files.setPosixFilePermissions(fs.repository, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals("Edited $file: +1 -1 lines.", edit(file, Edit("tea", "coffee")))
        assertEquals(
            "rwxr-x---",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)),
        )
    }
}

private data class Edit(val old: String, val new: String, val all: Boolean = false)
