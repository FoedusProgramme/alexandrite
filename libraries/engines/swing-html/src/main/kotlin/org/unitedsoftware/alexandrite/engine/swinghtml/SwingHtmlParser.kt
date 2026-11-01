package org.unitedsoftware.alexandrite.engine.swinghtml

import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlLink
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser

/** The [HtmlParser] on Swing's HTML parser. */
@Singleton
@Binds(HtmlParser::class)
public class SwingHtmlParser : HtmlParser {
    override fun parse(html: String, baseUrl: String?): HtmlDocument {
        val read = SwingHtml.read(html, baseUrl)
        return HtmlDocument(read.title, read.text, read.links.map { HtmlLink(it.href, it.text) })
    }
}
