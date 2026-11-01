package org.unitedsoftware.alexandrite.testkit.media

import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.testkit.ImageCodecCheck
import org.unitedsoftware.alexandrite.testkit.store.expect
import org.unitedsoftware.alexandrite.testkit.store.expectEqual
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.random.Random

internal val IMAGE_CODEC_CHECKS: List<ImageCodecCheck> = listOf(
    ImageCodecCheck("the size of an image is read from its bytes") {
        expectEqual(ImageDimensions(40, 20), codec.dimensions(png(40, 20), "image/png"), "the size of an image")
        expectEqual(
            ImageDimensions(7, 30),
            codec.dimensions(png(7, 30, alpha = true), "image/png"),
            "the size of an image with an alpha channel",
        )
    },
    ImageCodecCheck("an image within the limits passes unchanged") {
        val image = png(40, 20)

        val (fitted, size) = fit(image, maxSide = 100, maxBytes = 1_000_000)

        expect(fitted.bytes.contentEquals(image)) { "The image was re-encoded as ${fitted.mediaType}." }
        expectEqual("image/png", fitted.mediaType, "the media type")
        expectEqual(ImageDimensions(40, 20), size, "the size")
    },
    ImageCodecCheck("an opaque image over the longer side is scaled down in proportion as jpeg") {
        val (fitted, size) = fit(png(400, 200), maxSide = 100, maxBytes = 1_000_000)

        expectEqual("image/jpeg", fitted.mediaType, "the media type")
        expectEqual(ImageDimensions(100, 50), size, "the size")
    },
    ImageCodecCheck("an image with an alpha channel over the longer side is scaled down in proportion as png") {
        val (fitted, size) = fit(png(400, 200, alpha = true), maxSide = 100, maxBytes = 1_000_000)

        expectEqual("image/png", fitted.mediaType, "the media type")
        expectEqual(ImageDimensions(100, 50), size, "the size")
    },
    ImageCodecCheck("an image over the byte limit is re-encoded within it") {
        val random = Random(7)
        val noise = png(256, 256) { _, _ -> random.nextInt() or OPAQUE }
        expect(noise.size > 100_000) { "The noise compressed to ${noise.size} bytes." }

        val fitted = fit(noise, maxSide = 1_000, maxBytes = 40_000).first

        expectEqual("image/jpeg", fitted.mediaType, "the media type")
    },
    ImageCodecCheck("bytes that are no image have no size and fit to nothing") {
        val text = "Not an image, though it is called one.".encodeToByteArray()

        expectEqual(null, codec.dimensions(text, "image/png"), "the size of the text")
        expectEqual(null, codec.fitted(text, "image/png", 100, 1_000_000), "the text fitted")
    },
)

private const val OPAQUE = 0xFF shl 24

/** A png of [width] by [height] in 8-bit RGB, or RGBA when [alpha], whose pixels [argb] colours. */
private fun png(
    width: Int,
    height: Int,
    alpha: Boolean = false,
    argb: (x: Int, y: Int) -> Int = { x, y -> gradient(x, y, width, height, alpha) },
): ByteArray {
    val pixels = ByteArrayOutputStream()
    for (y in 0 until height) {
        pixels.write(0)
        for (x in 0 until width) {
            val pixel = argb(x, y)
            pixels.write(pixel shr 16 and 0xFF)
            pixels.write(pixel shr 8 and 0xFF)
            pixels.write(pixel and 0xFF)
            if (alpha) pixels.write(pixel ushr 24)
        }
    }
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { it.write(pixels.toByteArray()) }
    val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(if (alpha) 6 else 2).array()
    return ByteArrayOutputStream().apply {
        write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        chunk("IHDR", header)
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", ByteArray(0))
    }.toByteArray()
}

private fun gradient(x: Int, y: Int, width: Int, height: Int, alpha: Boolean): Int {
    val opacity = if (alpha) 64 + x * 191 / width else 0xFF
    return (opacity shl 24) or (x * 255 / width shl 16) or (y * 255 / height shl 8) or 0x80
}

private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
    val name = type.encodeToByteArray()
    val crc = CRC32().apply {
        update(name)
        update(data)
    }
    write(ByteBuffer.allocate(4).putInt(data.size).array())
    write(name)
    write(data)
    write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
}
