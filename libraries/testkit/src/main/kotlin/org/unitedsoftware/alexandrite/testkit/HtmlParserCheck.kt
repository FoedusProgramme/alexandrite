package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.text.HTML_PARSER_CHECKS
import org.unitedsoftware.alexandrite.testkit.text.HtmlParserRun

/** The checks that every [HtmlParser] passes, each on a page of its own. */
public val HTML_PARSER_CONTRACT: List<HtmlParserCheck> = HTML_PARSER_CHECKS

/** One check of the [HTML_PARSER_CONTRACT]. */
public class HtmlParserCheck internal constructor(
    public val name: String,
    private val body: HtmlParserRun.() -> Unit,
) {
    /** Runs the check on [parser], and throws an [AssertionError] when it breaks the contract. */
    public fun run(parser: HtmlParser) {
        HtmlParserRun(parser).body()
    }

    override fun toString(): String = name
}
