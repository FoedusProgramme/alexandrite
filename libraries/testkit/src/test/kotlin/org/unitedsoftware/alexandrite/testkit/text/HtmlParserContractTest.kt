package org.unitedsoftware.alexandrite.testkit.text

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.engine.swinghtml.AlexandriteEngineSwingHtmlIndex
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.HTML_PARSER_CONTRACT
import org.unitedsoftware.alexandrite.testkit.boundBy
import kotlin.test.assertFailsWith

/** A way to break the parser contract, and the name of the check that catches it. */
private enum class HtmlParserFlaw(val check: String) {
    LEAVES_LINKS_UNRESOLVED("links of every form"),
    DROPS_THE_TITLE("a reference page"),
    JOINS_LINES("a page without its optional tags"),
}

/** [parser] broken by [flaw]. */
private class FlawedParser(private val parser: HtmlParser, private val flaw: HtmlParserFlaw) : HtmlParser {
    override fun parse(html: String, baseUrl: String?): HtmlDocument = when (flaw) {
        HtmlParserFlaw.LEAVES_LINKS_UNRESOLVED -> parser.parse(html, null)

        HtmlParserFlaw.DROPS_THE_TITLE -> parser.parse(html, baseUrl).let { HtmlDocument(null, it.text, it.links) }

        HtmlParserFlaw.JOINS_LINES ->
            parser.parse(html, baseUrl).let { HtmlDocument(it.title, it.text.replace('\n', ' '), it.links) }
    }
}

class HtmlParserContractTest {
    private val parser = boundBy<HtmlParser>(AlexandriteEngineSwingHtmlIndex())

    @TestFactory
    fun `each check fails a parser with the flaw it guards against`(): List<DynamicTest> =
        HtmlParserFlaw.entries.map { flaw ->
            DynamicTest.dynamicTest("$flaw: ${flaw.check}") {
                val check = HTML_PARSER_CONTRACT.single { it.name == flaw.check }

                assertFailsWith<AssertionError> { check.run(FlawedParser(parser, flaw)) }
            }
        }
}
