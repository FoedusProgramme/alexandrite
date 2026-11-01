package org.unitedsoftware.alexandrite.testkit.text

import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.store.fail

/** One run of a parser check on [parser]. */
internal class HtmlParserRun(private val parser: HtmlParser) {
    /** Expects [parser] to read [html] from [baseUrl] as [expected]. */
    fun expectDocument(html: String, baseUrl: String?, expected: HtmlDocument) {
        val actual = parser.parse(html, baseUrl)
        if (actual == expected) return
        fail(
            buildString {
                if (actual.title != expected.title) append("title <${actual.title}>, expected <${expected.title}>; ")
                if (actual.text != expected.text) append("text\n${actual.text}\nexpected\n${expected.text}\n")
                if (actual.links != expected.links) append("links ${actual.links}, expected ${expected.links}")
            },
        )
    }
}
