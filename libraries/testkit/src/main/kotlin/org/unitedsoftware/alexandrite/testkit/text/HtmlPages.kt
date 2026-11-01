package org.unitedsoftware.alexandrite.testkit.text

import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlLink
import org.unitedsoftware.alexandrite.testkit.HtmlParserCheck

internal val HTML_PAGES: List<HtmlParserCheck> by lazy {
    listOf(
        page(
            "an article",
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width">
              <title>Field Notes &mdash; Moss</title>
              <link rel="stylesheet" href="/site.css">
              <script>window.queue = []; if (a < b && c > d) { document.write("</p>"); }</script>
              <style>body { font: 16px/1.5 serif; } p > a { color: red; }</style>
            </head>
            <body>
              <header>
                <nav>
                  <ul>
                    <li><a href="/">Home</a></li>
                    <li><a href="/notes/">Notes</a></li>
                    <li><a href="https://elsewhere.example/about">About</a></li>
                  </ul>
                </nav>
              </header>
              <main>
                <article>
                  <h1>On moss</h1>
                  <p>Moss grows <em>slowly</em> in the <a href="shade.html">shade</a> and keeps water.</p>
                  <p>It needs no soil &amp; little light&nbsp;&ndash; see <a href="../plants/fern.html#care">ferns</a>.</p>
                  <blockquote><p>A stone that rolls gathers none.</p></blockquote>
                  <ol>
                    <li>Collect it.</li>
                    <li>Keep it damp.</li>
                  </ol>
                  <figure>
                    <img src="moss.jpg" alt="Moss on a stone">
                    <figcaption>Moss on a stone, &copy; 2026</figcaption>
                  </figure>
                </article>
              </main>
              <footer><p>Written in &#8220;spring&#8221;.</p></footer>
            </body>
            </html>
            """,
            "https://example.org/notes/moss/index.html",
            "Field Notes — Moss",
            listOf(
                "Home",
                "Notes",
                "About",
                "On moss",
                "Moss grows slowly in the shade and keeps water.",
                "It needs no soil & little light – see ferns .",
                "A stone that rolls gathers none.",
                "Collect it.",
                "Keep it damp.",
                "Moss on a stone, © 2026",
                "Written in “spring”.",
            ),
            HtmlLink("https://example.org/", "Home"),
            HtmlLink("https://example.org/notes/", "Notes"),
            HtmlLink("https://elsewhere.example/about", "About"),
            HtmlLink("https://example.org/notes/moss/shade.html", "shade"),
            HtmlLink("https://example.org/notes/plants/fern.html#care", "ferns"),
        ),
        page(
            "a reference page",
            """
            <html><head><title>API   reference</title><base href="/docs/v2/"></head>
            <body>
            <h2 id="install">Install</h2>
            <p>Run this:<br>then restart.</p>
            <pre><code><span class="k">def</span> <span class="f">grow</span>(moss):
                <span class="k">return</span> moss * 2
            </code></pre>
            <pre>
              first line

              third line${"  "}
            </pre>
            <table>
              <thead><tr><th>Name</th><th>Kind</th></tr></thead>
              <tbody>
                <tr><td><code>grow</code></td><td>function</td></tr>
                <tr><td>Moss</td><td>class</td></tr>
              </tbody>
            </table>
            <dl><dt>Term</dt><dd>Its meaning.</dd></dl>
            <hr>
            <details><summary>More</summary><p>Hidden until opened.</p></details>
            <p>See <a href="guide.html">the guide</a> or <a href="/faq">the questions</a>.</p>
            </body></html>
            """,
            "https://docs.example.com/start",
            "API reference",
            listOf(
                "Install",
                "Run this:",
                "then restart.",
                "def grow(moss):",
                "    return moss * 2",
                "  first line",
                "",
                "  third line",
                "Name Kind",
                "grow function",
                "Moss class",
                "Term",
                "Its meaning.",
                "More",
                "Hidden until opened.",
                "See the guide or the questions .",
            ),
            HtmlLink("https://docs.example.com/docs/v2/guide.html", "the guide"),
            HtmlLink("https://docs.example.com/faq", "the questions"),
        ),
        page(
            "links of every form",
            """
            <head><title>Links</title></head>
            <body>
            <a href="https://other.example/x?y=1#z">absolute</a>
            <a href="//cdn.example/lib.js">scheme-relative</a>
            <a href="/root/path">root</a>
            <a href="sibling">sibling</a>
            <a href="./here/../there/./page">dots</a>
            <a href="../../../../up">far up</a>
            <a href="?q=moss&amp;page=2">query</a>
            <a href="#top">fragment</a>
            <a href="">empty</a>
            <a href=" spaced/out.html ">spaced</a>
            <a href="mailto:moss@example.org">mail</a>
            <a href="/card"><div class="card"><h3>Card title</h3><p>Card text</p></div></a>
            <a href="/picture"><img src="p.png" alt="a picture"></a>
            <a name="anchor">no link</a>
            <p><a href="/one">one</a><a href="/two">two</a></p>
            </body>
            """,
            "https://example.org/a/b/c?old=1#frag",
            "Links",
            listOf(
                "absolute scheme-relative root sibling dots far up query fragment empty spaced mail",
                "Card title",
                "Card text",
                "no link",
                "one two",
            ),
            HtmlLink("https://other.example/x?y=1#z", "absolute"),
            HtmlLink("https://cdn.example/lib.js", "scheme-relative"),
            HtmlLink("https://example.org/root/path", "root"),
            HtmlLink("https://example.org/a/b/sibling", "sibling"),
            HtmlLink("https://example.org/a/b/there/page", "dots"),
            HtmlLink("https://example.org/up", "far up"),
            HtmlLink("https://example.org/a/b/c?q=moss&page=2", "query"),
            HtmlLink("https://example.org/a/b/c?old=1#top", "fragment"),
            HtmlLink("https://example.org/a/b/c?old=1", "empty"),
            HtmlLink("https://example.org/a/b/spaced/out.html", "spaced"),
            HtmlLink("mailto:moss@example.org", "mail"),
            HtmlLink("https://example.org/card", "Card title Card text"),
            HtmlLink("https://example.org/picture", ""),
            HtmlLink("https://example.org/one", "one"),
            HtmlLink("https://example.org/two", "two"),
        ),
        page(
            "dropped content",
            """
            <html><head><title>Dropped</title>
            <noscript><style>.js { display: none }</style></noscript></head>
            <body>
            <p>Before<script type="text/javascript">
              var html = '<div class="x">' + "</div>"; if (a<b) { alert("</p>"); }
            </script>after.</p>
            <!-- a comment with <script> inside and <a href="/hidden">a link</a> -->
            <noscript><p>Turn on scripts.</p><a href="/noscript">no</a></noscript>
            <style>
              .note::after { content: "</style"; }
            </style>
            <p>It&apos;s kept &amp; shown.</p>
            </body></html>
            """,
            null,
            "Dropped",
            listOf("Before after.", "It's kept & shown."),
        ),
        page(
            "dropped content inside pre",
            """
            <pre>a<script>x()</script>b<style>p { }</style>c
            d<noscript>no</noscript>e</pre>
            """,
            null,
            null,
            listOf("abc", "de"),
        ),
        page(
            "private-use characters",
            "<p>&#xE000; icon &#57344;</p><pre>a&#xE000;b\nc</pre>",
            null,
            null,
            listOf(" icon ", "ab", "c"),
        ),
        page(
            "a page without its optional tags",
            """
            <!doctype html>
            <meta charset=utf-8>
            <title>Short</title>
            <p>First paragraph
            <p>Second <b>bold</b> paragraph
            <ul><li>one<li>two</ul>
            """,
            null,
            "Short",
            listOf("First paragraph", "Second bold paragraph", "one", "two"),
        ),
        page(
            "forms and the boundaries of elements",
            """
            <body>
            <form action="/search"><label for=q>Search</label> <input id=q name=q><button>Go</button></form>
            <p>a<b>b</b>c d<img src=x.png>e f<wbr>g h<br>i</p>
            <select><option>First</option><option selected>Second</option></select>
            <textarea>Some   text
            here</textarea>
            <p>Tab${"\t"}and&#9;tab, line&#10;feed, kept&nbsp;&nbsp;spaces.</p>
            </body>
            """,
            null,
            null,
            listOf(
                "Search Go",
                "a b c d e f g h",
                "i",
                "First",
                "Second",
                "Some text here",
                "Tab and tab, line feed, kept  spaces.",
            ),
        ),
        page(
            "inline graphics",
            """
            <body><p>Icon <svg viewBox="0 0 10 10"><title>a leaf</title><path d="M0 0h10v10z"/></svg> label</p>
            <svg><text x="0" y="10">Chart caption</text></svg></body>
            """,
            null,
            null,
            listOf("Icon a leaf label", "Chart caption"),
        ),
        page(
            "character references",
            """
            <p>&lt;tag&gt; &quot;q&quot; &#39;s&#x27; &eacute;&Eacute; &euro;5 &alpha;&beta; &rarr; &hellip; &trade;</p>
            <p>1 < 2 &amp;&amp; 3 > 2</p>
            """,
            null,
            null,
            listOf("<tag> \"q\" 's' éÉ €5 αβ → … ™", "1 < 2 && 3 > 2"),
        ),
        page(
            "a relative base without the page's own URL",
            """<base href="relative/"><a href="page.html">relative</a> <a href="https://example.org/x">absolute</a>""",
            null,
            null,
            listOf("relative absolute"),
            HtmlLink("page.html", "relative"),
            HtmlLink("https://example.org/x", "absolute"),
        ),
        page(
            "an absolute base without the page's own URL",
            """<base href="https://example.net/root/"><a href="page.html">page</a>""",
            null,
            null,
            listOf("page"),
            HtmlLink("https://example.net/root/page.html", "page"),
        ),
        page("an empty page", "", null, null, emptyList()),
    )
}

private fun page(
    name: String,
    html: String,
    baseUrl: String?,
    title: String?,
    lines: List<String>,
    vararg links: HtmlLink,
): HtmlParserCheck =
    HtmlParserCheck(name, html.trimIndent(), baseUrl, HtmlDocument(title, lines.joinToString("\n"), links.toList()))
