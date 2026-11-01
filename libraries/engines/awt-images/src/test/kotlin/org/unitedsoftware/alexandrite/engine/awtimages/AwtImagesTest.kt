package org.unitedsoftware.alexandrite.engine.awtimages

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.media.imageType
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwtImagesTest {
    @Test
    fun `the plugin binds a codec that reads and fits images`() {
        var bound: ImageCodec? = null
        runBlocking {
            PluginHarness.builder(AlexandriteEngineAwtImagesIndex()).build().run {
                bound =
                    get(key<ImageCodec>())
            }
        }
        val codec = assertIs<AwtImageCodec>(bound)
        val png = encode(image(40, 20, alpha = false), "png")

        val fitted = assertNotNull(codec.fitted(png, "image/png", 10, 1_000_000))

        assertEquals(ImageDimensions(40, 20), codec.dimensions(png, "image/png"))
        assertEquals("image/jpeg" to ImageDimensions(10, 5), fitted.mediaType to fitted.dimensions)
    }

    @Test
    fun `each format's magic bytes are sniffed, and unknown bytes are not`() {
        val cases = mapOf(
            "png" to (encode(image(4, 4, alpha = false), "png") to "image/png"),
            "jpeg" to (encode(image(4, 4, alpha = false), "jpg") to "image/jpeg"),
            "gif" to (encode(image(4, 4, alpha = false), "gif") to "image/gif"),
            "bmp" to (encode(image(4, 4, alpha = false), "bmp") to "image/bmp"),
            "tiff" to (encode(image(4, 4, alpha = false), "tiff") to "image/tiff"),
            "webp" to (fakeWebp(10, 10, 40) to "image/webp"),
            "heic" to (fakeHeic("heic") to "image/heic"),
            "heif" to (fakeHeic("mif1") to "image/heif"),
            "text" to ("hello there, not an image".toByteArray() to null),
        )

        for ((label, case) in cases) assertEquals(case.second, imageType(case.first), label)
    }

    @Test
    fun `a file's bytes are sniffed over its name`(@TempDir directory: Path) {
        val png = encode(image(4, 4, alpha = false), "png")
        val misnamed = directory.resolve("photo.jpg")
        Files.write(misnamed, png)

        assertEquals("image/png", imageType(Files.readAllBytes(misnamed)))
    }

    @Test
    fun `a png within the limits passes through unchanged`() {
        val png = encode(image(100, 60, alpha = false), "png")

        val normalized = assertNotNullReport(AwtImages.normalized(png))

        assertContentEquals(png, normalized.bytes)
        assertEquals("image/png" to (100 to 60), normalized.mediaType to (normalized.width to normalized.height))
    }

    @Test
    fun `a jpeg wider than the longer side is scaled down proportionally`() {
        val jpeg = encode(image(2000, 500, alpha = false), "jpg")

        val normalized = assertNotNullReport(AwtImages.normalized(jpeg, maxSide = 1568, maxBytes = 5_000_000))

        assertEquals("image/jpeg", normalized.mediaType)
        assertEquals(1568 to 392, normalized.width to normalized.height)
    }

    @Test
    fun `a wide image with alpha is scaled and re-encoded as png`() {
        val png = encode(image(2000, 500, alpha = true), "png")

        val normalized = assertNotNullReport(AwtImages.normalized(png, maxSide = 1568, maxBytes = 5_000_000))

        assertEquals("image/png", normalized.mediaType)
        assertEquals(1568 to 392, normalized.width to normalized.height)
        val decoded = ImageIO.read(ByteArrayInputStream(normalized.bytes))
        assertTrue(decoded.colorModel.hasAlpha())
    }

    @Test
    fun `a bmp is converted away from its own format`() {
        val bmp = encode(image(300, 150, alpha = false), "bmp")

        val normalized = assertNotNullReport(AwtImages.normalized(bmp))

        assertNotEquals("image/bmp", normalized.mediaType)
        assertEquals(300 to 150, normalized.width to normalized.height)
    }

    @Test
    fun `the jpeg ladder gives up after four halvings when the budget can never be met`() {
        val png = encode(image(1568, 1568, alpha = false), "png")

        val normalized = assertNotNullReport(AwtImages.normalized(png, maxSide = 1568, maxBytes = 1))

        assertEquals("image/jpeg", normalized.mediaType)
        assertEquals(98 to 98, normalized.width to normalized.height)
    }

    @Test
    fun `a webp passes through within its budget, its canvas size read from its header`() {
        val webp = fakeWebp(width = 300, height = 200, totalSize = 1000)

        val normalized = assertNotNullReport(AwtImages.normalized(webp, maxBytes = 2000))

        assertContentEquals(webp, normalized.bytes)
        assertEquals("image/webp" to (300 to 200), normalized.mediaType to (normalized.width to normalized.height))
    }

    @Test
    fun `a webp over its budget cannot be shrunk`() {
        val webp = fakeWebp(width = 300, height = 200, totalSize = 2000)

        assertNull(AwtImages.normalized(webp, maxBytes = 1000))
    }

    @Test
    fun `an image this version has no decoder for is refused`() {
        assertNull(AwtImages.normalized(fakeHeic("heic")))
        assertNull(AwtImages.normalized("not an image at all".toByteArray()))
    }

    @Test
    fun `an image above 50 megapixels is refused`() {
        assertNull(AwtImages.normalized(fakeBombBmp(width = 10_000, height = 10_000)))
    }

    private fun <T> assertNotNullReport(value: T?): T = value ?: error("expected a normalized image, got null")
}

private fun image(width: Int, height: Int, alpha: Boolean): BufferedImage {
    val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val red = x * 255 / maxOf(1, width - 1)
            val green = y * 255 / maxOf(1, height - 1)
            val blue = (x + y) % 256
            val a = if (alpha) x * 255 / maxOf(1, width - 1) else 255
            image.setRGB(x, y, (a shl 24) or (red shl 16) or (green shl 8) or blue)
        }
    }
    return image
}

private fun encode(image: BufferedImage, format: String): ByteArray {
    val output = ByteArrayOutputStream()
    ImageIO.write(image, format, output)
    return output.toByteArray()
}

/** A `RIFF`/`WEBP`/`VP8X` header declaring [width] by [height], padded with zeros to [totalSize] bytes. */
private fun fakeWebp(width: Int, height: Int, totalSize: Int): ByteArray {
    val bytes = ByteArray(totalSize)
    "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
    "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
    "VP8X".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
    writeLe24(bytes, 24, width - 1)
    writeLe24(bytes, 27, height - 1)
    return bytes
}

/** An ISOBMFF `ftyp` box whose major brand is [brand], with no HEIC/HEIF data behind it. */
private fun fakeHeic(brand: String): ByteArray {
    val bytes = ByteArray(20)
    "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, 4)
    brand.toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
    return bytes
}

/** A BMP header alone, declaring [width] by [height], with no pixel data behind it. */
private fun fakeBombBmp(width: Int, height: Int): ByteArray {
    val bytes = ByteArray(54)
    bytes[0] = 'B'.code.toByte()
    bytes[1] = 'M'.code.toByte()
    writeLe32(bytes, 10, 54)
    writeLe32(bytes, 14, 40)
    writeLe32(bytes, 18, width)
    writeLe32(bytes, 22, height)
    writeLe16(bytes, 26, 1)
    writeLe16(bytes, 28, 24)
    return bytes
}

private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value shr 8).toByte()
}

private fun writeLe24(bytes: ByteArray, offset: Int, value: Int) {
    writeLe16(bytes, offset, value)
    bytes[offset + 2] = (value shr 16).toByte()
}

private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
    writeLe24(bytes, offset, value)
    bytes[offset + 3] = (value shr 24).toByte()
}
