package org.unitedsoftware.alexandrite.engine.jsoup

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.testkit.HTML_PARSER_CONTRACT

class JsoupHtmlParserTest {
    @TestFactory
    fun `jsoup keeps the HTML parser contract`(): List<DynamicTest> =
        HTML_PARSER_CONTRACT.map { check -> DynamicTest.dynamicTest(check.name) { check.run(JsoupHtmlParser()) } }
}
