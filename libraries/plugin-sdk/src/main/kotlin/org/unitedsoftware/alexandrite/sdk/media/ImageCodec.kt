package org.unitedsoftware.alexandrite.sdk.media

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.di.BoundSpi

/**
 * Decodes, scales and re-encodes images so that a model takes them.
 *
 * Optional: where nothing binds one, an image reaches a model only when it is png, jpeg, gif or webp and already within
 * the limits, and every other image is refused.
 */
@BoundSpi
public interface ImageCodec {
    /** The width and height [bytes], an image of [mediaType], declare as decoded, null when this codec cannot tell. */
    public fun dimensions(bytes: ByteArray, mediaType: String): ImageDimensions?

    /**
     * [bytes], an image of [mediaType], fit to send a model: png, jpeg, gif or webp, at most [maxSide] pixels on the
     * longer side and at most [maxBytes] in size where the codec gets there; null when it cannot decode them.
     *
     * An image that already fits passes unchanged. Another is scaled down and re-encoded, as png where it has an alpha
     * channel and that fits, else as jpeg over white.
     */
    public fun fitted(bytes: ByteArray, mediaType: String, maxSide: Int, maxBytes: Int): FittedImage?
}

/** In pixels. */
@Poko
public class ImageDimensions(public val width: Int, public val height: Int)

/** [bytes] of the IANA [mediaType], their [dimensions] null when the codec does not know them. */
public class FittedImage(
    public val bytes: ByteArray,
    public val mediaType: String,
    public val dimensions: ImageDimensions?,
)
