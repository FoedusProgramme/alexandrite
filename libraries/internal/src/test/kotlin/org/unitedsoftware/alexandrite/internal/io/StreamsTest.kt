package org.unitedsoftware.alexandrite.internal.io

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StreamsTest {
    @Test
    fun `a read takes the count of bytes and leaves the rest`() {
        val input = ByteArray(20_000) { it.toByte() }.inputStream()

        assertContentEquals(ByteArray(10_000) { it.toByte() }, input.readAtMost(10_000))
        assertEquals(10_000 % 256, input.read())
    }

    @Test
    fun `a read of a shorter stream takes what it holds`() {
        assertContentEquals("abc".toByteArray(), "abc".byteInputStream().readAtMost(10))
        assertContentEquals(ByteArray(0), "abc".byteInputStream().readAtMost(0))
    }

    @Test
    fun `a read goes on past short reads`() {
        val trickling = object : InputStream() {
            private var next = 0

            override fun read(): Int = if (next < 5) next++ else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val byte = read()
                if (byte < 0) return -1
                b[off] = byte.toByte()
                return 1
            }
        }

        assertContentEquals(byteArrayOf(0, 1, 2, 3), trickling.readAtMost(4))
    }
}
