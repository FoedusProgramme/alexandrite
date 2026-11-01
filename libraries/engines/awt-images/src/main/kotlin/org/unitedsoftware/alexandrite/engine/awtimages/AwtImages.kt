package org.unitedsoftware.alexandrite.engine.awtimages

import org.unitedsoftware.alexandrite.internal.media.MAX_IMAGE_BYTES
import org.unitedsoftware.alexandrite.internal.media.MAX_IMAGE_SIDE
import org.unitedsoftware.alexandrite.internal.media.NormalizedImage
import org.unitedsoftware.alexandrite.internal.media.PASSTHROUGH_TYPES
import org.unitedsoftware.alexandrite.internal.media.imageType
import org.unitedsoftware.alexandrite.internal.media.webpDimensions
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.roundToInt

/** Images decoded, scaled and re-encoded with AWT and ImageIO. */
internal object AwtImages {
    /** The width and height [bytes] declare as decoded, without scaling, null when they cannot be read that cheaply. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        val sniffed = imageType(bytes) ?: return null
        if (sniffed == "image/webp") return webpDimensions(bytes)
        val opened = opened(bytes) ?: return null
        return (opened.width to opened.height).also {
            opened.reader.dispose()
            opened.stream.close()
        }
    }

    /**
     * [bytes] fit to send a model: at most [maxSide] pixels on the longer side and [maxBytes] in size, null when
     * [bytes] cannot be decoded as an image.
     *
     * An already-fitting png, jpeg or (non-animated) gif passes through unchanged. A webp passes through unchanged when
     * it already fits [maxBytes], else null: ImageIO has no built-in decoder to shrink it. Everything else is scaled
     * and re-encoded, PNG when it has an alpha channel and fits, else JPEG at a falling quality, the pixel size halved
     * up to four times if it still does not fit. An oversized animated gif becomes its first frame as PNG. Refuses
     * (null) images above 50 megapixels before decoding their raster.
     */
    fun normalized(
        bytes: ByteArray,
        maxSide: Int = MAX_IMAGE_SIDE,
        maxBytes: Int = MAX_IMAGE_BYTES,
    ): NormalizedImage? {
        val sniffed = imageType(bytes) ?: return null
        if (sniffed == "image/webp") return webpImage(bytes, maxBytes)
        val opened = opened(bytes) ?: return null
        val reader = opened.reader
        try {
            val width = opened.width
            val height = opened.height
            if (width.toLong() * height.toLong() > BOMB_PIXELS) return null
            val withinLimits = bytes.size <= maxBytes && maxOf(width, height) <= maxSide
            if (withinLimits && sniffed in PASSTHROUGH_TYPES) return NormalizedImage(bytes, sniffed, width, height)
            val decoded = try {
                reader.read(0)
            } catch (e: IOException) {
                return null
            }
            if (sniffed == "image/gif") {
                val target = scaledTo(decoded, maxSide)
                return NormalizedImage(encodePng(target), "image/png", target.width, target.height)
            }
            return encoded(decoded, maxSide, maxBytes)
        } finally {
            reader.dispose()
            opened.stream.close()
        }
    }

    /** An [ImageReader] set up to decode [bytes], with the [width] and [height] it already reports. */
    private class OpenedReader(
        val reader: ImageReader,
        val stream: MemoryCacheImageInputStream,
        val width: Int,
        val height: Int,
    )

    /** [bytes] opened by the first [ImageReader] that claims them, null when none does or its header is unreadable. */
    private fun opened(bytes: ByteArray): OpenedReader? {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: run {
            stream.close()
            return null
        }
        reader.input = stream
        return try {
            OpenedReader(reader, stream, reader.getWidth(0), reader.getHeight(0))
        } catch (e: IOException) {
            reader.dispose()
            stream.close()
            null
        }
    }

    /** [bytes] unchanged when within [maxBytes], else null: ImageIO cannot re-encode a webp it did not decode. */
    private fun webpImage(bytes: ByteArray, maxBytes: Int): NormalizedImage? {
        if (bytes.size > maxBytes) return null
        val (width, height) = webpDimensions(bytes) ?: (0 to 0)
        return NormalizedImage(bytes, "image/webp", width, height)
    }

    /** [decoded] scaled to [maxSide], then PNG when it has alpha and fits [maxBytes], else JPEG at a falling quality. */
    private fun encoded(decoded: BufferedImage, maxSide: Int, maxBytes: Int): NormalizedImage {
        val hasAlpha = decoded.colorModel.hasAlpha()
        val target = scaledTo(decoded, maxSide)
        if (hasAlpha) {
            val png = encodePng(target)
            if (png.size <= maxBytes) return NormalizedImage(png, "image/png", target.width, target.height)
        }
        var current = target
        var halvings = 0
        while (true) {
            for ((index, quality) in JPEG_QUALITY_LADDER.withIndex()) {
                val jpeg = encodeJpeg(current, quality)
                val lastTry = halvings == MAX_HALVINGS && index == JPEG_QUALITY_LADDER.lastIndex
                if (jpeg.size <= maxBytes || lastTry) {
                    return NormalizedImage(jpeg, "image/jpeg", current.width, current.height)
                }
            }
            current = halved(current)
            halvings++
        }
    }

    private fun encodePng(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { target ->
                writer.output = target
                writer.write(null, IIOImage(toOpaque(image), null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    /** [image] over a white background, its alpha channel dropped: a JPEG writer refuses one. */
    private fun toOpaque(image: BufferedImage): BufferedImage {
        if (!image.colorModel.hasAlpha()) return image
        val opaque = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = opaque.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return opaque
    }

    /** [image] unchanged when its longer side is at most [maxSide], else scaled proportionally down to it. */
    private fun scaledTo(image: BufferedImage, maxSide: Int): BufferedImage {
        val longest = maxOf(image.width, image.height)
        if (longest <= maxSide) return image
        val scale = maxSide.toDouble() / longest
        return resized(
            image,
            maxOf(1, (image.width * scale).roundToInt()),
            maxOf(1, (image.height * scale).roundToInt()),
        )
    }

    private fun halved(image: BufferedImage): BufferedImage =
        resized(image, maxOf(1, image.width / 2), maxOf(1, image.height / 2))

    private fun resized(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val scaled = BufferedImage(width, height, type)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return scaled
    }

    private val JPEG_QUALITY_LADDER = floatArrayOf(0.85f, 0.7f, 0.5f)
    private const val MAX_HALVINGS = 4
    private const val BOMB_PIXELS = 50_000_000L
}
