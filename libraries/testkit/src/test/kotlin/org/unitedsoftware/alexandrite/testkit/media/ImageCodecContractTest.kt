package org.unitedsoftware.alexandrite.testkit.media

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.engine.awtimages.AlexandriteEngineAwtImagesIndex
import org.unitedsoftware.alexandrite.sdk.media.FittedImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.testkit.IMAGE_CODEC_CONTRACT
import org.unitedsoftware.alexandrite.testkit.boundBy
import kotlin.test.assertFailsWith

/** A way to break the codec contract, and the name of the check that catches it. */
private enum class ImageCodecFlaw(val check: String) {
    SWAPS_SIDES("the size of an image is read from its bytes"),
    KEEPS_THE_SIZE("an opaque image over the longer side is scaled down in proportion as jpeg"),
    CALLS_EVERYTHING_JPEG("an image with an alpha channel over the longer side is scaled down in proportion as png"),
    IGNORES_THE_BYTE_LIMIT("an image over the byte limit is re-encoded within it"),
    SIZES_NOISE("bytes that are no image have no size and fit to nothing"),
}

/** [codec] broken by [flaw]. */
private class FlawedCodec(private val codec: ImageCodec, private val flaw: ImageCodecFlaw) : ImageCodec {
    override fun dimensions(bytes: ByteArray, mediaType: String): ImageDimensions? {
        val read = codec.dimensions(bytes, mediaType)
        return when (flaw) {
            ImageCodecFlaw.SWAPS_SIDES -> read?.let { ImageDimensions(it.height, it.width) }
            ImageCodecFlaw.SIZES_NOISE -> read ?: ImageDimensions(1, 1)
            else -> read
        }
    }

    override fun fitted(bytes: ByteArray, mediaType: String, maxSide: Int, maxBytes: Int): FittedImage? = when (flaw) {
        ImageCodecFlaw.KEEPS_THE_SIZE -> FittedImage(bytes, mediaType, null)

        ImageCodecFlaw.CALLS_EVERYTHING_JPEG ->
            codec.fitted(bytes, mediaType, maxSide, maxBytes)?.let {
                FittedImage(it.bytes, "image/jpeg", it.dimensions)
            }

        ImageCodecFlaw.IGNORES_THE_BYTE_LIMIT -> codec.fitted(bytes, mediaType, maxSide, Int.MAX_VALUE)

        else -> codec.fitted(bytes, mediaType, maxSide, maxBytes)
    }
}

class ImageCodecContractTest {
    private val codec = boundBy<ImageCodec>(AlexandriteEngineAwtImagesIndex())

    @TestFactory
    fun `each check fails a codec with the flaw it guards against`(): List<DynamicTest> =
        ImageCodecFlaw.entries.map { flaw ->
            DynamicTest.dynamicTest("$flaw: ${flaw.check}") {
                val check = IMAGE_CODEC_CONTRACT.single { it.name == flaw.check }

                assertFailsWith<AssertionError> { check.run(FlawedCodec(codec, flaw)) }
            }
        }
}
