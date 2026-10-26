package org.unitedsoftware.alexandrite.channel.qqbot.mapping

/**
 * Makes a Markdown text safe for the QQ client without throwing away what it renders.
 *
 * The client renders headings, emphasis, lists, code, tables and most of what the model writes, so supported Markdown
 * is passed through as it is. What the client only shows as raw text is adapted to the nearest form it does render:
 * `==highlight==` becomes bold, `^sup^` and `~sub~` become the client's inline HTML, definition lists become plain
 * "term: definition" lines, and HTML blocks lose their tags. A link the client cannot open is left as its label, and a
 * local image path becomes its alt text. The inside of a code fence or an inline code span is never changed.
 */
internal object QqBotMarkdown {
    private val FENCE = Regex("^\\s*(`{3,}|~{3,}).*")
    private val TOC_LINE = Regex("^\\s*\\[TOC]\\s*$", RegexOption.IGNORE_CASE)
    private val FOOTNOTE_DEFINITION = Regex("^\\s*\\[\\^[^]]+]:.*$")
    private val DEFINITION_LINE = Regex("^\\s*:\\s+(.*)$")
    private val LOCAL_IMAGE = Regex("!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
    private val HTML_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val HTML_RULE = Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?/?>")
    private val HIGHLIGHT = Regex("==([^=\\n]+)==")
    private val SUPERSCRIPT = Regex("\\^([^^\\n]+)\\^")
    private val SUBSCRIPT = Regex("(?<!~)~([^~\\n]+)~(?!~)")
    private val FOOTNOTE_REFERENCE = Regex("\\[\\^[^]]+]")

    /** [text] with the unsupported Markdown adapted to what the client renders; [stripUrls] drops links and URLs. */
    fun render(text: String, stripUrls: Boolean = false): String {
        if (text.isEmpty()) return text
        val out = mutableListOf<String>()
        var fence: String? = null
        for (line in text.lines()) {
            val opening = FENCE.matchEntire(line)?.groupValues?.get(1)
            if (fence != null) {
                out += line
                if (opening != null && line.trimStart().startsWith(fence)) fence = null
                continue
            }
            if (opening != null) {
                fence = opening.take(3)
                out += line
                continue
            }
            if (TOC_LINE.matches(line) || FOOTNOTE_DEFINITION.matches(line)) continue
            val definition = DEFINITION_LINE.matchEntire(line)
            if (definition != null) {
                val previous = out.indexOfLast { it.isNotBlank() }
                if (previous >= 0 && !looksLikeBlock(out[previous])) {
                    out[previous] = out[previous].trimEnd() + "：" + definition.groupValues[1].trim()
                    continue
                }
            }
            out += renderLine(line, stripUrls)
        }
        return out.joinToString("\n")
    }

    /** [text] with every URL and link target taken out, for a platform that refuses a message holding one. */
    fun withoutUrls(text: String): String = render(text, stripUrls = true)

    /** Whether [line] is a heading, list item or quote, which a definition line does not extend. */
    private fun looksLikeBlock(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("#") || trimmed.startsWith("-") || trimmed.startsWith("*") ||
            trimmed.startsWith("+") || trimmed.startsWith(">") || trimmed.startsWith("|") ||
            trimmed.startsWith("```") || trimmed.startsWith("~~~") || trimmed.matches(Regex("^\\d+[.)]\\s.*"))
    }

    /** [line] outside a code fence, with inline code left alone. */
    private fun renderLine(line: String, stripUrls: Boolean): String = buildString {
        var index = 0
        while (index < line.length) {
            val ticks = ticksAt(line, index)
            if (ticks == 0) {
                val start = index
                while (index < line.length && ticksAt(line, index) == 0) index++
                append(renderSegment(line.substring(start, index), stripUrls))
            } else {
                val end = line.indexOf("`".repeat(ticks), index + ticks)
                if (end < 0) {
                    append(renderSegment(line.substring(index), stripUrls))
                    return@buildString
                }
                append(line, index, end + ticks)
                index = end + ticks
            }
        }
    }

    /** How many backticks start at [index], zero when the character there is none. */
    private fun ticksAt(line: String, index: Int): Int {
        if (index >= line.length || line[index] != '`') return 0
        var end = index
        while (end < line.length && line[end] == '`') end++
        return end - index
    }

    /** [segment] outside inline code, adapted for the client. */
    private fun renderSegment(segment: String, stripUrls: Boolean): String {
        if (segment.isEmpty()) return segment
        var text = segment
        if (stripUrls) {
            text = text.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            text = text.replace(Regex("https?://\\S+"), "")
        }
        text = text.replace(HIGHLIGHT, "**$1**")
        text = text.replace(SUPERSCRIPT, "<sup>$1</sup>")
        text = text.replace(SUBSCRIPT, "<sub>$1</sub>")
        text = text.replace(FOOTNOTE_REFERENCE, "")
        text = text.replace(HTML_BREAK, "\n")
        text = text.replace(HTML_RULE, "\n---")
        text = text.replace(HTML_TAG) { match ->
            val tag = match.value.lowercase()
            if (tag.startsWith("<sub") || tag.startsWith("</sub") || tag.startsWith("<sup") ||
                tag.startsWith("</sup")
            ) {
                match.value
            } else {
                ""
            }
        }
        text = text.replace(LOCAL_IMAGE) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            if (url.startsWith("http://") || url.startsWith("https://")) match.value else alt
        }
        return text
    }
}
