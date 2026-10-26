package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.internal.sandbox.isNonPublicAddress
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.web.PageQuestion
import org.unitedsoftware.alexandrite.sdk.web.WebModels
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend
import org.unitedsoftware.alexandrite.sdk.web.WebSearchResult
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebTest {
    @Test
    fun `URL policy refuses credentials local names schemes and non-public IPv4 and IPv6`() {
        listOf(
            "file:///etc/hosts",
            "ftp://example.com/file",
            "http://user:pass@example.com",
            "http://localhost",
            "http://a.localhost",
        ).forEach {
            assertFailsWith<IllegalArgumentException> { checkedHost(URI(it)) }
        }
        listOf(
            "0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "224.0.0.1", "::", "::1", "fc00::1", "fe80::1", "2002::1",
            "::ffff:127.0.0.1", "2001:db8::1",
        ).forEach {
            assertTrue(isNonPublicAddress(InetAddress.getByName(it)), it)
        }
        assertEquals("example.com", checkedHost(URI("https://example.com/page")))
    }

    @Test
    fun `redirects are validated before another request and DNS answer is pinned`() {
        val public = InetAddress.getByAddress(byteArrayOf(11, 1, 2, 3))
        var calls = 0
        val resolver: (String) -> List<InetAddress> = { host ->
            if (host == "private.example") listOf(InetAddress.getLoopbackAddress()) else listOf(public)
        }
        assertFailsWith<IllegalArgumentException> {
            fetchPage(URI("https://example.com"), 100, 1000, resolver) { _, pinned, _, _ ->
                assertEquals(public, pinned)
                calls++
                WebResponse(302, mapOf("location" to "http://private.example/secret"), byteArrayOf())
            }
        }
        assertEquals(1, calls)
        val page = fetchPage(URI("https://example.com/a"), 100, 1000, resolver) { uri, _, _, _ ->
            if (uri.path == "/a") {
                WebResponse(302, mapOf("location" to "/b"), byteArrayOf())
            } else {
                WebResponse(200, mapOf("content-type" to "text/html"), "<p>ok</p>".toByteArray())
            }
        }
        assertEquals("/b", page.first.path)
        assertEquals("ok", pageText(page.second))
        assertFailsWith<IllegalArgumentException> {
            fetchPage(
                URI("http://example.com"),
                100,
                1000,
                { listOf(public, InetAddress.getLoopbackAddress()) },
            ) { _, _, _, _ ->
                error("Mixed DNS must never connect")
            }
        }
    }

    @Test
    fun `a redirect to another public site is followed, one between a private host and any other never requested`() {
        val public = InetAddress.getByAddress(byteArrayOf(11, 1, 2, 3))
        val requested = mutableListOf<URI>()
        fun fetch(start: String, location: String) = fetchPage(
            URI(start),
            100,
            1000,
            { if (it.startsWith("internal.")) listOf(InetAddress.getLoopbackAddress()) else listOf(public) },
            setOf("internal.example", "internal.example.com"),
        ) { uri, _, _, _ ->
            requested += uri
            if (requested.size == 1) {
                WebResponse(302, mapOf("location" to location), byteArrayOf())
            } else {
                WebResponse(200, mapOf("content-type" to "text/plain"), "ok".toByteArray())
            }
        }
        val refused = listOf(
            "https://internal.example/a" to "https://evil.internal.example/b",
            "https://example.com/a" to "https://internal.example.com/b",
            "https://internal.example/a" to "https://example.com/b",
        )

        for ((start, location) in refused) {
            requested.clear()
            assertFailsWith<IllegalArgumentException> { fetch(start, location) }
            assertEquals(listOf(URI(start)), requested)
        }
        for (location in listOf("https://docs.example.com/b", "https://elsewhere.test/b")) {
            requested.clear()
            assertEquals(location, fetch("https://example.com/a", location).first.toString())
            assertEquals(listOf(URI("https://example.com/a"), URI(location)), requested)
        }
    }

    @Test
    fun `only the special blocks of 192_0 are private, the rest of it is public`() {
        listOf("192.0.0.1", "192.0.2.1").forEach { assertTrue(isNonPublicAddress(InetAddress.getByName(it)), it) }
        listOf("192.0.32.10", "192.0.1.1").forEach { assertFalse(isNonPublicAddress(InetAddress.getByName(it)), it) }
    }

    @Test
    fun `a page is decoded in the charset its content type names, else as UTF-8`() {
        val public = InetAddress.getByAddress(byteArrayOf(11, 1, 2, 3))
        fun fetched(type: String, body: ByteArray) =
            fetchPage(URI("https://example.com"), 100, 1000, { listOf(public) }) { _, _, _, _ ->
                WebResponse(200, mapOf("content-type" to type), body)
            }.second
        assertEquals("café", fetched("text/plain; charset=ISO-8859-1", "café".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("café", fetched("text/plain; charset=\"utf-8\"", "café".toByteArray()))
        assertEquals("café", fetched("text/plain; charset=no-such-charset", "café".toByteArray()))
    }

    @Test
    fun `private host allowances require exact hosts and normalize case and trailing dot`() {
        val config = WebConfig(allowedPrivateHosts = setOf("Private.Example.", "LOCALHOST.", "[::1]"))
        assertEquals(setOf("private.example", "localhost", "::1"), config.normalizedPrivateHosts)
        listOf(
            "", " ", "*.example.com", "example.com/path", "http://example.com", "user@example.com",
            "localhost:8080", "[::1]:8080", "example.com?x", "example.com#x", "example.com..",
            "example.com\\path", "[fe80::1%25eth0]",
        ).forEach { host ->
            assertFailsWith<IllegalArgumentException>(host) { WebConfig(allowedPrivateHosts = setOf(host)) }
        }
        assertEquals("localhost", checkedHost(URI("http://LOCALHOST./"), config.normalizedPrivateHosts))
        assertFailsWith<IllegalArgumentException> {
            checkedHost(URI("http://child.localhost/"), config.normalizedPrivateHosts)
        }
        val loopback = InetAddress.getLoopbackAddress()
        val page = fetchPage(
            URI("http://PRIVATE.EXAMPLE./"),
            100,
            1000,
            resolve = { host ->
                assertEquals("private.example", host)
                listOf(loopback)
            },
            allowedPrivateHosts = config.normalizedPrivateHosts,
        ) { _, pinned, _, _ ->
            assertEquals(loopback, pinned)
            WebResponse(200, mapOf("content-type" to "text/plain"), "allowed".toByteArray())
        }
        assertEquals("allowed", page.second)
    }

    @Test
    fun `WebFetch denies private hosts by default and explicitly allowed localhost preserves plain text`() =
        runBlocking {
            FakeModelServer().use { server ->
                val url = server.baseUrl.replace("127.0.0.1", "localhost") + "/text"
                assertFailsWith<IllegalArgumentException> { WebFetch(WebConfig()).fetch(url) }
                assertFailsWith<IllegalArgumentException> { WebFetch(WebConfig()).fetch(server.baseUrl) }
                assertTrue(server.requests.isEmpty())
                val source = "<script>literal text</script>\nA < B and C > D"
                server.enqueue(
                    fakeResponse {
                        header("content-type", "text/plain; charset=utf-8")
                        body(source)
                    },
                )
                val fetch = WebFetch(WebConfig(allowedPrivateHosts = setOf("LOCALHOST.")))
                assertEquals(source, fetch.fetch(url).text)
                assertEquals("/text", server.awaitRequests(1).single().path)
                server.assertFinished()
            }
        }

    @Test
    fun `WebFetch rechecks redirect host allowance on every hop`() = runBlocking {
        FakeModelServer().use { server ->
            val url = server.baseUrl.replace("127.0.0.1", "localhost") + "/redirect"
            server.enqueue(fakeResponse(302) { header("location", server.baseUrl + "/secret") })
            val fetch = WebFetch(WebConfig(allowedPrivateHosts = setOf("localhost")))
            assertFailsWith<IllegalArgumentException> { fetch.fetch(url) }
            assertEquals("/redirect", server.awaitRequests(1).single().path)
            assertEquals(1, server.requests.size)
            server.assertFinished()
        }
    }

    @Test
    fun `HTTP parser bounds content length chunking and unframed bodies`() {
        fun parse(text: String, max: Int = 20): WebResponse = response(ByteArrayInputStream(text.toByteArray()), max)
        assertEquals("abc", parse("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc").body.toString(Charsets.UTF_8))
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
        assertEquals("abc", parse(chunked).body.toString(Charsets.UTF_8))
        listOf(
            "Content-Length: 21\r\n\r\n",
            "Content-Length: 4\r\n\r\nabc",
            "Transfer-Encoding: chunked\r\n\r\n15\r\n",
            "Content-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n",
            "Content-Encoding: gzip\r\n\r\n",
            "\r\n${"a".repeat(21)}",
        ).forEach { body -> assertFailsWith<IllegalArgumentException> { parse("HTTP/1.1 200 OK\r\n$body") } }
        assertFailsWith<IllegalStateException> {
            response(ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\na".toByteArray()), 20) { error("deadline exceeded") }
        }
    }

    @Test
    fun `repeated headers are joined, and only the framing and redirect headers must come once`() {
        fun parse(text: String): WebResponse = response(ByteArrayInputStream(text.toByteArray()), 20)
        val page = parse("HTTP/1.1 200 OK\r\nSet-Cookie: a=1\r\nVary: Accept\r\nset-cookie: b=2\r\n\r\nabc")

        assertEquals("a=1, b=2", page.headers["set-cookie"])
        assertEquals("abc", page.body.toString(Charsets.UTF_8))
        for (header in listOf("Content-Length: 3", "Location: /a")) {
            assertFailsWith<IllegalArgumentException> { parse("HTTP/1.1 200 OK\r\n$header\r\n$header\r\n\r\nabc") }
        }
    }

    @Test
    fun `cancelling web IO closes its pinned socket immediately`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val reading = CompletableDeferred<Unit>()
            val call = launch {
                webIo { track ->
                    Socket().use { socket ->
                        track(socket)
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                        reading.complete(Unit)
                        socket.getInputStream().read()
                    }
                }
            }
            withTimeout(1.seconds) { reading.await() }
            server.accept().use { remote ->
                withTimeout(1.seconds) { call.cancelAndJoin() }
                remote.soTimeout = 1000
                assertEquals(-1, remote.getInputStream().read())
            }
        }
    }

    @Test
    fun `HTML text drops active content`() {
        val html = "<h1>Title</h1><script>steal()</script><style>hidden</style><p>A &amp; B</p>"
        assertEquals("Title A & B", pageText(html))
    }

    @Test
    fun `fetch exposes only side model answer and taints before model failure`() = runBlocking {
        val context = testToolContext()
        val answers = Answers()
        val tool = FetchTool(WebConfig(), WebPages { WebPage(URI(it), "raw source") }, answers, emptyList())
        assertEquals(ToolRisk.NETWORK_READ, tool.definition.risk)
        val result = tool.execute(args("url" to "https://example.com", "prompt" to "question"), context)
        assertFalse(result.isError)
        assertEquals("answer", result.content.filterIsInstance<TextPart>().single().text)
        assertEquals("raw source", answers.text)
        assertEquals(1, context.untrustedReasons.size)
        answers.failure = IllegalStateException("source must not leak")
        val failure = tool.execute(args("url" to "https://example.com", "prompt" to "question"), context)
        assertTrue(failure.isError)
        val failureText = failure.content.filterIsInstance<TextPart>().single().text
        assertFalse(failureText.contains("source must not leak"))
        assertEquals("Web request failed: the side model could not answer (IllegalStateException)", failureText)
        assertEquals(2, context.untrustedReasons.size)
        answers.failure = CancellationException("cancel")
        assertFailsWith<CancellationException> {
            tool.execute(args("url" to "https://example.com", "prompt" to "q"), context)
        }
        Unit
    }

    @Test
    fun `a fetch's review names its host as untrusted content, and a search's its output`() = runBlocking {
        val context = testToolContext()
        val fetch = FetchTool(WebConfig(), WebPages { WebPage(URI(it), "page") }, Answers(), emptyList())

        val review = fetch.review(args("url" to "https://Docs.Example.org/a", "prompt" to "q"), context)
        val malformed = fetch.review(args("url" to "nowhere", "prompt" to "q"), context)
        val search = SearchTool(WebConfig(), Answers(), emptyList()).review(args("query" to "q"), context)

        assertEquals(true to "docs.example.org", review.untrusted to review.host)
        assertEquals(true to null, malformed.untrusted to malformed.host)
        assertEquals(true to null, search.untrusted to search.host)
    }

    @Test
    fun `a fetch that a redirect takes to another site answers from the page it reached`() = runBlocking {
        val context = testToolContext()
        val answers = Answers()
        fun tool(to: String) = FetchTool(WebConfig(), WebPages { WebPage(URI(to), "page") }, answers, emptyList())
        suspend fun FetchTool.result() = execute(args("url" to "https://example.com/a", "prompt" to "q"), context)

        val results = listOf("https://elsewhere.test/b", "https://docs.example.com/b").map { tool(it).result() }

        assertEquals(listOf(false, false), results.map { it.isError })
    }

    @Test
    fun `a refused or failed fetch tells the model why, and a slow one that it timed out`() = runBlocking {
        val context = testToolContext()
        fun tool(pages: WebPages) = FetchTool(WebConfig(timeoutSeconds = 1), pages, Answers(), emptyList())
        suspend fun FetchTool.text() = execute(args("url" to "https://example.com", "prompt" to "q"), context).content
            .filterIsInstance<TextPart>().single().text

        val refused = tool { throw IllegalArgumentException("Web URL resolves to a non-public network.") }.text()
        assertEquals("Web request refused: Web URL resolves to a non-public network.", refused)
        val failed = tool { error("Web server returned HTTP 404.") }.text()
        assertEquals("Web request failed: Web server returned HTTP 404.", failed)
        val slow = tool {
            delay(5.seconds)
            WebPage(URI(it), "late")
        }.text()
        assertEquals("Web request timed out.", slow)
    }

    @Test
    fun `search prefers native service else configured SPI and validates input`() = runBlocking {
        val answers = Answers()
        var calls = 0
        val backend = object : WebSearchBackend {
            override val id = "custom"
            override suspend fun search(query: WebQuery): List<WebSearchResult> {
                calls++
                return listOf(WebSearchResult("SPI", "https://example.com", "snippet"))
            }
        }
        val tool = SearchTool(WebConfig(searchBackend = "custom"), answers, listOf(backend))
        val context = testToolContext(model = ModelRef.parse("test/model"))
        assertFalse(tool.execute(args("query" to "question"), context).isError)
        assertEquals(1, calls)
        answers.native = listOf(WebSearchResult("Native", "https://example.com", "native snippet"))
        val native = tool.execute(args("query" to "question"), context)
        assertTrue(native.content.filterIsInstance<TextPart>().single().text.startsWith("Native"))
        assertEquals(1, calls)
        assertEquals(ModelRef.parse("test/model") to WebQuery("question", 5), answers.searched)
        assertFalse(tool.execute(args("query" to "question"), testToolContext()).isError)
        assertEquals(2, calls)
        assertEquals(2, context.untrustedReasons.size)
        assertTrue(tool.execute(args("query" to "question", "limit" to "5"), context).isError)
        val zeroLimit = JsonObject(mapOf("query" to JsonPrimitive("q"), "limit" to JsonPrimitive(0)))
        assertTrue(tool.execute(zeroLimit, context).isError)
        assertEquals(2, context.untrustedReasons.size)
        assertTrue(SearchTool(WebConfig(), Answers(), emptyList()).execute(args("query" to "q"), context).isError)
    }

    @Test
    fun `Brave and Tavily adapters send real requests with bounded result parsing`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{
                        "web":{"results":[
                        {"title":"One","url":"https://example.com","description":"desc"}]}
                    }""",
                    )
                },
            )
            server.enqueue(
                fakeResponse {
                    body("""{"results":[{"title":"Two","url":"https://example.com/b","content":"body"}]}""")
                },
            )
            val http = SearchHttp(WebConfig())
            val brave = braveResults(http.send(braveCall("a & b", 3, "fixture-key", server.baseUrl)), 3)
            val tavily = tavilyResults(http.send(tavilyCall("question", 2, "fixture-key", server.baseUrl)), 2)
            assertEquals("desc", brave.single().snippet)
            assertEquals("body", tavily.single().snippet)
            val requests = server.awaitRequests(2)
            assertEquals("GET", requests[0].method)
            assertTrue(requests[0].path.contains("q=a+%26+b&count=3"))
            assertEquals("fixture-key", requests[0].header("X-Subscription-Token"))
            assertEquals("POST", requests[1].method)
            assertEquals(JsonPrimitive("fixture-key"), requests[1].json()["api_key"])
            assertEquals(JsonPrimitive(2), requests[1].json()["max_results"])
            server.enqueue(fakeResponse { body("[]") })
            val notObject = assertFailsWith<IllegalStateException> {
                http.send(braveCall("q", 1, "fixture-key", server.baseUrl))
            }
            assertEquals("The search service answered with something other than a JSON object.", notObject.message)
            http.onDestroy()
            server.assertFinished()
        }
    }

    @Test
    fun `SearXNG's JSON API and DuckDuckGo's scraped HTML are both parsed into bounded results`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"results": [
                            {"title": "One", "url": "https://example.com/one", "content": "first"},
                            {"title": "Two", "url": "https://example.com/two", "content": "second"}
                        ]}""",
                    )
                },
            )
            server.enqueue(
                fakeResponse {
                    header("content-type", "text/html")
                    body(
                        """<div class="results">
                            <div class="result">
                                <a rel="nofollow" class="result__a"
                                   href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fone&amp;rut=a">
                                   Result <b>One</b></a>
                                <a class="result__snippet" href="#">Snippet <b>one</b> text</a>
                            </div>
                            <div class="result">
                                <a rel="nofollow" class="result__a"
                                   href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Ftwo&amp;rut=b">
                                   Result Two</a>
                                <a class="result__snippet" href="#">Snippet two text</a>
                            </div>
                        </div>""",
                    )
                },
            )
            val http = SearchHttp(WebConfig())
            val searxng = searxngResults(http.send(searxngCall("a & b", 5, server.baseUrl)), 5)
            val duckDuckGo = duckDuckGoResults(http.sendText(duckDuckGoCall("a & b", server.baseUrl)), 1)
            assertEquals(listOf("first", "second"), searxng.map { it.snippet })
            assertEquals(listOf("https://example.com/one"), duckDuckGo.map { it.url })
            assertEquals("Result One", duckDuckGo.single().title)
            assertEquals("Snippet one text", duckDuckGo.single().snippet)
            val requests = server.awaitRequests(2)
            assertTrue(requests[0].path.contains("format=json"))
            assertTrue(requests[1].path.contains("/html/"))
            http.onDestroy()
            server.assertFinished()
        }
    }
}

private fun args(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

private class Answers : WebModels {
    var text: String? = null
    var failure: Exception? = null
    var native: List<WebSearchResult>? = null
    var searched: Pair<ModelRef, WebQuery>? = null

    override suspend fun answer(question: PageQuestion): String {
        failure?.let { throw it }
        text = question.page
        return "answer"
    }

    override suspend fun search(model: ModelRef, query: WebQuery): List<WebSearchResult>? =
        native?.also { searched = model to query }
}
