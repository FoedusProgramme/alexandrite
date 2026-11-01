package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.text.HTML_PAGES

/** The pages that every [HtmlParser] reads alike, each a check of its own. */
public val HTML_PARSER_CONTRACT: List<HtmlParserCheck> get() = HTML_PAGES

/** One page of the [HTML_PARSER_CONTRACT], with the document it reads as. */
public class HtmlParserCheck internal constructor(
    public val name: String,
    private val html: String,
    private val baseUrl: String?,
    private val expected: HtmlDocument,
) {
    /** Throws an [AssertionError] when [parser] reads the page as another document. */
    public fun run(parser: HtmlParser) {
        val actual = parser.parse(html, baseUrl)
        if (actual == expected) return
        throw AssertionError(
            buildString {
                append("$name: ")
                if (actual.title != expected.title) append("title <${actual.title}>, expected <${expected.title}>; ")
                if (actual.text != expected.text) append("text\n${actual.text}\nexpected\n${expected.text}\n")
                if (actual.links != expected.links) append("links ${actual.links}, expected ${expected.links}")
            },
        )
    }

    override fun toString(): String = name
}
