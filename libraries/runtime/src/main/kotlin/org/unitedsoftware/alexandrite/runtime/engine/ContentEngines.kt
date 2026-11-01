package org.unitedsoftware.alexandrite.runtime.engine

import org.unitedsoftware.alexandrite.internal.media.AwtImages
import org.unitedsoftware.alexandrite.internal.portable.JdkFeatures
import org.unitedsoftware.alexandrite.internal.text.SwingHtml
import org.unitedsoftware.alexandrite.runtime.lifecycle.RUNTIME_PLUGIN
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.PluginBindings
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.media.FittedImage
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageDimensions
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlLink
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser

/** An engine port with the JDK default the runtime binds where no plugin binds the port, null where the JDK lacks it. */
internal class EngineDefault<T : Any>(val port: Key<T>, private val name: String, private val default: () -> T?) {
    fun binding(): Binding<T>? = default()?.let { instanceBinding(port, it, RUNTIME_PLUGIN, name) }
}

/** The content engine ports, HTML and images, with their JDK defaults. */
internal val CONTENT_ENGINES: List<EngineDefault<*>> = listOf(
    EngineDefault(key<HtmlParser>(), "SwingHtmlParser") { if (JdkFeatures.swingHtml) SwingHtmlParser() else null },
    EngineDefault(key<ImageCodec>(), "AwtImageCodec") { if (JdkFeatures.awtImages) AwtImageCodec() else null },
)

/** The JDK defaults of the [CONTENT_ENGINES] that none of [plugins] binds. */
internal fun contentEngineBindings(plugins: List<PluginBindings>): List<Binding<*>> = CONTENT_ENGINES
    .filter { engine -> plugins.none { plugin -> plugin.bindings.any { it.key == engine.port } } }
    .mapNotNull { it.binding() }

/** The [HtmlParser] on Swing's HTML parser. */
internal class SwingHtmlParser : HtmlParser {
    override fun parse(html: String, baseUrl: String?): HtmlDocument {
        val read = SwingHtml.read(html, baseUrl)
        return HtmlDocument(read.title, read.text, read.links.map { HtmlLink(it.href, it.text) })
    }
}

/** The [ImageCodec] on AWT and ImageIO. */
internal class AwtImageCodec : ImageCodec {
    override fun dimensions(bytes: ByteArray, mediaType: String): ImageDimensions? =
        AwtImages.dimensions(bytes)?.let { (width, height) -> ImageDimensions(width, height) }

    override fun fitted(bytes: ByteArray, mediaType: String, maxSide: Int, maxBytes: Int): FittedImage? =
        AwtImages.normalized(bytes, maxSide, maxBytes)?.let { image ->
            val dimensions = ImageDimensions(image.width, image.height).takeIf { image.width > 0 && image.height > 0 }
            FittedImage(image.bytes, image.mediaType, dimensions)
        }
}
