package org.unitedsoftware.alexandrite.internal.media

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class UnchangedImagesTest {
    @Test
    fun `the headers of png, jpeg, gif and webp tell their size as ImageIO reads it`() {
        for (format in listOf("png", "jpg", "gif")) {
            val bytes = encoded(37, 21, format)
            val type = imageType(bytes)!!
            val decoded = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(decoded.width to decoded.height, headerDimensions(bytes, type), format)
            assertEquals(37 to 21, headerDimensions(bytes, type), format)
        }
        assertEquals(300 to 200, headerDimensions(webp(300, 200, 40), "image/webp"))
        assertNull(headerDimensions(encoded(4, 4, "bmp"), "image/bmp"))
        assertNull(headerDimensions(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg"))
    }

    @Test
    fun `an image a model takes as it is passes unchanged, every other is refused`() {
        val png = encoded(100, 60, "png")
        val passed = unchangedImage(png, "image/png")!!
        assertSame(png, passed.bytes)
        assertEquals("image/png" to (100 to 60), passed.mediaType to (passed.width to passed.height))
        assertEquals(300 to 200, unchangedImage(webp(300, 200, 40), "image/webp")!!.let { it.width to it.height })

        assertNull(unchangedImage(encoded(2000, 10, "png"), "image/png"), "too wide")
        assertNull(unchangedImage(png, "image/png", maxBytes = png.size - 1), "too large")
        assertNull(unchangedImage(encoded(10, 10, "bmp"), "image/bmp"), "a type no model takes")
        assertNull(unchangedImage(webp(300, 200, 2000), "image/webp", maxBytes = 1000), "a webp too large")
    }
}

private fun encoded(width: Int, height: Int, format: String): ByteArray {
    val output = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output)
    return output.toByteArray()
}

/** A `RIFF`/`WEBP`/`VP8X` header declaring [width] by [height], padded with zeros to [totalSize] bytes. */
private fun webp(width: Int, height: Int, totalSize: Int): ByteArray {
    val bytes = ByteArray(totalSize)
    "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
    "WEBPVP8X".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
    for ((offset, value) in listOf(24 to width - 1, 27 to height - 1)) {
        for (i in 0..2) bytes[offset + i] = (value shr (8 * i)).toByte()
    }
    return bytes
}
