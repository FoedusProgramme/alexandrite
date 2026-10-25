package org.unitedsoftware.alexandrite.channel.onebot.mapping

import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment

/**
 * Renders Markdown as the plain text a chat of this platform reads, in the segments a message takes.
 *
 * A message of this platform has no formatting of its own, so a marker of the formatting would arrive as the character
 * it is written with. What the marker means is kept as words instead: emphasis keeps its text, a heading keeps its
 * title, a code block keeps its lines and a link keeps its address beside its text. A list keeps its marker, which a
 * chat already reads as one, and an image becomes an image segment.
 */
internal object OneBotMarkdown {
    /** [markdown] as text segments and image segments, in the order it writes them. */
    fun segments(markdown: String): List<OneBotSegment> = Renderer().also { it.render(markdown) }.segments()
}

/** What a chat is told of one message, built as its lines are read. */
private class Renderer {
    private val text = StringBuilder()
    private val images = mutableListOf<String>()

    fun render(markdown: String) {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var at = 0
        while (at < lines.size) {
            at = block(lines, at)
            if (at < lines.size) text.append('\n')
        }
    }

    fun segments(): List<OneBotSegment> {
        val segments = mutableListOf<OneBotSegment>()
        var at = 0
        for (match in IMAGE_MARKER_IN_TEXT.findAll(text)) {
            if (match.range.first > at) segments += OneBotSegment.Text(text.substring(at, match.range.first))
            images.getOrNull(match.groupValues[1].toInt())?.let { segments += OneBotSegment.Image(file = it) }
            at = match.range.last + 1
        }
        if (at < text.length) segments += OneBotSegment.Text(text.substring(at))
        return segments
    }

    /** Reads the block that starts at line [at] and returns the line after it. */
    private fun block(lines: List<String>, at: Int): Int {
        val line = lines[at]
        fenceOpening(line)?.let { return fence(lines, at, it) }
        if (startsTable(lines, at)) return table(lines, at)
        if (RULE.matches(line)) {
            text.append(RULE_TEXT)
            return at + 1
        }
        HEADING.matchEntire(line)?.let { heading ->
            inline(heading.groupValues[1])
            return at + 1
        }
        QUOTE.matchEntire(line)?.let { quote ->
            inline(quote.groupValues[1])
            return at + 1
        }
        BULLET.matchEntire(line)?.let { item ->
            text.append(item.groupValues[1])
            inline(item.groupValues[2])
            return at + 1
        }
        ORDERED.matchEntire(line)?.let { item ->
            text.append(item.groupValues[1])
            inline(item.groupValues[2])
            return at + 1
        }
        inline(line.trimEnd())
        return at + 1
    }

    /** The lines of the fence that starts at [at], without the fence and what follows its opening. */
    private fun fence(lines: List<String>, at: Int, fence: Fence): Int {
        var end = at + 1
        while (end < lines.size && !fence.closedBy(lines[end])) end++
        text.append(lines.subList(at + 1, end).joinToString("\n") { fence.unindented(it) })
        return if (end < lines.size) end + 1 else end
    }

    /** The rows of the table that starts at [at], without the row that only says how its cells line up. */
    private fun table(lines: List<String>, at: Int): Int {
        var end = at + 2
        while (end < lines.size && lines[end].isNotBlank() && '|' in lines[end]) end++
        text.append((listOf(lines[at]) + lines.subList(at + 2, end)).joinToString("\n") { cells(it) })
        return end
    }

    /** The cells of one row of a table, which a chat reads apart by the pipe between them. */
    private fun cells(row: String): String = row.trim().trim('|').split('|').joinToString(" | ") { it.trim() }

    /** Appends [source] as a chat reads it: without the markers of its formatting and with its images kept apart. */
    private fun inline(source: String) {
        val code = mutableListOf<String>()
        var work = protectCode(source.trimEnd(), code)
        work = IMAGE.replace(work) { match ->
            val target = match.groupValues[2]
            if (target.startsWith("https://") || target.startsWith("http://")) {
                images += target
                "$IMAGE_MARKER${images.lastIndex}$MARKER_END"
            } else {
                // A model's image target is not a path this host may read: only a public URL becomes an image.
                val label = match.groupValues[1].trim()
                if (label.isEmpty()) target else "$label ($target)"
            }
        }
        work = LINK.replace(work) { match ->
            val label = match.groupValues[1].trim()
            val url = match.groupValues[2]
            when {
                label.isEmpty() -> url
                label == url -> url
                else -> "$label ($url)"
            }
        }
        work = stripEmphasis(work)
        work = ESCAPED.replace(work) { it.value.substring(1) }
        text.append(restoreCode(work, code))
    }

    /** [source] with each code span replaced by a marker, whose text is put back by [restoreCode]. */
    private fun protectCode(source: String, code: MutableList<String>): String {
        val out = StringBuilder()
        var at = 0
        while (at < source.length) {
            val open = source.indexOf('`', at)
            if (open < 0) {
                out.append(source, at, source.length)
                break
            }
            val length = run(source, open, '`')
            val closing = closingRun(source, open + length, length)
            if (closing < 0) {
                out.append(source, at, open + length)
                at = open + length
                continue
            }
            out.append(source, at, open)
            code += codeText(source.substring(open + length, closing))
            out.append(CODE_MARKER).append(code.lastIndex).append(MARKER_END)
            at = closing + length
        }
        return out.toString()
    }

    /** The index of the run of [length] backticks that closes the span from [from] on, -1 when it never closes. */
    private fun closingRun(source: String, from: Int, length: Int): Int {
        var search = from
        while (search < source.length) {
            val closing = source.indexOf('`', search)
            if (closing < 0) return -1
            val closingLength = run(source, closing, '`')
            if (closingLength == length) return closing
            search = closing + closingLength
        }
        return -1
    }

    /** [source] with what emphasis and strikethrough wrap kept and their markers left out. */
    private fun stripEmphasis(source: String): String {
        var result = source
        var previous = ""
        while (result != previous) {
            previous = result
            result = EMPHASIS.replace(result) { it.groupValues[2] }
            result = UNDERSCORE_EMPHASIS.replace(result) { it.groupValues[1] }
        }
        return result
    }

    /** [source] with the text kept by [protectCode] put back. */
    private fun restoreCode(source: String, code: List<String>): String =
        CODE_MARKER_IN_TEXT.replace(source) { code.getOrElse(it.groupValues[1].toInt()) { "" } }
}

/** An opening fence of a code block: how far it is indented and what it is written with. */
private class Fence(private val indent: Int, private val marker: Char, private val length: Int) {
    /** Whether [line] is the fence that closes this one. */
    fun closedBy(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= length && trimmed.all { it == marker }
    }

    /** [line] without the indentation of the opening fence. */
    fun unindented(line: String): String {
        var start = 0
        while (start < indent && start < line.length && (line[start] == ' ' || line[start] == '\t')) start++
        return line.substring(start)
    }
}

/** The fence that opens the code block [line] starts, null when the line starts none. */
private fun fenceOpening(line: String): Fence? {
    val match = FENCE.matchEntire(line) ?: return null
    val (indent, marker, info) = match.destructured
    // A backtick fence's info string may not hold a backtick, which the code of a span would write there.
    if (marker[0] == '`' && '`' in info) return null
    return Fence(indent.length, marker[0], marker.length)
}

/** Whether the row that lines a table's cells up follows the row at [at]. */
private fun startsTable(lines: List<String>, at: Int): Boolean =
    at + 1 < lines.size && '|' in lines[at] && '|' in lines[at + 1] && DELIMITER_ROW.matches(lines[at + 1])

/** How many of [char] start at [at]. */
private fun run(source: String, at: Int, char: Char): Int {
    var end = at
    while (end < source.length && source[end] == char) end++
    return end - at
}

/** The text of a code span: line breaks as spaces, without one space on each side. */
private fun codeText(raw: String): String {
    val text = raw.replace('\n', ' ')
    val padded = text.length >= 2 && text.first() == ' ' && text.last() == ' ' && text.isNotBlank()
    return if (padded) text.substring(1, text.length - 1) else text
}

/** What a line that only says how a table's cells line up is written with. */
private const val RULE_TEXT = "———"

/** What a marker in the text being built starts with, and what ends it: not a character a message holds. */
private const val IMAGE_MARKER = "\u0000I"
private const val CODE_MARKER = "\u0000C"
private const val MARKER_END = "\u0000"

private val IMAGE_MARKER_IN_TEXT = Regex("\u0000I(\\d+)\u0000")
private val CODE_MARKER_IN_TEXT = Regex("\u0000C(\\d+)\u0000")

/** Emphasis and strikethrough, which keep what they wrap and lose the marker that wraps it. */
private val EMPHASIS = Regex("""(?<!\\)(\*\*|__|~~|\*)(?=\S)(.+?)(?<=\S)\1""")

/** Emphasis written with one underscore, which no word of its own may hold. */
private val UNDERSCORE_EMPHASIS = Regex("""(?<![\w\\])_(?=\S)(.+?)(?<=\S)_(?![\w])""")

private val FENCE = Regex("""^([ \t]*)(`{3,}|~{3,})(.*)$""")

private val RULE = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")

private val HEADING = Regex("""^ {0,3}#{1,6}(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")

private val QUOTE = Regex("""^ {0,3}(?:>[ \t]?)+(.*)$""")

private val BULLET = Regex("""^([ \t]*[-*+][ \t]+)(.*)$""")

private val ORDERED = Regex("""^([ \t]*\d{1,9}[.)][ \t]+)(.*)$""")

private val DELIMITER_ROW = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")

/** An image, which becomes a segment of its own. */
private val IMAGE = Regex("""(?<!\\)!\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

/** A link, whose text keeps its address beside it where the address says more than the text. */
private val LINK = Regex("""(?<!\\)\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

/** A character that is written as itself. */
private val ESCAPED = Regex("""\\[!-/:-@\[-`{-~]""")
