package org.unitedsoftware.alexandrite.engine.okhttp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import org.unitedsoftware.alexandrite.internal.portable.Threads
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineResponse
import org.unitedsoftware.alexandrite.sdk.net.HttpHeaders
import org.unitedsoftware.alexandrite.sdk.net.HttpRequestBody
import org.unitedsoftware.alexandrite.sdk.net.HttpResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/** The [HttpEngine] on OkHttp, with one connection pool and a client for each connect timeout its requests name. */
@Singleton
@Binds(HttpEngine::class)
public class OkHttpEngine :
    HttpEngine,
    AutoCloseable {
    private val executor = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue()) {
        Threads.unstarted("alexandrite-okhttp", it)
    }

    // Unlimited, so that long-lived streams to one host never queue
    private val dispatcher = Dispatcher(executor).apply {
        maxRequests = Int.MAX_VALUE
        maxRequestsPerHost = Int.MAX_VALUE
    }

    private val base = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(::withRecoveryByMethod)
        .addNetworkInterceptor(::withoutAddedIdentity)
        .addNetworkInterceptor(::withRetryAfterHeld)
        .build()

    private val clients = ConcurrentHashMap<Duration, OkHttpClient>()

    private val bodies = ConcurrentHashMap.newKeySet<OkBody>()

    override suspend fun open(request: HttpEngineRequest): HttpEngineResponse {
        val call = client(request.connectTimeout).newCall(okRequest(request))
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, unused, _ -> unused.close() }
                    }
                },
            )
        }
        val headers = response.headers.toMultimap().let { fields ->
            if (response.code !in HELD_STATUSES) {
                fields
            } else {
                (fields - RETRY_AFTER).mapKeys { if (it.key == HELD) RETRY_AFTER else it.key }
            }
        }
        val body = OkBody(call, response, executor, bodies).also(bodies::add)
        return HttpEngineResponse(response.code, HttpHeaders.of(headers), body)
    }

    /** Ends every exchange in flight and frees the threads and connections. */
    override fun close() {
        dispatcher.cancelAll()
        bodies.forEach { it.close() }
        executor.shutdown()
        base.connectionPool.evictAll()
    }

    private fun client(connectTimeout: Duration): OkHttpClient = clients.computeIfAbsent(connectTimeout) {
        base.newBuilder().connectTimeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
    }

    private fun okRequest(request: HttpEngineRequest): Request {
        val url = requireNotNull(request.url.toHttpUrlOrNull()) { "OkHttp cannot send to the URL." }
        val headers = Headers.Builder()
        request.headers.toMap().forEach { (name, values) -> values.forEach { headers.add(name, it) } }
        val builder = Request.Builder().url(url)
        // Keeps OkHttp from asking for gzip and decoding it
        if (request.headers[ACCEPT_ENCODING] == null) {
            headers.add(ACCEPT_ENCODING, IDENTITY)
            builder.tag(AddedIdentity::class.java, AddedIdentity)
        }
        val body = (request.body ?: EMPTY_BODY.takeIf { request.method in BODY_METHODS })?.let(::SourceBody)
        return builder.headers(headers.build()).method(request.method, body).build()
    }

    private fun withoutAddedIdentity(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(AddedIdentity::class.java) == null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().removeHeader(ACCEPT_ENCODING).build())
    }

    /** [chain]'s response, with OkHttp's recovery from a failed connection on for a `GET` or `HEAD` only. */
    private fun withRecoveryByMethod(chain: Interceptor.Chain): Response =
        chain.withRetryOnConnectionFailure(chain.request().method in RESENT_METHODS).proceed(chain.request())

    /** [chain]'s response with a 408's or 503's `retry-after` under another name, keeping OkHttp from resending. */
    private fun withRetryAfterHeld(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code !in HELD_STATUSES) return response
        val headers = response.headers.newBuilder().removeAll(RETRY_AFTER)
        response.headers(RETRY_AFTER).forEach { headers.addUnsafeNonAscii(HELD, it) }
        // A wait, which keeps OkHttp from resending a 408
        if (response.code == CLIENT_TIMEOUT) headers.add(RETRY_AFTER, "1")
        return response.newBuilder().headers(headers.build()).build()
    }

    private object AddedIdentity

    private companion object {
        const val ACCEPT_ENCODING = "accept-encoding"
        const val IDENTITY = "identity"
        const val RETRY_AFTER = "retry-after"
        const val HELD = "x-alexandrite-held-retry-after"
        const val CLIENT_TIMEOUT = 408
        const val UNAVAILABLE = 503

        val HELD_STATUSES = setOf(CLIENT_TIMEOUT, UNAVAILABLE)

        val RESENT_METHODS = setOf("GET", "HEAD")

        val EMPTY_BODY = HttpRequestBody.of(ByteArray(0))

        /** The methods OkHttp sends only with a body. */
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}

private class SourceBody(private val body: HttpRequestBody) : RequestBody() {
    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = body.length

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        body.open().use { source ->
            val buffer = ByteArray(PIECE_BYTES)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                sink.write(buffer, 0, count)
            }
        }
    }
}

/** The body of [response], each read run on [executor] and the [call] cancelled when the read is. */
private class OkBody(
    private val call: Call,
    private val response: Response,
    private val executor: Executor,
    private val openBodies: MutableSet<OkBody>,
) : HttpResponseBody {
    private val source = response.body.source()

    private val state = AtomicInteger(IDLE)

    @Volatile
    private var ended = false

    override suspend fun read(): ByteArray? = suspendCancellableCoroutine { continuation ->
        if (!state.compareAndSet(IDLE, READING)) {
            continuation.resumeWithException(closedFailure())
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { close() }
        try {
            executor.execute {
                val result = runCatching {
                    val buffer = Buffer()
                    val count = source.read(buffer, PIECE_BYTES.toLong())
                    if (count == -1L) null.also { ended = true } else buffer.readByteArray()
                }
                continuation.resumeWith(if (finishRead()) result else Result.failure(closedFailure()))
            }
        } catch (e: RejectedExecutionException) {
            finishRead()
            continuation.resumeWithException(IOException("The engine is closed.", e))
        }
    }

    override fun close() {
        while (true) {
            when (state.get()) {
                IDLE -> if (state.compareAndSet(IDLE, CLOSED)) {
                    // A body read to its end leaves its connection to the pool
                    if (!ended) call.cancel()
                    response.close()
                    openBodies -= this
                    return
                }

                READING -> if (state.compareAndSet(READING, CLOSING)) {
                    call.cancel()
                    return
                }

                else -> return
            }
        }
    }

    /** Whether the body is still open after a read, closing the response when [close] came during it. */
    private fun finishRead(): Boolean {
        if (state.compareAndSet(READING, IDLE)) return true
        state.set(CLOSED)
        response.close()
        openBodies -= this
        return false
    }

    private fun closedFailure(): IOException = IOException("The response body is closed.")

    private companion object {
        const val IDLE = 0
        const val READING = 1

        /** Closed by [close] while a read runs, which closes the response once it returns. */
        const val CLOSING = 2
        const val CLOSED = 3
    }
}

private const val PIECE_BYTES = 16 * 1024
