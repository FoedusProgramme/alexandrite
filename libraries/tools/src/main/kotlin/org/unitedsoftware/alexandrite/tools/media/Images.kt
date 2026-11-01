package org.unitedsoftware.alexandrite.tools.media

import org.unitedsoftware.alexandrite.internal.media.MAX_IMAGE_BYTES
import org.unitedsoftware.alexandrite.internal.media.MAX_IMAGE_SIDE
import org.unitedsoftware.alexandrite.internal.media.NormalizedImage
import org.unitedsoftware.alexandrite.internal.media.headerDimensions
import org.unitedsoftware.alexandrite.internal.media.unchangedImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec

/** Why an image is not shown where no [ImageCodec] is bound and it is not one a model takes as it is. */
internal const val NO_IMAGE_CODEC = "this host cannot process images"

/**
 * [bytes], an image of [mediaType], fit to send a model by [codec], or as they are where none is bound; null when the
 * codec cannot decode them or, without one, they do not fit as they are.
 */
internal fun fittedImage(bytes: ByteArray, mediaType: String, codec: ImageCodec?): NormalizedImage? {
    if (codec == null) return unchangedImage(bytes, mediaType)
    val image = codec.fitted(bytes, mediaType, MAX_IMAGE_SIDE, MAX_IMAGE_BYTES) ?: return null
    return NormalizedImage(image.bytes, image.mediaType, image.dimensions?.width ?: 0, image.dimensions?.height ?: 0)
}

/** The width and height [bytes], an image of [mediaType], declare, as [codec] or else their header tells them. */
internal fun imageDimensions(bytes: ByteArray, mediaType: String, codec: ImageCodec?): Pair<Int, Int>? =
    if (codec == null) {
        headerDimensions(bytes, mediaType)
    } else {
        codec.dimensions(bytes, mediaType)?.let { it.width to it.height }
    }
