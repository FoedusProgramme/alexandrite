package org.unitedsoftware.alexandrite.engine.awtimages

import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.media.FittedImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions

/** The [ImageCodec] on AWT and ImageIO. */
@Singleton
@Binds(ImageCodec::class)
public class AwtImageCodec : ImageCodec {
    override fun dimensions(bytes: ByteArray, mediaType: String): ImageDimensions? =
        AwtImages.dimensions(bytes)?.let { (width, height) -> ImageDimensions(width, height) }

    override fun fitted(bytes: ByteArray, mediaType: String, maxSide: Int, maxBytes: Int): FittedImage? =
        AwtImages.normalized(bytes, maxSide, maxBytes)?.let { image ->
            val dimensions = ImageDimensions(image.width, image.height).takeIf { image.width > 0 && image.height > 0 }
            FittedImage(image.bytes, image.mediaType, dimensions)
        }
}
