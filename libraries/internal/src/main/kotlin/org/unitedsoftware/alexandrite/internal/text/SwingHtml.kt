package org.unitedsoftware.alexandrite.internal.text

import org.unitedsoftware.alexandrite.internal.portable.JdkOnly
import java.io.StringReader
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

/** Reads pages by the rules of the SDK's `HtmlParser` with Swing's HTML parser. */
@JdkOnly
public object SwingHtml {
    public fun read(html: String, baseUrl: String?): HtmlReading {
        val source = prepared(html)
        val events = SwingEvents(source, HtmlReader(baseUrl))
        // Swing's parser renames one shared element for each unknown tag it meets
        synchronized(this) { ParserDelegator().parse(StringReader(source), events, true) }
        return events.read()
    }
}

/**
 * Hands Swing's events to [reader], holding back the end of an element that Swing closes early by HTML 3.2's rules
 * until the end tag in [source] that closes it.
 */
@JdkOnly
private class SwingEvents(private val source: String, private val reader: HtmlReader) : HTMLEditorKit.ParserCallback() {
    /** The elements Swing closed early, with where their end tags are. */
    private val held = mutableListOf<Pair<String, Int>>()

    override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
        val name = tag.toString().lowercase()
        catchUp(position, name)
        reader.start(name, attributes.getAttribute(HTML.Attribute.HREF) as? String)
    }

    override fun handleEndTag(tag: HTML.Tag, position: Int) {
        val name = tag.toString().lowercase()
        catchUp(position, null)
        if (name !in STRUCTURE && !endTagAt(position, name)) {
            held += name to nextEndTag(position, name)
        } else {
            reader.end(name)
        }
    }

    override fun handleSimpleTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
        val name = tag.toString().lowercase()
        catchUp(position, name)
        if (attributes.getAttribute(HTML.Attribute.ENDTAG) != null) {
            reader.end(name)
        } else {
            reader.start(name, attributes.getAttribute(HTML.Attribute.HREF) as? String)
        }
    }

    override fun handleComment(data: CharArray, position: Int) {
        catchUp(position, null)
        reader.comment()
    }

    /** Splits the text Swing joined across an end tag it dropped, at the start of the part after it. */
    override fun handleText(data: CharArray, position: Int) {
        val text = String(data).replace(PRE_LINE_FEED, '\n')
        val due = due(position, null)
        if (due.isEmpty()) return reader.text(text)
        val after = sourceText(position).takeIf { text.endsWith(it) } ?: text
        reader.text(text.dropLast(after.length))
        due.forEach { reader.end(it.first) }
        reader.text(after)
    }

    fun read(): HtmlReading {
        held.forEach { reader.end(it.first) }
        held.clear()
        return reader.read()
    }

    /** Ends the held elements whose end tags lie before [position], every held link too when a link [starting] there. */
    private fun catchUp(position: Int, starting: String?) {
        due(position, starting).forEach { reader.end(it.first) }
    }

    private fun due(position: Int, starting: String?): List<Pair<String, Int>> {
        if (held.isEmpty()) return emptyList()
        val due = held.filter { (name, end) -> end <= position || (starting == "a" && name == "a") }
        held -= due.toSet()
        return due
    }

    /** The text of [source] from [position] to the next tag, as Swing reports it. */
    private fun sourceText(position: Int): String {
        val raw = source.substring(position, source.indexOf('<', position).takeIf { it >= 0 } ?: source.length)
        return if (PRE_LINE_FEED in raw) raw.replace(PRE_LINE_FEED, '\n') else raw.replace(WHITESPACE_RUN, " ")
    }

    private fun endTagAt(position: Int, name: String): Boolean =
        source.startsWith("</$name", position, ignoreCase = true) &&
            source.getOrNull(position + name.length + 2)?.let(::endsTagName) != false

    private fun nextEndTag(from: Int, name: String): Int {
        var at = source.indexOf("</$name", from, ignoreCase = true)
        while (at >= 0 && !endTagAt(at, name)) at = source.indexOf("</$name", at + 1, ignoreCase = true)
        return if (at < 0) Int.MAX_VALUE else at
    }
}

/**
 * [html] made ready for Swing: dropped elements cut out, a bogus comment of the HTML standard made a comment, a `<`
 * that starts no tag escaped, line ends inside `pre` kept from Swing's trimming, `&apos;` spelled as a number, and an
 * `&` that starts an attribute's parameter name (`?a=1&copy=2`) escaped.
 */
private fun prepared(html: String): String = buildString {
    var index = 0
    var pre = 0
    while (index < html.length) {
        val start = html.indexOf('<', index)
        val textEnd = if (start < 0) html.length else start
        val text = html.substring(index, textEnd).replace("&apos;", "&#39;")
        append(if (pre > 0) text.replace(LINE_END, PRE_LINE_FEED.toString()) else text)
        if (start < 0) break
        if (html.startsWith("<!--", start)) {
            val end = html.indexOf("-->", start + 4)
            if (end < 0) break
            append(html, start, end + 3)
            index = end + 3
            continue
        }
        val closing = html.startsWith("</", start)
        val nameStart = start + if (closing) 2 else 1
        val next = html.getOrNull(nameStart)
        if (next?.isAsciiLetter() != true) {
            if (closing || next == '?') {
                val end = html.indexOf('>', nameStart)
                if (end < 0) break
                append("<!---->")
                index = end + 1
            } else {
                append(if (next == '!') "<" else "&lt;")
                index = start + 1
            }
            continue
        }
        var nameEnd = nameStart
        while (nameEnd < html.length && !endsTagName(html[nameEnd])) nameEnd++
        val name = html.substring(nameStart, nameEnd).lowercase()
        val end = tagEnd(html, nameEnd)
        if (end < 0) break
        if (!closing && name in HIDDEN_ELEMENTS) {
            val close = closingTag(html, name, end + 1)
            if (close < 0) break
            val closeEnd = html.indexOf('>', close)
            if (closeEnd < 0) break
            append(' ')
            index = closeEnd + 1
            continue
        }
        append(html.substring(start, end + 1).replace("&apos;", "&#39;").replace(PARAMETER_AMPERSAND, "&amp;$1="))
        if (name == "pre") pre = (pre + if (closing) -1 else 1).coerceAtLeast(0)
        index = end + 1
    }
}

/** The index of the `>` that ends the tag whose name ends at [from], outside quoted attribute values; -1 if none. */
private fun tagEnd(html: String, from: Int): Int {
    var index = from
    var afterEquals = false
    while (index < html.length) {
        val c = html[index]
        when {
            c == '>' -> return index

            afterEquals && (c == '"' || c == '\'') -> {
                index = html.indexOf(c, index + 1)
                if (index < 0) return -1
                afterEquals = false
            }

            c == '=' -> afterEquals = true

            !c.isWhitespace() -> afterEquals = false
        }
        index++
    }
    return -1
}

/** The index of the end tag of [name] at or after [from], -1 if none. */
private fun closingTag(html: String, name: String, from: Int): Int {
    var at = html.indexOf("</$name", from, ignoreCase = true)
    while (at >= 0) {
        val after = html.getOrNull(at + name.length + 2)
        if (after == null || endsTagName(after)) return at
        at = html.indexOf("</$name", at + 1, ignoreCase = true)
    }
    return -1
}

private fun endsTagName(c: Char): Boolean = c.isWhitespace() || c == '/' || c == '>'

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** The elements whose ends Swing places where the HTML standard does. */
private val STRUCTURE = setOf("html", "head", "body", "title")

/** What stands for a line feed inside `pre` while Swing parses, which it would drop before an end tag. */
private const val PRE_LINE_FEED = '\uE000'

private val LINE_END = Regex("\r\n?|\n")

private val WHITESPACE_RUN = Regex("[ \t\n\u000C\r]+")

private val PARAMETER_AMPERSAND = Regex("&([A-Za-z0-9]+)=")
