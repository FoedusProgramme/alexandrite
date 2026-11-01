package org.unitedsoftware.alexandrite.internal.io

import org.junit.jupiter.api.io.TempDir
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextFilesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a UTF-8 file reads as its text`() {
        val file = Files.write(directory.resolve("text"), "café\n".toByteArray(Charsets.UTF_8))

        assertEquals("café\n", readUtf8(file))
    }

    @Test
    fun `a file of no UTF-8 is refused`() {
        val file = Files.write(directory.resolve("latin"), byteArrayOf(0x63, 0xe9.toByte()))

        assertFailsWith<CharacterCodingException> { readUtf8(file) }
    }
}
