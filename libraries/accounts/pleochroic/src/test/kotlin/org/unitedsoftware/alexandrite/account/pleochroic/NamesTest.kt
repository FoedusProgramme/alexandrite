package org.unitedsoftware.alexandrite.account.pleochroic

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamesTest {
    @Test
    fun `agent ids follow the plugin id grammar`() {
        assertTrue(isValidAgentId("helper"))
        assertTrue(isValidAgentId("my-helper2"))
        assertFalse(isValidAgentId(".."))
        assertFalse(isValidAgentId("../../etc"))
        assertFalse(isValidAgentId("Helper"))
        assertFalse(isValidAgentId("helper/x"))
        assertFalse(isValidAgentId("helper\\x"))
        assertFalse(isValidAgentId(""))
        assertFalse(isValidAgentId("a".repeat(300)))
    }

    @Test
    fun `skill scopes accept only global or a valid agent id`() {
        assertTrue(isValidSkillScope("global"))
        assertTrue(isValidSkillScope("agent:helper"))
        assertFalse(isValidSkillScope("agent:../../etc"))
        assertFalse(isValidSkillScope("agent:"))
        assertFalse(isValidSkillScope("project:abc"))
        assertFalse(isValidSkillScope(""))
    }

    @Test
    fun `skill names follow the Agent Skills pattern`() {
        assertTrue(isValidSkillName("pdf-tools"))
        assertFalse(isValidSkillName("../../etc/passwd"))
        assertFalse(isValidSkillName("Pdf"))
        assertFalse(isValidSkillName(""))
    }

    @Test
    fun `instructions ids accept only AGENTS-md or a valid agent's PERSONA-md`() {
        assertTrue(isValidInstructionsId("AGENTS.md"))
        assertTrue(isValidInstructionsId("agents/helper/PERSONA.md"))
        assertFalse(isValidInstructionsId("agents/../../../etc/passwd/PERSONA.md"))
        assertFalse(isValidInstructionsId("../AGENTS.md"))
        assertFalse(isValidInstructionsId("agents//PERSONA.md"))
        assertFalse(isValidInstructionsId("AGENTS.MD"))
    }

    @Test
    fun `secret names follow the account's name format`() {
        assertTrue(isValidSecretName("MY_TOKEN"))
        assertTrue(isValidSecretName("A"))
        assertFalse(isValidSecretName("my_token"))
        assertFalse(isValidSecretName("1TOKEN"))
        assertFalse(isValidSecretName("TOKEN\nINJECTED=1"))
        assertFalse(isValidSecretName("A".repeat(65)))
    }

    @Test
    fun `a secret value must not be able to inject another line into the secrets file`() {
        assertTrue(isSafeSecretValue("hunter2"))
        assertFalse(isSafeSecretValue("x\nEVIL=1"))
        assertFalse(isSafeSecretValue("x\r\nEVIL=1"))
        assertFalse(isSafeSecretValue("x\u0000y"))
    }

    @Test
    fun `relative paths reject traversal, absolute forms, backslashes, drive letters and NUL`() {
        assertTrue(isSafeRelativePath("SKILL.md"))
        assertTrue(isSafeRelativePath("assets/icon.bin"))
        assertFalse(isSafeRelativePath("../escape"))
        assertFalse(isSafeRelativePath("a/../../b"))
        assertFalse(isSafeRelativePath("/etc/passwd"))
        assertFalse(isSafeRelativePath("\\\\server\\share"))
        assertFalse(isSafeRelativePath("a\\b"))
        assertFalse(isSafeRelativePath("C:\\Windows\\System32"))
        assertFalse(isSafeRelativePath("a/\u0000/b"))
        assertFalse(isSafeRelativePath(""))
        assertFalse(isSafeRelativePath("a//b"))
    }

    @Test
    fun `safeChild refuses a traversal name and anything but a single segment`(@TempDir tmp: Path) {
        assertEquals(tmp.resolve("my-skill"), safeChild(tmp, "my-skill"))
        assertNull(safeChild(tmp, "../escape"))
        assertNull(safeChild(tmp, "a/b"))
        assertNull(safeChild(tmp, ""))
    }

    @Test
    fun `safeChild refuses a name that is already a symlink`(@TempDir tmp: Path) {
        val target = tmp.resolve("elsewhere").also { it.createDirectories() }
        val link = tmp.resolve("linked")
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: Exception) {
            return // symlinks unsupported here (permission or file system); nothing to assert
        }

        assertNull(safeChild(tmp, "linked"))
    }

    @Test
    fun `safeDescendant stays under root and refuses traversal and absolute forms`(@TempDir tmp: Path) {
        assertEquals(tmp.resolve("a/b.txt").normalize(), safeDescendant(tmp, "a/b.txt"))
        assertNull(safeDescendant(tmp, "../escape"))
        assertNull(safeDescendant(tmp, "/etc/passwd"))
        assertNull(safeDescendant(tmp, "a\\..\\..\\b"))
    }

    @Test
    fun `isFreeOfSymlinks is true for a plain directory and false once a parent is a symlink`(@TempDir tmp: Path) {
        val real = tmp.resolve("real").also { it.createDirectories() }
        assertTrue(isFreeOfSymlinks(real, real.resolve("child")))

        val target = tmp.resolve("target").also { it.createDirectories() }
        val linkedRoot = tmp.resolve("linked-root")
        try {
            Files.createSymbolicLink(linkedRoot, target)
        } catch (e: Exception) {
            return
        }
        val child = linkedRoot.resolve("child")
        assertFalse(isFreeOfSymlinks(linkedRoot, child))
    }

    @Test
    fun `safe config ids reject control characters and oversized strings`() {
        assertTrue(isSafeConfigId("openrouter/work"))
        assertFalse(isSafeConfigId(""))
        assertFalse(isSafeConfigId("a\u0000b"))
        assertFalse(isSafeConfigId("a".repeat(500)))
    }
}
