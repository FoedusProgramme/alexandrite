package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.tui.Console
import org.unitedsoftware.alexandrite.app.tui.JLineConsole
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.toJavaDuration
import java.io.Console as JavaConsole
import kotlin.time.Duration as KotlinDuration

/** The exit code of a program and what it printed. */
internal class Executed(val code: Int, val output: String)

/** The status of an HTTP answer and its body. */
internal class Fetched(val status: Int, val body: String)

/** What the commands reach of the machine they run on. */
internal interface Machine {
    /** Whether a person answers at a terminal. */
    val interactive: Boolean

    /** Whether the output goes to a terminal that shows glyphs. */
    val styled: Boolean get() = false

    /** The Java that runs the command. */
    val java: Runtime.Version

    /** The line typed after [prompt], null at the end of the input. */
    fun ask(prompt: String): String?

    /** The line typed after [prompt] without echo, null at the end of the input. */
    fun askSecret(prompt: String): String?

    /** Runs [command] to its end, or throws [IOException] when it cannot start. */
    fun execute(command: List<String>): Executed

    /** Runs [command] on this terminal, or throws [IOException] when it cannot start. */
    fun attach(command: List<String>): Int

    /** Answers a GET of [url], or throws [IOException] when it is unreachable. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): Fetched

    /** Answers a POST of the JSON [body] to [url], or throws [IOException] when it is unreachable. */
    fun post(url: String, body: String): Fetched

    /** Answers a DELETE of [url] with [headers], or throws [IOException] when it is unreachable. */
    fun delete(url: String, headers: Map<String, String> = emptyMap()): Fetched

    /** Where a GET of [url] redirects to, null when it does not, or throws [IOException] when it fails. */
    fun redirect(url: String): String?

    fun sleep(duration: KotlinDuration)

    /** Whether a follow of a log goes on. */
    fun following(): Boolean = true

    /** The terminal to draw prompts on, null when there is none. */
    fun console(): Console? = null
}

/** The machine the app runs on. */
internal object LocalMachine : Machine {
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** A client for quick checks that follows no redirect. */
    private val direct: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    }

    private val input by lazy { BufferedReader(InputStreamReader(System.`in`)) }

    override val interactive: Boolean get() = System.console()?.let(::isTerminal) ?: false

    override val styled: Boolean get() = interactive && System.out.charset() == Charsets.UTF_8

    override fun console(): Console? = if (interactive) JLineConsole.open() else null

    /** Whether [console] is a terminal, which Java 22 and later tell apart from redirected streams. */
    private fun isTerminal(console: JavaConsole): Boolean = try {
        JavaConsole::class.java.getMethod("isTerminal").invoke(console) as Boolean
    } catch (e: NoSuchMethodException) {
        true
    }

    override val java: Runtime.Version get() = Runtime.version()

    override fun ask(prompt: String): String? {
        System.console()?.let { return it.readLine("%s", prompt) }
        System.err.print(prompt)
        return input.readLine()
    }

    override fun askSecret(prompt: String): String? {
        System.console()?.let { console -> return console.readPassword("%s", prompt)?.let(::String) }
        return input.readLine()
    }

    override fun execute(command: List<String>): Executed {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        return Executed(process.waitFor(), output)
    }

    override fun attach(command: List<String>): Int = ProcessBuilder(command).inheritIO().start().waitFor()

    override fun get(url: String, headers: Map<String, String>): Fetched {
        val request = try {
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET()
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        headers.forEach { (name, value) -> request.header(name, value) }
        return send(request.build())
    }

    override fun post(url: String, body: String): Fetched {
        val request = try {
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(POST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        return send(request.build())
    }

    override fun delete(url: String, headers: Map<String, String>): Fetched {
        val request = try {
            val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).DELETE()
            headers.forEach { (name, value) -> builder.header(name, value) }
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        return send(request)
    }

    override fun redirect(url: String): String? {
        val answer = try {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build()
            direct.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        return when (answer.statusCode()) {
            in 300..399 -> answer.headers().firstValue("location").orElse(null)?.let {
                try {
                    "${answer.uri().resolve(it)}"
                } catch (e: IllegalArgumentException) {
                    throw IOException(e.message, e)
                }
            }

            in 200..299, 404 -> null

            else -> throw IOException("HTTP ${answer.statusCode()}")
        }
    }

    private fun send(request: HttpRequest): Fetched {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        return Fetched(response.statusCode(), response.body())
    }

    /** Long enough for a long poll of the Bot API. */
    private const val POST_TIMEOUT_SECONDS = 40L

    override fun sleep(duration: KotlinDuration) {
        Thread.sleep(duration.toJavaDuration())
    }
}
