package org.unitedsoftware.alexandrite.tools.web

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.internal.portable.Threads
import org.unitedsoftware.alexandrite.internal.sandbox.isNonPublicAddress
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.text.HtmlDocument
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

internal class WebPage(val url: URI, val text: String)

internal fun interface WebPages {
    suspend fun fetch(url: String): WebPage
}

/** Fetches pages over its own connections, reading HTML with [parser] and other text without one. */
@Singleton
@Binds(WebPages::class)
internal class WebFetch(private val config: WebConfig, private val parser: HtmlParser?) : WebPages {
    override suspend fun fetch(url: String): WebPage = withTimeout(config.timeoutSeconds.seconds) {
        webIo { track ->
            val fetched = fetchPage(
                URI(url),
                config.maxBytes,
                config.timeoutSeconds * 1000,
                allowedPrivateHosts = config.normalizedPrivateHosts,
                exchange = { uri, address, max, timeout -> exchangePinned(uri, address, max, timeout, track) },
            )
            WebPage(fetched.url, readable(fetched).take(config.maxTextChars))
        }
    }

    /** [fetched] as one line of text: a page's title and text, other text as it came. */
    private fun readable(fetched: FetchedText): String {
        if (!fetched.html) return fetched.text
        val parser = parser ?: throw IllegalArgumentException(
            "This host cannot read HTML pages: no plugin provides an HTML parser (such as alexandrite-engine-jsoup).",
        )
        return oneLine(parser.parse(fetched.text, fetched.url.toString()))
    }
}

/** [page]'s title and text on one line. */
internal fun oneLine(page: HtmlDocument): String =
    listOfNotNull(page.title, page.text).joinToString(" ").replace(WHITESPACE_RUN, " ").trim()

/** The text of the response at [url], [html] when it is a page's markup. */
internal class FetchedText(val url: URI, val text: String, val html: Boolean)

internal fun fetchPage(
    start: URI,
    maxBytes: Int,
    timeoutMillis: Int,
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    allowedPrivateHosts: Set<String> = emptySet(),
    exchange: (URI, InetAddress, Int, Int) -> WebResponse = ::exchange,
): FetchedText {
    val allowed = allowedPrivateHosts.map(::normalizeAllowedHost).toSet()
    val site = checkedHost(start, allowed)
    var uri = start
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    repeat(6) {
        val host = checkedHost(uri, allowed)
        val addresses = resolve(host)
        require(addresses.isNotEmpty() && (host in allowed || addresses.none(::isNonPublicAddress))) {
            "Web URL resolves to a non-public network."
        }
        require(host == site || (site !in allowed && host !in allowed)) {
            "Web redirect from $site to $host leaves or reaches an allowed private host."
        }
        val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(timeoutMillis.toLong()).toInt()
        require(remaining > 0) { "Web fetch timed out." }
        val response = exchange(uri, addresses.first(), maxBytes, remaining)
        if (response.status in setOf(301, 302, 303, 307, 308)) {
            val location = requireNotNull(response.headers["location"]) { "Redirect has no Location." }
            uri = uri.resolve(location)
        } else {
            require(response.status in 200..299) { "Web server returned HTTP ${response.status}." }
            val contentType = response.headers["content-type"]
            val type = contentType?.substringBefore(';')?.trim()?.lowercase()
            require(
                type == null || type.startsWith("text/") || type in setOf("application/xhtml+xml", "application/json"),
            ) {
                "Web fetch accepts only textual responses."
            }
            val text = response.body.toString(charsetOf(contentType))
            val html = type == "text/html" || type == "application/xhtml+xml" ||
                (type == null && text.trimStart().startsWith('<'))
            return FetchedText(uri, text, html)
        }
    }
    error("Too many web redirects.")
}

/** The charset that [contentType] names, else UTF-8. */
private fun charsetOf(contentType: String?): Charset {
    val name = contentType?.split(';')?.drop(1)?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
    return try {
        name?.let(Charset::forName) ?: Charsets.UTF_8
    } catch (e: IllegalArgumentException) {
        Charsets.UTF_8
    }
}

internal fun checkedHost(uri: URI, allowedPrivateHosts: Set<String> = emptySet()): String {
    val host = parsedHost(uri)
    require(host in allowedPrivateHosts || (!host.endsWith(".localhost") && host != "localhost")) {
        "Localhost requires an explicitly allowed private host."
    }
    return host
}

private fun parsedHost(uri: URI): String {
    require(uri.scheme in setOf("http", "https")) { "Web URL must use HTTP or HTTPS." }
    require(uri.userInfo == null) { "Web URL must not contain credentials." }
    val host = requireNotNull(uri.host) { "Web URL requires a host." }.removeSurrounding("[", "]")
        .lowercase(Locale.ROOT).removeSuffix(".")
    require(host.isNotBlank() && !host.endsWith('.') && '%' !in host) { "Invalid web host." }
    require(uri.port == -1 || uri.port in 1..65535) { "Invalid web port." }
    return host
}

internal class WebResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

internal suspend fun <T> webIo(block: ((Socket) -> Unit) -> T): T = suspendCancellableCoroutine { continuation ->
    val active = AtomicReference<Socket?>()
    val worker = Threads.unstarted("alexandrite-web-io") {
        if (!continuation.isActive) return@unstarted
        try {
            val result = block { socket ->
                active.set(socket)
                if (!continuation.isActive) socket.close()
            }
            continuation.resume(result)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
    continuation.invokeOnCancellation {
        runCatching { active.get()?.close() }
        worker.interrupt()
    }
    worker.start()
}

private fun exchange(uri: URI, address: InetAddress, maxBytes: Int, timeout: Int): WebResponse =
    exchangePinned(uri, address, maxBytes, timeout) {}

private fun exchangePinned(
    uri: URI,
    address: InetAddress,
    maxBytes: Int,
    timeout: Int,
    track: (Socket) -> Unit,
): WebResponse {
    val host = parsedHost(uri)
    val secure = uri.scheme == "https"
    val port = uri.port.takeIf { it != -1 } ?: if (secure) 443 else 80
    val deadline = System.nanoTime() + timeout * 1_000_000L
    Socket().use { raw ->
        track(raw)
        raw.connect(InetSocketAddress(address, port), timeout)
        raw.soTimeout = timeout
        val socket = if (secure) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).let {
                (it as SSLSocket).apply {
                    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    startHandshake()
                }
            }
        } else {
            raw
        }
        socket.use {
            val path = (uri.rawPath?.takeIf(String::isNotEmpty) ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "")
            val authority = uri.rawAuthority
            socket.getOutputStream().write(
                (
                    "GET $path HTTP/1.1\r\nHost: $authority\r\nAccept: text/html,text/plain,application/json\r\n" +
                        "Accept-Encoding: identity\r\nConnection: close\r\nUser-Agent: Alexandrite/1\r\n\r\n"
                    )
                    .toByteArray(Charsets.US_ASCII),
            )
            val input = BufferedInputStream(socket.getInputStream())
            return response(input, maxBytes) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                require(remaining > 0) { "Web fetch timed out." }
                socket.soTimeout = remaining
            }
        }
    }
}

internal fun response(input: InputStream, maxBytes: Int, beforeRead: () -> Unit = {}): WebResponse {
    var headerBytes = 0
    fun line(): String = buildString {
        while (true) {
            beforeRead()
            val c = input.read()
            require(c >= 0) { "Truncated HTTP headers." }
            require(++headerBytes <= 32_768) { "HTTP headers too large." }
            if (c == 10) break
            if (c != 13) append(c.toChar())
        }
    }
    val statusLine = line().split(' ')
    require(statusLine.firstOrNull()?.startsWith("HTTP/1.") == true) { "Invalid HTTP response." }
    val status = statusLine.getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status.")
    val headers = linkedMapOf<String, String>()
    while (true) {
        val field = line().takeIf(String::isNotEmpty) ?: break
        val name = field.substringBefore(':').trim().lowercase()
        require(':' in field && (name !in headers || name !in SINGLE_HEADERS)) { "Invalid or duplicate HTTP header." }
        val value = field.substringAfter(':').trim()
        headers[name] = headers[name]?.let { "$it, $value" } ?: value
    }
    if (status !in 200..299) return WebResponse(status, headers, byteArrayOf())
    require(
        headers["content-encoding"].let {
            it == null || it.equals("identity", true)
        },
    ) { "Unsupported content encoding." }
    val length = headers["content-length"]?.let { value ->
        requireNotNull(
            value.toLongOrNull()?.takeIf {
                it in 0..maxBytes.toLong()
            },
        ) { "Web response too large or invalid length." }
    }
    val out = ByteArrayOutputStream()
    fun copy(count: Long?) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining == null || remaining > 0) {
            beforeRead()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining ?: buffer.size.toLong()).toInt())
            if (n < 0) {
                require(remaining == null) { "Truncated HTTP body." }
                break
            }
            require(out.size().toLong() + n <= maxBytes) { "Web response too large." }
            out.write(buffer, 0, n)
            remaining = remaining?.minus(n)
        }
    }
    val transfer = headers["transfer-encoding"]
    if (transfer != null) {
        require(transfer.equals("chunked", true) && length == null) { "Unsupported HTTP transfer encoding." }
        while (true) {
            val count = line().substringBefore(';').trim().toLongOrNull(16)
            require(count != null && count >= 0 && count <= maxBytes - out.size()) {
                "Invalid or oversized HTTP chunk."
            }
            if (count == 0L) break
            copy(count)
            require(line().isEmpty()) { "Invalid HTTP chunk ending." }
        }
    } else {
        copy(length)
    }
    return WebResponse(status, headers, out.toByteArray())
}

/** The headers whose repetition makes a response ambiguous. */
private val SINGLE_HEADERS = setOf("content-length", "transfer-encoding", "content-encoding", "location")

private val WHITESPACE_RUN = Regex("\\s+")
