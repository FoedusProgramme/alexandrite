package org.unitedsoftware.alexandrite.engine.okhttp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
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
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/** The [HttpEngine] on OkHttp, with one connection pool and a client for each connect timeout its requests name. */
@Singleton
@Binds(HttpEngine::class)
internal class OkHttpEngine :
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
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .addNetworkInterceptor(::withoutAddedIdentity)
        .build()

    private val clients = ConcurrentHashMap<Duration, OkHttpClient>()

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
        return HttpEngineResponse(response.code, HttpHeaders.of(response.headers.toMultimap()), OkBody(call, response))
    }

    /** Ends every exchange in flight and frees the threads and connections. */
    override fun close() {
        dispatcher.cancelAll()
        executor.shutdown()
        base.connectionPool.evictAll()
    }

    private fun client(connectTimeout: Duration): OkHttpClient = clients.computeIfAbsent(connectTimeout) {
        base.newBuilder().connectTimeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
    }

    private fun okRequest(request: HttpEngineRequest): Request {
        val url = requireNotNull(request.url.toHttpUrlOrNull()) { "OkHttp cannot send to the URL." }
        val headers = Headers.Builder()
        request.headers.toMap().forEach { (name, values) -> values.forEach { headers.addUnsafeNonAscii(name, it) } }
        val builder = Request.Builder().url(url)
        // Named so that OkHttp neither asks for gzip nor decodes it, and taken off again on the wire
        if (request.headers[ACCEPT_ENCODING] == null) {
            headers.add(ACCEPT_ENCODING, IDENTITY)
            builder.tag(AddedIdentity::class.java, AddedIdentity)
        }
        val body = request.body?.let(::SourceBody)
            ?: ByteArray(0).toRequestBody().takeIf { request.method in BODY_METHODS }
        return builder.headers(headers.build()).method(request.method, body).build()
    }

    private fun withoutAddedIdentity(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(AddedIdentity::class.java) == null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().removeHeader(ACCEPT_ENCODING).build())
    }

    private object AddedIdentity

    private companion object {
        const val ACCEPT_ENCODING = "accept-encoding"
        const val IDENTITY = "identity"

        /** The methods OkHttp sends only with a body. */
        val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}

/** [body], read from a source the engine opens each time OkHttp writes it. */
private class SourceBody(private val body: HttpRequestBody) : RequestBody() {
    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = body.length

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

/** The body of [response], each read on an IO thread and the [call] cancelled when the read is. */
private class OkBody(private val call: Call, private val response: Response) : HttpResponseBody {
    private val source = response.body.source()

    @Volatile
    private var ended = false

    override suspend fun read(): ByteArray? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        IO.execute {
            continuation.resumeWith(
                runCatching {
                    val buffer = Buffer()
                    val count = source.read(buffer, PIECE_BYTES.toLong())
                    if (count == -1L) null.also { ended = true } else buffer.readByteArray()
                },
            )
        }
    }

    override fun close() {
        // A body read to its end leaves its connection to the pool
        if (!ended) call.cancel()
        response.close()
    }
}

private val IO = Dispatchers.IO.asExecutor()

private const val PIECE_BYTES = 16 * 1024
