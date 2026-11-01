package org.unitedsoftware.alexandrite.engine.jsoup

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.unitedsoftware.alexandrite.internal.text.HtmlReader
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlLink
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser

/** Reads pages from the element tree jsoup builds by the HTML standard. */
@Singleton
@Binds(HtmlParser::class)
internal class JsoupHtmlParser : HtmlParser {
    override fun parse(html: String, baseUrl: String?): HtmlDocument {
        val reader = HtmlReader(baseUrl)
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is Document -> Unit

                        is Element -> reader.start(
                            node.normalName(),
                            node.attributes().takeIf {
                                it.hasKey("href")
                            }?.get("href"),
                        )

                        is TextNode -> reader.text(node.wholeText)

                        is Comment -> reader.comment()
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node !is Document) reader.end(node.normalName())
                }
            },
            Jsoup.parse(html),
        )
        val read = reader.read()
        return HtmlDocument(read.title, read.text, read.links.map { HtmlLink(it.href, it.text) })
    }
}
