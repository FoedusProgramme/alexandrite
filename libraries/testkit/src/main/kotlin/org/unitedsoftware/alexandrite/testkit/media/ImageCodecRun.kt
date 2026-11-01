package org.unitedsoftware.alexandrite.testkit.media

import org.unitedsoftware.alexandrite.sdk.media.FittedImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.testkit.store.expect
import org.unitedsoftware.alexandrite.testkit.store.expectEqual
import org.unitedsoftware.alexandrite.testkit.store.fail

/** One run of a codec check on [codec]. */
internal class ImageCodecRun(val codec: ImageCodec) {
    /** [png] as [codec] fits it within [maxSide] and [maxBytes], with the size it has then. */
    fun fit(png: ByteArray, maxSide: Int, maxBytes: Int): Pair<FittedImage, ImageDimensions> {
        val fitted = codec.fitted(png, "image/png", maxSide, maxBytes) ?: fail("The codec could not fit a png.")
        expect(fitted.mediaType in MODEL_TYPES) { "The codec fitted the image as ${fitted.mediaType}." }
        expect(fitted.bytes.size <= maxBytes) { "The fitted image has ${fitted.bytes.size} bytes, over $maxBytes." }
        val read = codec.dimensions(fitted.bytes, fitted.mediaType)
        val size = fitted.dimensions ?: read ?: fail("The codec cannot tell the size of the image it fitted.")
        read?.let { expectEqual(size, it, "the size the codec reads in the image it fitted") }
        expect(maxOf(size.width, size.height) <= maxSide) { "The fitted image is $size, over $maxSide pixels." }
        return fitted to size
    }

    private companion object {
        val MODEL_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}
