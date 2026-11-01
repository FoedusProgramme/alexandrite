package org.unitedsoftware.alexandrite.internal.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The next [count] bytes of this stream, fewer where it ends first. */
public fun InputStream.readAtMost(count: Int): ByteArray {
    require(count >= 0) { "A count of bytes to read is not negative, was $count." }
    val output = ByteArrayOutputStream(minOf(count, BUFFER_BYTES))
    val buffer = ByteArray(minOf(count, BUFFER_BYTES))
    var left = count
    while (left > 0) {
        val read = read(buffer, 0, minOf(left, buffer.size))
        if (read < 0) break
        output.write(buffer, 0, read)
        left -= read
    }
    return output.toByteArray()
}

private const val BUFFER_BYTES = 8 * 1024
