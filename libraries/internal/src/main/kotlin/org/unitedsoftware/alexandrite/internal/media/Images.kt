package org.unitedsoftware.alexandrite.internal.media

/** The longest side, in pixels, of an image sent to a model. */
public const val MAX_IMAGE_SIDE: Int = 1568

/** The most bytes of an image sent to a model. */
public const val MAX_IMAGE_BYTES: Int = 5_000_000

/** The IANA media type that [bytes]' magic bytes declare, null when none of them match. */
public fun imageType(bytes: ByteArray): String? = when {
    bytes.matches(0, PNG_SIGNATURE) -> "image/png"
    bytes.matches(0, JPEG_SIGNATURE) -> "image/jpeg"
    bytes.matches(0, GIF87A) || bytes.matches(0, GIF89A) -> "image/gif"
    bytes.matches(0, RIFF) && bytes.matches(8, WEBP) -> "image/webp"
    bytes.matches(0, BMP_SIGNATURE) -> "image/bmp"
    bytes.matches(0, TIFF_LE) || bytes.matches(0, TIFF_BE) -> "image/tiff"
    bytes.matches(4, FTYP) -> heicType(bytes)
    else -> null
}

/** An image ready to send a model: [bytes] of [mediaType], [width] by [height] pixels, 0 by 0 when unknown. */
public class NormalizedImage(
    public val bytes: ByteArray,
    public val mediaType: String,
    public val width: Int,
    public val height: Int,
)

/**
 * The width and height that the header of [bytes], a png, jpeg, gif or webp of [mediaType], declares; null for another
 * type or a header that does not tell.
 */
public fun headerDimensions(bytes: ByteArray, mediaType: String): Pair<Int, Int>? = when (mediaType) {
    "image/png" -> if (bytes.size >= 24 && bytes.matches(12, IHDR)) readBe32(bytes, 16) to readBe32(bytes, 20) else null
    "image/gif" -> if (bytes.size >= 10) readLe16(bytes, 6) to readLe16(bytes, 8) else null
    "image/jpeg" -> jpegDimensions(bytes)
    "image/webp" -> webpDimensions(bytes)
    else -> null
}

/**
 * [bytes], an image of [mediaType], unchanged where a model takes them so: a png, jpeg or gif at most [maxSide] pixels
 * on its longer side, or a webp, within [maxBytes]; null otherwise.
 */
public fun unchangedImage(
    bytes: ByteArray,
    mediaType: String,
    maxSide: Int = MAX_IMAGE_SIDE,
    maxBytes: Int = MAX_IMAGE_BYTES,
): NormalizedImage? {
    if (bytes.size > maxBytes) return null
    if (mediaType == "image/webp") {
        val (width, height) = webpDimensions(bytes) ?: (0 to 0)
        return NormalizedImage(bytes, mediaType, width, height)
    }
    if (mediaType !in PASSTHROUGH_TYPES) return null
    val (width, height) = headerDimensions(bytes, mediaType) ?: return null
    if (width <= 0 || height <= 0 || maxOf(width, height) > maxSide) return null
    return NormalizedImage(bytes, mediaType, width, height)
}

/** The canvas size a webp's `VP8X`, `VP8L` or `VP8 ` chunk declares, null when it cannot be read that cheaply. */
public fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) return null
    return when (String(bytes, 12, 4, Charsets.US_ASCII)) {
        "VP8X" -> (readLe24(bytes, 24) + 1) to (readLe24(bytes, 27) + 1)
        "VP8L" -> readLe32(bytes, 21).let { packed -> ((packed and 0x3FFF) + 1) to (((packed shr 14) and 0x3FFF) + 1) }
        "VP8 " -> (readLe16(bytes, 26) and 0x3FFF) to (readLe16(bytes, 28) and 0x3FFF)
        else -> null
    }
}

/** The size the first start-of-frame marker of a jpeg declares, null when none comes before its scan. */
private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
    var at = 2
    while (at + 3 < bytes.size) {
        if (bytes[at] != 0xFF.toByte()) return null
        val marker = bytes[at + 1].toInt() and 0xFF
        when {
            marker == 0xFF -> at++

            marker == 0x01 || marker in 0xD0..0xD8 -> at += 2

            marker == 0xD9 || marker == 0xDA -> return null

            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                if (at + 8 >= bytes.size) return null
                return readBe16(bytes, at + 7) to readBe16(bytes, at + 5)
            }

            else -> at += 2 + readBe16(bytes, at + 2)
        }
    }
    return null
}

private fun heicType(bytes: ByteArray): String? {
    if (bytes.size < 12) return null
    return when (String(bytes, 8, 4, Charsets.US_ASCII)) {
        in HEIC_BRANDS -> "image/heic"
        in HEIF_BRANDS -> "image/heif"
        else -> null
    }
}

private fun ByteArray.matches(offset: Int, signature: ByteArray): Boolean =
    size >= offset + signature.size && signature.indices.all { this[offset + it] == signature[it] }

private fun readLe16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readLe24(bytes: ByteArray, offset: Int): Int =
    readLe16(bytes, offset) or ((bytes[offset + 2].toInt() and 0xFF) shl 16)

private fun readLe32(bytes: ByteArray, offset: Int): Int =
    readLe24(bytes, offset) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun readBe16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun readBe32(bytes: ByteArray, offset: Int): Int =
    (readBe16(bytes, offset) shl 16) or readBe16(bytes, offset + 2)

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val GIF87A = "GIF87a".toByteArray(Charsets.US_ASCII)
private val GIF89A = "GIF89a".toByteArray(Charsets.US_ASCII)
private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
private val BMP_SIGNATURE = "BM".toByteArray(Charsets.US_ASCII)
private val TIFF_LE = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
private val TIFF_BE = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)
private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")
private val HEIF_BRANDS = setOf("mif1", "msf1")

/** The types that pass unchanged when they fit. */
public val PASSTHROUGH_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif")
