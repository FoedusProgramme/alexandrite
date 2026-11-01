package org.unitedsoftware.alexandrite.engine.jsoup

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.HTML_PARSER_CONTRACT
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertIs

class JsoupHtmlParserTest {
    @TestFactory
    fun `the jsoup parser keeps the HTML parser contract`(): List<DynamicTest> =
        HTML_PARSER_CONTRACT.map { check -> DynamicTest.dynamicTest(check.name) { check.run(JsoupHtmlParser()) } }

    @Test
    fun `the plugin binds its parser`() {
        runBlocking {
            PluginHarness.builder(AlexandriteEngineJsoupIndex()).build().run {
                assertIs<JsoupHtmlParser>(get<HtmlParser>())
            }
        }
    }
}
