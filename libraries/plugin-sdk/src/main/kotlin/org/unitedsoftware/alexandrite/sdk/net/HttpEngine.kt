package org.unitedsoftware.alexandrite.sdk.net

import org.unitedsoftware.alexandrite.sdk.di.BoundSpi

/**
 * Sends the HTTP requests of Alexandrite and its plugins, bound by one engine plugin at most, else by the runtime's
 * JDK default. The contract, which the testkit's `HTTP_ENGINE_CONTRACT` checks:
 * - it speaks HTTP/1.1, over TLS for `https`, with the platform's trusted certificates and proxy settings;
 * - it never follows a redirect;
 * - it sends the request's headers as given, adding only `host`, `content-length`, `connection` and a default
 *   `user-agent`, and hands the body over undecoded;
 * - it gives up connecting after [HttpEngineRequest.connectTimeout] and sets no other timeout;
 * - [open] suspends until the status and headers arrive, and the caller reads the body as it arrives;
 * - cancelling [open] or a read of the body ends the exchange at once and closes its connection;
 * - it resends a request only when a connection it reused turns out closed.
 */
@BoundSpi
public interface HttpEngine {
    /**
     * Sends [request] and returns its response once the status and headers arrived, for the caller to read and close.
     * Throws an [IllegalArgumentException] for a request it cannot send, any other exception when the exchange fails.
     */
    public suspend fun open(request: HttpEngineRequest): HttpEngineResponse
}

/** The response to an [HttpEngineRequest]: its [status] and [headers] as received, and its [body] to read. */
public class HttpEngineResponse(
    public val status: Int,
    public val headers: HttpHeaders,
    public val body: HttpResponseBody,
) : AutoCloseable {
    init {
        require(status in 100..999) { "An HTTP status has three digits, was $status." }
    }

    override fun close() {
        body.close()
    }

    override fun toString(): String = "HttpEngineResponse($status, $headers)"
}

/** The body of a response, which its reader takes in the pieces the engine receives. */
public interface HttpResponseBody : AutoCloseable {
    /**
     * The next piece of the body, never empty, null at its end. Throws when the exchange fails, and cancelling it ends
     * the exchange as [close] does.
     */
    public suspend fun read(): ByteArray?

    /** Ends the exchange, from any thread and more than once, also while a read suspends, which then ends. */
    override fun close()
}
