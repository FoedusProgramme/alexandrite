package org.unitedsoftware.alexandrite.tools.media

import org.unitedsoftware.alexandrite.internal.media.AwtImages
import org.unitedsoftware.alexandrite.internal.text.SwingHtml
import org.unitedsoftware.alexandrite.sdk.media.FittedImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlLink
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser

/** The codec the runtime binds on the JDK. */
val AWT_CODEC: ImageCodec = object : ImageCodec {
    override fun dimensions(bytes: ByteArray, mediaType: String): ImageDimensions? =
        AwtImages.dimensions(bytes)?.let { ImageDimensions(it.first, it.second) }

    override fun fitted(bytes: ByteArray, mediaType: String, maxSide: Int, maxBytes: Int): FittedImage? =
        AwtImages.normalized(bytes, maxSide, maxBytes)?.let {
            FittedImage(it.bytes, it.mediaType, ImageDimensions(it.width, it.height).takeIf { _ -> it.width > 0 })
        }
}

/** The HTML parser the runtime binds on the JDK. */
val SWING_HTML: HtmlParser = object : HtmlParser {
    override fun parse(html: String, baseUrl: String?): HtmlDocument = SwingHtml.read(html, baseUrl).let { read ->
        HtmlDocument(read.title, read.text, read.links.map { HtmlLink(it.href, it.text) })
    }
}
