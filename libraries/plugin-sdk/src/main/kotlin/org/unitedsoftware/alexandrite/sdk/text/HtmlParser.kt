package org.unitedsoftware.alexandrite.sdk.text

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.di.BoundSpi

/**
 * Reads an HTML page as a model reads it: its title, its text and its links.
 *
 * Two implementations give the same document for a page whose element tree the HTML standard builds without moving or
 * reopening elements; where it does (misnested tags, text inside a table but outside its cells), line breaks and link
 * texts may differ. The text is that of the page outside its head, read by these rules:
 * - The content of `script`, `style` and `noscript` elements is dropped, and with it the links inside.
 * - Whitespace is space, tab, line feed, form feed and carriage return; outside `pre`, a run of it reads as one space.
 *   Every other character is kept, U+00A0 among them.
 * - The start and the end of every element separate words (`a<b>b</b>c` reads `a b c`), and so does a comment.
 * - The start and the end of a block element end the line, and so do `br` and `hr`. The block elements are `address`,
 *   `article`, `aside`, `blockquote`, `body`, `caption`, `center`, `dd`, `details`, `dialog`, `dir`, `div`, `dl`, `dt`,
 *   `fieldset`, `figcaption`, `figure`, `footer`, `form`, `frameset`, `h1` to `h6`, `head`, `header`, `hgroup`, `html`,
 *   `legend`, `li`, `listing`, `main`, `menu`, `nav`, `ol`, `optgroup`, `option`, `p`, `plaintext`, `pre`, `search`,
 *   `section`, `summary`, `table`, `tbody`, `tfoot`, `thead`, `tr`, `ul` and `xmp`.
 * - Outside `pre`, lines are trimmed and empty ones dropped.
 * - Inside `pre`, the text is kept as written: element boundaries other than line ends and comments add nothing, each
 *   line feed ends a line, lines lose only their trailing whitespace, and the empty lines at its start and end are
 *   dropped.
 * - Numeric character references and the named ones of HTML 4.01, `&apos;` too, are decoded where they end in `;`.
 *   Whether other `&` sequences are decoded as the HTML standard decodes them is up to the implementation.
 */
@BoundSpi
public interface HtmlParser {
    /** [html] read as a document, [baseUrl] (the page's own URL) resolving its links. */
    public fun parse(html: String, baseUrl: String?): HtmlDocument
}

/** An HTML page as [HtmlParser] reads it. */
@Poko
public class HtmlDocument(
    /** The text of the head's first `title` element on one line, null when it has none or that is blank. */
    public val title: String?,
    /** The lines of the page's text, joined by `\n`. */
    public val text: String,
    /** The page's `a` elements with an `href`, in document order; one that starts inside another ends that one. */
    public val links: List<HtmlLink>,
)

/**
 * A link of a page: its [href] trimmed, with tabs and line breaks removed, and resolved by RFC 3986 alone against the
 * page's base URL. That is the `href` of its first `base` element with one, resolved against the page's own URL, else
 * the page's own URL; an [href] stays as written when that is no absolute URL. Its [text] is the link's text on one line.
 */
@Poko
public class HtmlLink(public val href: String, public val text: String)
