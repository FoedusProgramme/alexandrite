package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.tools.media.SWING_HTML
import java.io.StringReader
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebFetchTextTest {
    @Test
    fun `web fetch reads representative pages as it did before the parser port`() {
        for ((name, html) in PAGES) {
            val earlier = earlierPageText(html)
            assertTrue(earlier.length > 100, name)
            assertEquals(earlier, oneLine(SWING_HTML.parse(html, "https://example.org/page")), "$name\n$earlier")
        }
    }

    @Test
    fun `tags of every kind separate words as before`() {
        val snippets = listOf(
            "<p>Run this:<br>then restart.</p>",
            "<p>One<hr>two</p>",
            "<header>Moss</header><main>Notes</main>",
            "<p>a<b>b</b>c<img src=x>d<wbr>e</p>",
            "a<!-- <script> -->b<script>c</script>d",
            "<pre>  a\n    b</pre>",
            "<ul><li>one<li>two</ul><table><tr><td>a<td>b</table>",
        )
        for (html in snippets) assertEquals(earlierPageText(html), oneLine(SWING_HTML.parse(html, null)), html)
    }

    @Test
    fun `words the earlier reading ran together or dropped are now read as the HTML standard does`() {
        val changed = listOf(
            "<a href=x><div>Card</div>more</a>tail" to ("Card moretail" to "Card more tail"),
            "<span>s<div>block</div>t</span>u" to ("s block tu" to "s block t u"),
            "<p>It&apos;s</p>" to ("It&apos;s" to "It's"),
            "a < b and c<d" to ("a" to "a < b and c"),
        )
        for ((html, texts) in changed) {
            assertEquals(texts.first, earlierPageText(html), html)
            assertEquals(texts.second, oneLine(SWING_HTML.parse(html, null)), html)
        }
    }

    @Test
    fun `a page is fetched as its title and text on one line`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    header("content-type", "text/html; charset=utf-8")
                    body(PAGES.getValue("an article"))
                },
            )
            val fetch = WebFetch(WebConfig(allowedPrivateHosts = setOf("localhost")), SWING_HTML)
            val page = fetch.fetch(server.baseUrl.replace("127.0.0.1", "localhost") + "/article")
            assertEquals(earlierPageText(PAGES.getValue("an article")), page.text)
            server.assertFinished()
        }
    }

    @Test
    fun `without an HTML parser only pages of plain text are read`() = runBlocking {
        FakeModelServer().use { server ->
            val url = server.baseUrl.replace("127.0.0.1", "localhost")
            server.enqueue(fakeResponse { header("content-type", "text/plain").body("plain words") })
            server.enqueue(fakeResponse { header("content-type", "text/html").body("<p>markup</p>") })
            val fetch = WebFetch(WebConfig(allowedPrivateHosts = setOf("localhost")), null as HtmlParser?)
            assertEquals("plain words", fetch.fetch("$url/plain").text)
            val refused = assertFailsWith<IllegalArgumentException> { fetch.fetch("$url/page") }
            assertEquals(
                "This host cannot read HTML pages: no plugin provides an HTML parser (such as alexandrite-engine-jsoup).",
                refused.message,
            )
        }
    }
}

private val PAGES: Map<String, String> = mapOf(
    "an article" to """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Growing moss indoors | Field Notes</title>
          <link rel="stylesheet" href="/assets/site.css">
          <script async src="/assets/analytics.js"></script>
          <script>
            window.queue = window.queue || [];
            if (document.cookie.indexOf("seen=1") < 0 && screen.width > 600) { document.write("<p>Welcome</p>"); }
          </script>
          <style>
            .hero > h1 { font-size: 2rem; }
            a[href^="http"]::after { content: " ↗"; }
          </style>
        </head>
        <body class="article">
          <a class="skip" href="#content">Skip to content</a>
          <header class="site">
            <a href="/" class="logo"><svg width="24" height="24" aria-hidden="true"><use href="#leaf"/></svg> Field Notes</a>
            <nav aria-label="Main">
              <ul>
                <li><a href="/">Home</a></li>
                <li><a href="/topics/">Topics</a></li>
                <li><a href="/about/">About</a></li>
              </ul>
            </nav>
          </header>
          <main id="content">
            <article>
              <h1>Growing moss indoors</h1>
              <p class="byline">By <a href="/authors/r">the editors</a> &middot; <time datetime="2026-03-01">1 March 2026</time></p>
              <p>Moss is one of the <strong>easiest</strong> plants to keep: it needs no soil, little light and only
                 a mist of water every few days. This guide covers <em>three</em> things &mdash; containers, light
                 and water.</p>
              <h2 id="containers">Containers</h2>
              <p>Any glass jar works. A lid keeps the air damp; without one, mist twice as often.</p>
              <ul>
                <li>Jars with a wide mouth</li>
                <li>Shallow bowls &amp; terrariums</li>
                <li>Old aquariums (&gt; 20&nbsp;litres)</li>
              </ul>
              <h2 id="light">Light</h2>
              <p>Indirect light is best. Direct sun dries moss out within hours.</p>
              <blockquote>
                <p>&ldquo;A stone that rolls gathers no moss.&rdquo;</p>
              </blockquote>
              <figure>
                <img src="/img/jar.jpg" alt="A jar of moss" width="640" height="480">
                <figcaption>A closed jar after six months.</figcaption>
              </figure>
              <noscript><img src="/pixel.gif" alt=""></noscript>
              <p>Questions? <a href="/contact">Write to us</a>.</p>
            </article>
            <aside>
              <h2>Related</h2>
              <ol>
                <li><a href="/ferns">Ferns in the shade</a></li>
                <li><a href="/lichen">What lichen needs</a></li>
              </ol>
            </aside>
          </main>
          <footer>
            <p>&copy; 2026 Field Notes. <a href="/privacy">Privacy</a></p>
          </footer>
          <script>document.querySelectorAll("a").forEach(function (a) { a.rel = "noopener"; });</script>
        </body>
        </html>
    """.trimIndent(),
    "a reference page" to """
        <!doctype html>
        <html>
        <head>
          <title>moss.grow &mdash; API reference</title>
          <meta name="description" content="Reference for the grow function">
        </head>
        <body>
          <div class="layout">
            <div class="sidebar">
              <input type="search" placeholder="Search the docs">
              <ul class="toc">
                <li><a href="#grow">grow</a></li>
                <li><a href="#water">water</a></li>
              </ul>
            </div>
            <div class="content">
              <h1 id="grow"><code>grow(moss, days)</code></h1>
              <p>Returns the size of <var>moss</var> after <var>days</var> days.</p>
              <pre><code class="language-python">def grow(moss, days):
            for day in range(days):
                moss = moss * 1.01
            return moss
        </code></pre>
              <table>
                <thead>
                  <tr><th>Parameter</th><th>Type</th><th>Meaning</th></tr>
                </thead>
                <tbody>
                  <tr><td><code>moss</code></td><td>float</td><td>The size today</td></tr>
                  <tr><td><code>days</code></td><td>int</td><td>How long to wait</td></tr>
                </tbody>
              </table>
              <h2 id="water">water</h2>
              <dl>
                <dt>Since</dt>
                <dd>Version 2.1</dd>
                <dt>Raises</dt>
                <dd><code>ValueError</code> when <code>days</code> is negative.</dd>
              </dl>
              <div class="note"><strong>Note:</strong> sizes are in square centimetres.</div>
            </div>
          </div>
        </body>
        </html>
    """.trimIndent(),
    "a listing" to """
        <html>
        <head>
          <meta charset="utf-8">
          <title>Latest notes</title>
          <link rel="alternate" type="application/rss+xml" href="/feed.xml">
        </head>
        <body>
          <form action="/search" method="get">
            <label for="q">Search notes</label>
            <input id="q" name="q" type="text">
            <select name="sort">
              <option value="new" selected>Newest first</option>
              <option value="old">Oldest first</option>
            </select>
            <button type="submit">Search</button>
          </form>
          <section class="cards">
            <article class="card">
              <a href="/notes/moss">
                <div class="card-body">
                  <h3>Moss in jars</h3>
                  <p>Three things to get right.</p>
                </div>
              </a>
            </article>
            <article class="card">
              <a href="/notes/ferns">
                <div class="card-body">
                  <h3>Ferns &amp; shade</h3>
                  <p>Why the north window wins.</p>
                </div>
              </a>
            </article>
          </section>
          <table class="archive">
            <caption>Archive</caption>
            <tr><th>Month</th><th>Notes</th></tr>
            <tr><td>February</td><td>12</td></tr>
            <tr><td>January</td><td>9</td></tr>
          </table>
          <p class="pages">Page 1 of 4 &rsaquo; <a href="?page=2">Next</a></p>
          <!-- generated in 12 ms -->
        </body>
        </html>
    """.trimIndent(),
)

/** The text `web.fetch` read from [html] before it read pages through an [HtmlParser]. */
private fun earlierPageText(html: String): String {
    val text = StringBuilder()
    var hidden = 0
    val hiddenTags = setOf("script", "style", "noscript")
    val callback = object : HTMLEditorKit.ParserCallback() {
        override fun handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, position: Int) {
            if (tag.toString() in hiddenTags) hidden++
            if (hidden == 0) text.append(' ')
        }

        override fun handleEndTag(tag: HTML.Tag, position: Int) {
            if (tag.toString() in hiddenTags) hidden = (hidden - 1).coerceAtLeast(0)
            if (hidden == 0) text.append(' ')
        }

        override fun handleText(data: CharArray, position: Int) {
            if (hidden == 0) text.append(data).append(' ')
        }
    }
    ParserDelegator().parse(StringReader(withoutHiddenHtml(html)), callback, true)
    return text.toString().replace(Regex("\\s+"), " ").trim()
}

private fun withoutHiddenHtml(html: String): String = buildString {
    var index = 0
    while (index < html.length) {
        val start = html.indexOf('<', index)
        if (start < 0) {
            append(html, index, html.length)
            break
        }
        append(html, index, start)
        val end = html.indexOf('>', start + 1)
        if (end < 0) break
        val tag = html.substring(start + 1, end).trimStart().takeWhile { it.isLetterOrDigit() }.lowercase()
        if (tag in setOf("script", "style", "noscript")) {
            var close = html.indexOf("</$tag", end + 1, ignoreCase = true)
            while (close >= 0) {
                val after = close + tag.length + 2
                if (after == html.length || html[after].isWhitespace() || html[after] == '>') break
                close = html.indexOf("</$tag", after, ignoreCase = true)
            }
            if (close < 0) break
            val closingEnd = html.indexOf('>', close)
            if (closingEnd < 0) break
            append(' ')
            index = closingEnd + 1
        } else {
            append(html, start, end + 1)
            index = end + 1
        }
    }
}
