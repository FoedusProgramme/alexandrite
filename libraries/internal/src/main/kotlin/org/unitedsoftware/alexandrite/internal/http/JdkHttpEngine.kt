package org.unitedsoftware.alexandrite.internal.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.unitedsoftware.alexandrite.internal.portable.JdkOnly
import org.unitedsoftware.alexandrite.sdk.net.HttpBodySource
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineResponse
import org.unitedsoftware.alexandrite.sdk.net.HttpHeaders
import org.unitedsoftware.alexandrite.sdk.net.HttpResponseBody
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.channels.Channel as CoroutineChannel

/** The [HttpEngine] on `java.net.http`, with a client for each connect timeout its requests name. */
@JdkOnly
public class JdkHttpEngine :
    HttpEngine,
    AutoCloseable {
    private val clients = ConcurrentHashMap<Duration, HttpClient>()

    override suspend fun open(request: HttpEngineRequest): HttpEngineResponse {
        val body = BodyChannel()
        val info = CompletableDeferred<HttpResponse.ResponseInfo>()
        val future = try {
            client(request.connectTimeout).sendAsync(jdkRequest(request)) { response ->
                info.complete(response)
                HttpResponse.BodySubscribers.fromSubscriber(body)
            }
        } catch (e: IllegalStateException) {
            throw IOException("The engine is closed.", e)
        }
        body.future = future
        future.whenComplete { _, error ->
            if (error != null) {
                val cause = unwrap(error)
                info.completeExceptionally(cause)
                body.fail(cause)
            }
        }
        val response = try {
            info.await()
        } catch (e: Throwable) {
            body.close()
            throw e
        }
        return HttpEngineResponse(response.statusCode(), HttpHeaders.of(response.headers().map()), body)
    }

    /** Ends every exchange in flight and frees the clients. */
    override fun close() {
        clients.values.forEach { it.shutdownNow() }
        clients.clear()
    }

    private fun client(connectTimeout: Duration): HttpClient = clients.computeIfAbsent(connectTimeout) {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout.toJavaDuration())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    private fun jdkRequest(request: HttpEngineRequest): HttpRequest {
        val body = request.body
        val publisher = if (body == null || body.length == 0L) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream { SourceStream(body.open()) },
                body.length,
            )
        }
        val builder = HttpRequest.newBuilder(URI.create(request.url)).method(request.method, publisher)
        request.headers.toMap().forEach { (name, values) -> values.forEach { builder.header(name, it) } }
        return builder.build()
    }
}

/** Hands the pieces of a response body to one coroutine, one piece of demand at a time. */
@JdkOnly
private class BodyChannel :
    Subscriber<List<ByteBuffer>>,
    HttpResponseBody {
    private val pieces = CoroutineChannel<ByteArray>(CoroutineChannel.UNLIMITED)
    private val subscription = AtomicReference<Subscription?>()
    private val closed = AtomicBoolean()

    /** The exchange, which [close] cancels unless the whole body arrived. */
    @Volatile
    var future: CompletableFuture<*>? = null

    /** Whether the whole body arrived. */
    @Volatile
    private var ended: Boolean = false

    override fun onSubscribe(subscription: Subscription) {
        if (!this.subscription.compareAndSet(null, subscription) || closed.get()) {
            subscription.cancel()
            return
        }
        subscription.request(1)
    }

    override fun onNext(item: List<ByteBuffer>) {
        val bytes = ByteArray(item.sumOf { it.remaining() })
        var offset = 0
        for (buffer in item) {
            val count = buffer.remaining()
            buffer.get(bytes, offset, count)
            offset += count
        }
        if (bytes.isEmpty()) subscription.get()?.request(1) else pieces.trySend(bytes)
    }

    override fun onError(throwable: Throwable) {
        fail(throwable)
    }

    override fun onComplete() {
        ended = true
        pieces.close()
    }

    fun fail(error: Throwable) {
        pieces.close(error as? IOException ?: IOException(error))
    }

    override suspend fun read(): ByteArray? {
        val result = try {
            pieces.receiveCatching()
        } catch (e: CancellationException) {
            close()
            throw e
        }
        result.getOrNull()?.let { bytes ->
            subscription.get()?.request(1)
            return bytes
        }
        result.exceptionOrNull()?.let { throw it }
        return null
    }

    override fun close() {
        // A body read to its end leaves its connection to the client's next request
        if (ended || !closed.compareAndSet(false, true)) return
        subscription.get()?.cancel()
        pieces.close(IOException("The response body is closed."))
        while (pieces.tryReceive().isSuccess) continue
        future?.cancel(true)
    }
}

/** [source] as the stream that `java.net.http` reads a request body from. */
@JdkOnly
private class SourceStream(private val source: HttpBodySource) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        while (true) {
            when (read(one, 0, 1)) {
                -1 -> return -1
                1 -> return one[0].toInt() and 0xff
            }
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else source.read(b, off, len)

    override fun close() {
        source.close()
    }
}

private fun unwrap(error: Throwable): Throwable {
    var current = error
    while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
        current = current.cause!!
    }
    return current
}
