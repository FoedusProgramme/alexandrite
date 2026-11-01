package org.unitedsoftware.alexandrite.internal.text

/** A page's title, text and links as the SDK's `HtmlParser` reads them. */
public class HtmlReading(public val title: String?, public val text: String, public val links: List<Link>) {
    public class Link(public val href: String, public val text: String)
}

/**
 * Reads a page by the rules of the SDK's `HtmlParser` from its elements and text, as a parser reports them in document
 * order, [baseUrl] resolving its links.
 */
public class HtmlReader(private val baseUrl: String?) {
    private val lines = mutableListOf<String>()
    private val line = StringBuilder()

    /** Whether a word ended since the last character of [line]. */
    private var space = false

    /** The depth of dropped elements the reader is in. */
    private var hidden = 0
    private var inHead = false

    /** The text of the head's first title, while it is read. */
    private var titleText: StringBuilder? = null
    private var title: String? = null
    private var titled = false

    /** The depth of `pre` elements the reader is in. */
    private var pre = 0

    /** The index in [lines] where the outermost `pre` began. */
    private var preStart = 0

    /** Whether the last character in a `pre` was a carriage return. */
    private var carriageReturn = false
    private var baseHref: String? = null
    private val links = mutableListOf<Pair<String, String>>()

    /** The href of the link being read, null outside one. */
    private var linkHref: String? = null
    private val linkText = StringBuilder()
    private var linkSpace = false

    /** The start of the element [name], [href] its `href` attribute. */
    public fun start(name: String, href: String? = null) {
        if (hidden > 0) {
            if (name in HIDDEN_ELEMENTS) hidden++
            return
        }
        when (name) {
            in HIDDEN_ELEMENTS -> {
                separate(name)
                hidden++
                return
            }

            "head" -> inHead = true

            "title" -> if (inHead && !titled) titleText = StringBuilder()

            "base" -> if (baseHref == null && href != null) baseHref = href

            "a" -> {
                closeLink()
                if (href != null) linkHref = href
            }

            "pre" -> {
                lineBreak()
                if (pre++ == 0) preStart = lines.size
            }
        }
        separate(name)
    }

    /** The end of the element [name]. */
    public fun end(name: String) {
        if (hidden > 0) {
            if (name in HIDDEN_ELEMENTS) hidden--
            if (hidden == 0) separate(name)
            return
        }
        when (name) {
            "head" -> inHead = false

            "title" -> titleText?.let {
                title = collapsed(it)
                titleText = null
                titled = true
            }

            "a" -> closeLink()

            "pre" -> if (pre > 0 && --pre == 0) endPre()
        }
        separate(name)
    }

    public fun comment() {
        if (hidden == 0 && titleText == null) boundary()
    }

    public fun text(text: String) {
        if (hidden > 0) return
        titleText?.let {
            it.append(text)
            return
        }
        if (inHead) return
        for (c in text) {
            when {
                pre > 0 -> preCharacter(c)

                c in WHITESPACE -> {
                    space = true
                    linkSpace = true
                }

                else -> character(c)
            }
        }
    }

    public fun read(): HtmlReading {
        closeLink()
        titleText?.let { title = collapsed(it) }
        if (pre > 0) {
            pre = 0
            endPre()
        }
        lineBreak()
        val base = documentBase()
        return HtmlReading(
            title,
            lines.joinToString("\n"),
            links.map { (href, text) ->
                HtmlReading.Link(cleaned(href).let { base?.let { b -> resolved(b, it) } ?: it }, text)
            },
        )
    }

    private fun separate(name: String) {
        when (name) {
            "br", "hr" -> lineBreak()
            in BLOCK_ELEMENTS -> lineBreak()
            else -> boundary()
        }
    }

    private fun boundary() {
        if (pre > 0) return
        space = true
        linkSpace = true
    }

    private fun lineBreak() {
        if (pre > 0) {
            val kept = line.trimEnd(::isWhitespace)
            if (kept.isNotEmpty()) lines += kept.toString()
        } else if (line.isNotEmpty()) {
            lines += line.toString()
        }
        line.setLength(0)
        space = false
        linkSpace = true
    }

    private fun character(c: Char) {
        if (space && line.isNotEmpty()) line.append(' ')
        space = false
        line.append(c)
        linkCharacter(c)
    }

    private fun preCharacter(c: Char) {
        val afterReturn = carriageReturn
        carriageReturn = c == '\r'
        when {
            c == '\n' && afterReturn -> Unit

            c == '\n' || c == '\r' -> {
                lines += line.trimEnd(::isWhitespace).toString()
                line.setLength(0)
                linkSpace = true
            }

            c in WHITESPACE -> {
                line.append(c)
                linkSpace = true
            }

            else -> {
                line.append(c)
                linkCharacter(c)
            }
        }
    }

    private fun linkCharacter(c: Char) {
        if (linkHref == null) return
        if (linkSpace && linkText.isNotEmpty()) linkText.append(' ')
        linkSpace = false
        linkText.append(c)
    }

    /** Ends the outermost `pre`, dropping the empty lines at its start and end. */
    private fun endPre() {
        val kept = line.trimEnd(::isWhitespace)
        if (kept.isNotEmpty()) lines += kept.toString()
        line.setLength(0)
        while (lines.size > preStart && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        while (lines.size > preStart && lines[preStart].isEmpty()) lines.removeAt(preStart)
        carriageReturn = false
        space = false
    }

    private fun closeLink() {
        val href = linkHref ?: return
        links += href to linkText.toString()
        linkHref = null
        linkText.setLength(0)
        linkSpace = false
    }

    /** The absolute URL that links resolve against, null when there is none. */
    private fun documentBase(): String? {
        val page = baseUrl?.takeIf(::isAbsoluteUrl)
        val declared = baseHref?.let(::cleaned)?.let { if (page != null) resolved(page, it) else it }
        return declared?.takeIf(::isAbsoluteUrl) ?: page
    }
}

/** The elements whose start and end end the line. */
internal val BLOCK_ELEMENTS: Set<String> = setOf(
    "address", "article", "aside", "blockquote", "body", "caption", "center", "dd", "details", "dialog", "dir", "div",
    "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "frameset", "h1", "h2", "h3", "h4", "h5", "h6",
    "head", "header", "hgroup", "html", "legend", "li", "listing", "main", "menu", "nav", "ol", "optgroup", "option",
    "p", "plaintext", "pre", "search", "section", "summary", "table", "tbody", "tfoot", "thead", "tr", "ul", "xmp",
)

internal val HIDDEN_ELEMENTS: Set<String> = setOf("script", "style", "noscript")

private val WHITESPACE = charArrayOf(' ', '\t', '\n', '\u000C', '\r')

private fun isWhitespace(c: Char): Boolean = c in WHITESPACE

/** [text] with its whitespace collapsed and trimmed, null when nothing is left. */
private fun collapsed(text: CharSequence): String? = buildString {
    var space = false
    for (c in text) {
        if (isWhitespace(c)) {
            space = isNotEmpty()
        } else {
            if (space) append(' ')
            space = false
            append(c)
        }
    }
}.takeIf { it.isNotEmpty() }

/** [href] without tabs and line breaks, and without the controls and spaces around it. */
private fun cleaned(href: String): String = href.filterNot { it == '\t' || it == '\n' || it == '\r' }.trim { it <= ' ' }
