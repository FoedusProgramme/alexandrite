package org.unitedsoftware.alexandrite.channel.qqbot.mapping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QqBotMarkdownTest {
    @Test
    fun `supported markdown is passed through`() {
        val text = "# Title\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n```kotlin\nval x = 1\n```\n"

        assertEquals(text, QqBotMarkdown.render(text))
    }

    @Test
    fun `a highlight becomes bold`() {
        assertEquals("**important**", QqBotMarkdown.render("==important=="))
    }

    @Test
    fun `a superscript and a subscript become the inline HTML the client renders`() {
        assertEquals("E = mc<sup>2</sup>", QqBotMarkdown.render("E = mc^2^"))
        assertEquals("H<sub>2</sub>O", QqBotMarkdown.render("H~2~O"))
        assertEquals("~~removed~~", QqBotMarkdown.render("~~removed~~"))
    }

    @Test
    fun `a definition list becomes plain lines`() {
        assertEquals("term：its meaning", QqBotMarkdown.render("term\n: its meaning"))
    }

    @Test
    fun `a table of contents and a footnote definition are dropped`() {
        val text = "[TOC]\n\n[^note]: a footnote\n\nA paragraph."

        assertEquals("\n\nA paragraph.", QqBotMarkdown.render(text))
    }

    @Test
    fun `an HTML block loses its tags but keeps its text`() {
        val text = "<details><summary>More</summary>\n\nSome text\n\n</details>"

        assertEquals("More\n\nSome text\n\n", QqBotMarkdown.render(text))
    }

    @Test
    fun `a break and a rule become markdown the client renders`() {
        assertEquals("a\nb\n---", QqBotMarkdown.render("a<br>b<hr>"))
    }

    @Test
    fun `an image with a local path becomes its alt text and a public one stays`() {
        assertEquals("a picture", QqBotMarkdown.render("![a picture](./images/a.png)"))
        val remote = "![a picture](https://example.com/a.png)"
        assertEquals(remote, QqBotMarkdown.render(remote))
    }

    @Test
    fun `a code fence and an inline code span are not changed`() {
        val text = "```\n==not bold== <details>\n```\n\n`==not bold==`"

        assertEquals(text, QqBotMarkdown.render(text))
    }

    @Test
    fun `withoutUrls keeps the label and drops the target`() {
        assertEquals("see the site", QqBotMarkdown.render("see [the site](https://example.com)", stripUrls = true))
        assertFalse("example.com" in QqBotMarkdown.render("see https://example.com", stripUrls = true))
        assertTrue("the site" in QqBotMarkdown.withoutUrls("see [the site](https://example.com)"))
    }
}
