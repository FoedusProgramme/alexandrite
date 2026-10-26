package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillDiffTest {
    private fun skillDoc(vararg files: Triple<String, String, String>) = buildJsonObject {
        put(
            "files",
            buildJsonArray {
                for ((path, encoding, content) in files) {
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("encoding", encoding)
                            put("content", content)
                        },
                    )
                }
            },
        )
    }

    @Test
    fun `a file only in the new content is added, with its size and a diff against nothing`() {
        val after = skillDoc(Triple("SKILL.md", "utf8", "# Hi"))

        val changes = skillFileChanges(null, after)

        val change = changes.single()
        assertEquals("SKILL.md" to SkillFileStatus.ADDED, change.path to change.status)
        assertEquals(null to 4, change.sizeBefore to change.sizeAfter)
        assertEquals("+ # Hi", change.textDiff)
    }

    @Test
    fun `a file only in the old content is removed, with its size`() {
        val before = skillDoc(Triple("SKILL.md", "utf8", "# Bye"))

        val change = skillFileChanges(before, null).single()

        assertEquals("SKILL.md" to SkillFileStatus.REMOVED, change.path to change.status)
        assertEquals(5 to null, change.sizeBefore to change.sizeAfter)
    }

    @Test
    fun `a file with different content is changed, with a line diff`() {
        val before = skillDoc(Triple("SKILL.md", "utf8", "line one\nline two\nline three"))
        val after = skillDoc(Triple("SKILL.md", "utf8", "line one\nline two changed\nline three"))

        val change = skillFileChanges(before, after).single()

        assertEquals(SkillFileStatus.CHANGED, change.status)
        assertContains(change.textDiff.orEmpty(), "- line two")
        assertContains(change.textDiff.orEmpty(), "+ line two changed")
        assertContains(change.textDiff.orEmpty(), "  line one")
        assertContains(change.textDiff.orEmpty(), "  line three")
    }

    @Test
    fun `identical content on both sides is not a change`() {
        val doc = skillDoc(Triple("SKILL.md", "utf8", "# Same"))

        assertTrue(skillFileChanges(doc, doc).isEmpty())
    }

    @Test
    fun `a base64 file's diff is never attempted`() {
        val before = skillDoc(Triple("icon.bin", "base64", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
        val after = skillDoc(Triple("icon.bin", "base64", Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6))))

        val change = skillFileChanges(before, after).single()

        assertEquals(SkillFileStatus.CHANGED, change.status)
        assertNull(change.textDiff)
        assertEquals(3 to 3, change.sizeBefore to change.sizeAfter)
    }

    @Test
    fun `several files at once are each reported, added, changed and removed together`() {
        val before = skillDoc(
            Triple("SKILL.md", "utf8", "old"),
            Triple("gone.txt", "utf8", "bye"),
        )
        val after = skillDoc(
            Triple("SKILL.md", "utf8", "new"),
            Triple("added.txt", "utf8", "hi"),
        )

        val changes = skillFileChanges(before, after).associateBy { it.path }

        assertEquals(SkillFileStatus.CHANGED, changes["SKILL.md"]?.status)
        assertEquals(SkillFileStatus.REMOVED, changes["gone.txt"]?.status)
        assertEquals(SkillFileStatus.ADDED, changes["added.txt"]?.status)
    }

    @Test
    fun `a too-large text file's diff is left out, but the change itself is still reported`() {
        val bigOld = "x".repeat(70_000)
        val bigNew = "y".repeat(70_000)
        val before = skillDoc(Triple("BIG.md", "utf8", bigOld))
        val after = skillDoc(Triple("BIG.md", "utf8", bigNew))

        val change = skillFileChanges(before, after).single()

        assertEquals(SkillFileStatus.CHANGED, change.status)
        assertNull(change.textDiff)
    }

    @Test
    fun `neither side given is no files at all`() {
        assertTrue(skillFileChanges(null, null).isEmpty())
    }
}
