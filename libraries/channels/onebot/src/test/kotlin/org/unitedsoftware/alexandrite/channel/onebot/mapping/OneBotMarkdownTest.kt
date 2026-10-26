package org.unitedsoftware.alexandrite.channel.onebot.mapping

import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a chat of this platform reads of a message a model wrote as Markdown.
 *
 * A message of this platform shows no formatting, so the markers of it would arrive as the characters they are written
 * with. What they wrap is kept as the text a chat reads instead.
 */
class OneBotMarkdownTest {
    @Test
    fun `a heading keeps its title without its marker`() {
        assertEquals("Title", text("# Title"))
        assertEquals("Title", text("## Title ##"))
    }

    @Test
    fun `emphasis and strikethrough keep their words without their markers`() {
        assertEquals("bold and italic and struck", text("**bold** and *italic* and ~~struck~~"))
        assertEquals("bold and italic", text("__bold__ and _italic_"))
        assertEquals("snake_case stays as written", text("snake_case stays as written"))
    }

    @Test
    fun `a code span keeps what it holds without its backticks`() {
        assertEquals("x = **1**", text("`x = **1**`"))
    }

    @Test
    fun `a code block keeps its lines without its fence`() {
        assertEquals("val x = 1\nprintln(x)", text("```kotlin\nval x = 1\nprintln(x)\n```"))
        assertEquals("plain", text("~~~\nplain\n~~~"))
    }

    @Test
    fun `a link keeps its text and its address`() {
        assertEquals("see the docs (https://example.com/x)", text("see [the docs](https://example.com/x)"))
        assertEquals("https://a.b/c", text("[https://a.b/c](https://a.b/c)"))
        assertEquals("the docs (https://example.com/x)", text("""[the docs](https://example.com/x "The docs")"""))
    }

    @Test
    fun `an image becomes an image segment`() {
        val segments = OneBotMarkdown.segments("before ![a picture](https://x/1.jpg) after")

        assertEquals(
            listOf(
                OneBotSegment.Text("before "),
                OneBotSegment.Image(file = "https://x/1.jpg"),
                OneBotSegment.Text(" after"),
            ),
            segments,
        )
    }

    @Test
    fun `an image that names a local file stays text`() {
        assertEquals(
            listOf(OneBotSegment.Text("picture (file:///etc/passwd)")),
            OneBotMarkdown.segments("![picture](file:///etc/passwd)"),
        )
    }

    @Test
    fun `a list keeps its marker and loses the markers inside its item`() {
        assertEquals("- done\n* first\n1. second", text("- **done**\n* *first*\n1. **second**"))
    }

    @Test
    fun `a quote loses its marker`() {
        assertEquals("quoted line", text("> **quoted** line"))
    }

    @Test
    fun `a table keeps its cells without the row that lines them up`() {
        assertEquals("a | b\n1 | 2", text("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test
    fun `a rule becomes a line a chat reads`() {
        assertEquals("———", text("---"))
    }

    @Test
    fun `an escaped marker is the character it stands for`() {
        assertEquals("*not emphasis*", text("""\*not emphasis\*"""))
    }

    @Test
    fun `a message of plain lines keeps them`() {
        assertEquals("hello\n\nworld", text("hello\n\nworld"))
    }

    private fun text(markdown: String): String =
        OneBotMarkdown.segments(markdown).filterIsInstance<OneBotSegment.Text>().joinToString("") { it.text }
}
