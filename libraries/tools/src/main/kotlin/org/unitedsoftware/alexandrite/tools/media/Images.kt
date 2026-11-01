package org.unitedsoftware.alexandrite.tools.media

import org.unitedsoftware.alexandrite.internal.media.NormalizedImage
import org.unitedsoftware.alexandrite.internal.media.headerDimensions
import org.unitedsoftware.alexandrite.internal.media.modelImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec

/** [bytes], an image of [mediaType], fit to send a model by [codec] where one is bound, else as they are. */
internal fun fittedImage(bytes: ByteArray, mediaType: String, codec: ImageCodec?): NormalizedImage? =
    modelImage(bytes, mediaType, codec?.let { it::normalized })

private fun ImageCodec.normalized(bytes: ByteArray, mediaType: String, maxSide: Int, maxBytes: Int): NormalizedImage? =
    fitted(bytes, mediaType, maxSide, maxBytes)?.let {
        NormalizedImage(it.bytes, it.mediaType, it.dimensions?.width, it.dimensions?.height)
    }

/**
 * The width and height [bytes], an image of [mediaType], declare, as [codec] tells them or, where none is bound, their
 * header.
 */
internal fun imageDimensions(bytes: ByteArray, mediaType: String, codec: ImageCodec?): Pair<Int, Int>? =
    if (codec == null) {
        headerDimensions(bytes, mediaType)
    } else {
        codec.dimensions(bytes, mediaType)?.let { it.width to it.height }
    }
