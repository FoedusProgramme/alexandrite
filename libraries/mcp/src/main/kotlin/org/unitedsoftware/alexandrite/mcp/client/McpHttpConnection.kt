package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.http.common.HttpCall
import org.unitedsoftware.alexandrite.http.common.HttpException
import org.unitedsoftware.alexandrite.http.common.HttpFailureKind
import org.unitedsoftware.alexandrite.http.common.HttpResponseStream
import org.unitedsoftware.alexandrite.http.common.HttpTransport
import org.unitedsoftware.alexandrite.http.common.SseEvent
import org.unitedsoftware.alexandrite.http.common.SseParser
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A server behind one Streamable HTTP endpoint at [url], sent [headers] with every message and the token of [bearer]
 * when it has one.
 *
 * Speaks both the stateless revision and the session-based legacy ones, as [protocolVersion] says.
 */
internal class McpHttpConnection(
    private val url: URI,
    private val headers: Map<String, String>,
    private val transport: HttpTransport,
    private val bearer: suspend () -> String? = { null },
) : McpConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong()
    private val incoming = ClientSide.notifications()
    private val end = CompletableDeferred<McpException>()
    private val closed = AtomicBoolean()
    private val secrets = headers.values.toList()

    @Volatile
    private var sessionId: String? = null

    override val notifications: SharedFlow<JsonRpcMessage.Notification> = incoming

    override val ended: Deferred<McpException> = end

    @Volatile
    override var protocolVersion: String? = null

    override val mirrorsParams: Boolean = true

    override val listensLegacy: Boolean = true

    private val legacy: Boolean get() = McpVersions.isLegacy(protocolVersion)

    override suspend fun request(
        method: String,
        params: JsonObject,
        meta: JsonObject?,
        headers: Map<String, String>,
        timeout: Duration?,
    ): JsonObject {
        if (closed.get()) throw McpConnectionException("The connection was closed.")
        val id = ids.incrementAndGet()
        val body = JsonRpc.withMeta(params, meta)
        val name = if (method in McpHeaders.NAMED_METHODS) {
            ((body["name"] ?: body["uri"]) as? JsonPrimitive)?.content
        } else {
            null
        }
        val protocolHeaders = buildMap {
            put(McpHeaders.METHOD, McpHeaders.encode(method))
            name?.let { put(McpHeaders.NAME, McpHeaders.encode(it)) }
            putAll(headers)
        }
        val session = sessionId
        val post = post(JsonRpc.request(id, method, body), protocolHeaders, timeout)
        val authorized = post.authorized
        var abandoned = true
        try {
            val response = try {
                transport.open(post.call) { stream ->
                    if (method == INITIALIZE) {
                        sessionId = stream.headers[McpHeaders.SESSION_ID]
                    }
                    answer(stream, id, method)
                }
            } catch (e: HttpException) {
                if (e.status != null) abandoned = false
                throw failure(e, method, timeout, session, authorized)
            }
            abandoned = false
            response.error?.let { throw McpRpcException(it.code, it.message, it.data) }
            return response.result ?: JsonObject(emptyMap())
        } catch (e: McpConnectionException) {
            abandoned = false
            throw e
        } finally {
            if (abandoned && legacy && !closed.get()) {
                val reason = "The client abandoned the request."
                scope.launch {
                    try {
                        notify(ClientSide.CANCELLED, ClientSide.cancelParams(id, reason))
                    } catch (e: McpException) {
                        // The cancellation is advisory.
                    }
                }
            }
        }
    }

    override suspend fun notify(method: String, params: JsonObject) {
        send(JsonRpc.notification(method, params), mapOf(McpHeaders.METHOD to McpHeaders.encode(method)), method)
    }

    override suspend fun listenLegacy(): Boolean {
        val session = sessionId
        val base = base(EVENT_STREAM)
        val call = HttpCall("GET", url, base.headers, secrets = base.secrets)
        return try {
            transport.open(call) { stream ->
                if (stream.mediaType != EVENT_STREAM) return@open false
                read(stream, id = null)
                true
            }
        } catch (e: HttpException) {
            when {
                e.status == METHOD_NOT_ALLOWED -> false
                e.kind == HttpFailureKind.TIMEOUT && e.afterResponse -> true
                else -> throw failure(e, "the GET stream", null, session, base.authorized)
            }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val session = sessionId
        if (session != null && legacy) {
            withContext(NonCancellable) {
                try {
                    val base = base(null)
                    transport.open(
                        HttpCall("DELETE", url, base.headers, secrets = base.secrets, firstByte = CLOSE_WAIT),
                    ) {
                    }
                } catch (e: HttpException) {
                    // The session ends with the server anyway.
                } catch (e: McpException) {
                    // The session ends with the server anyway.
                }
            }
        }
        scope.cancel()
        end.complete(McpConnectionException("The connection was closed."))
    }

    private suspend fun post(body: String, protocolHeaders: Map<String, String>, timeout: Duration?): Post {
        val base = base("$JSON, $EVENT_STREAM")
        val call = HttpCall(
            "POST",
            url,
            base.headers + (CONTENT_TYPE to JSON) + protocolHeaders,
            body,
            base.secrets,
            firstByte = timeout,
        )
        return Post(call, base.authorized)
    }

    private class Post(val call: HttpCall, val authorized: Boolean)

    private class Base(val headers: Map<String, String>, val secrets: List<String>, val authorized: Boolean)

    private suspend fun base(accept: String?): Base {
        val token = bearer()
        val reserved = RESERVED.map { it.lowercase() }.toSet() + if (token == null) emptySet() else setOf(AUTHORIZATION)
        val merged = buildMap {
            headers.filterKeys { it.lowercase() !in reserved }.forEach(::put)
            token?.let { put(AUTHORIZATION_HEADER, "Bearer $it") }
            accept?.let { put(ACCEPT, it) }
            protocolVersion?.let { put(McpHeaders.PROTOCOL_VERSION, it) }
            sessionId?.let { put(McpHeaders.SESSION_ID, it) }
        }
        return Base(merged, if (token == null) secrets else secrets + token, token != null)
    }

    /** POSTs a message that gets no response. */
    private suspend fun send(message: String, protocolHeaders: Map<String, String>, what: String) {
        if (closed.get()) throw McpConnectionException("The connection was closed.")
        val session = sessionId
        val post = post(message, protocolHeaders, null)
        try {
            transport.open(post.call) {}
        } catch (e: HttpException) {
            throw failure(e, what, null, session, post.authorized)
        }
    }

    /** The response to the request [id] that [stream] carries. */
    private suspend fun answer(stream: HttpResponseStream, id: Long, method: String): JsonRpcMessage.Response =
        when (stream.mediaType) {
            JSON -> {
                val message = JsonRpc.parse(stream.text(HttpTransport.DEFAULT_LIMIT))
                (message as? JsonRpcMessage.Response)?.takeIf { idKey(it.id) == id.toString() }
                    ?: throw McpProtocolException("The server answered $method with no response to it.")
            }

            EVENT_STREAM -> {
                val outcome = read(stream, id)
                outcome.response ?: resume(outcome, id, method)
            }

            else -> throw McpProtocolException("The server answered $method with the content type ${stream.mediaType}.")
        }

    /** Picks the response to [id] up again through the legacy GET stream, as long as the server lets it. */
    private suspend fun resume(outcome: StreamOutcome, id: Long, method: String): JsonRpcMessage.Response {
        var last = outcome
        repeat(RESUME_ATTEMPTS) {
            val eventId = last.lastEventId
            if (!legacy || eventId == null) {
                throw McpConnectionException("The server ended the response stream of $method without an answer.")
            }
            delay(last.retry ?: DEFAULT_RETRY)
            val base = base(EVENT_STREAM)
            val call = HttpCall("GET", url, base.headers + (LAST_EVENT_ID to eventId), secrets = base.secrets)
            last = try {
                transport.open(call) { read(it, id) }
            } catch (e: HttpException) {
                throw failure(e, method, null, sessionId, base.authorized)
            }
            last.response?.let { return it }
        }
        throw McpConnectionException("The server ended the response stream of $method without an answer.")
    }

    private class StreamOutcome(val response: JsonRpcMessage.Response?, val lastEventId: String?, val retry: Duration?)

    /** Reads [stream] until the response to [id] or its end, handing on what else it carries. */
    private suspend fun read(stream: HttpResponseStream, id: Long?): StreamOutcome {
        val parser = SseParser()
        var lastEventId: String? = null
        fun outcome(response: JsonRpcMessage.Response?) =
            StreamOutcome(response, lastEventId, parser.retry?.milliseconds)
        while (true) {
            val bytes = stream.read() ?: break
            for (event in parser.feed(bytes)) {
                event.id?.let { lastEventId = it }
                dispatch(event, id)?.let { return outcome(it) }
            }
        }
        parser.finish()?.let { event ->
            event.id?.let { lastEventId = it }
            dispatch(event, id)?.let { return outcome(it) }
        }
        return outcome(null)
    }

    private fun dispatch(event: SseEvent, id: Long?): JsonRpcMessage.Response? {
        if (event.type != "message" || event.data.isBlank()) return null
        when (val message = JsonRpc.parse(event.data)) {
            is JsonRpcMessage.Response -> if (id != null && idKey(message.id) == id.toString()) return message
            is JsonRpcMessage.Notification -> incoming.tryEmit(message)
            is JsonRpcMessage.Request -> scope.launch { reply(message) }
        }
        return null
    }

    private suspend fun reply(request: JsonRpcMessage.Request) {
        try {
            send(ClientSide.reply(request), emptyMap(), "the answer to ${request.method}")
        } catch (e: McpException) {
            // The server asks again or gives up.
        } catch (e: CancellationException) {
            throw e
        }
    }

    /** What [e] means for the call [what], which carried [session] and a bearer token when [authorized]. */
    private fun failure(
        e: HttpException,
        what: String,
        timeout: Duration?,
        session: String?,
        authorized: Boolean,
    ): McpException {
        val status = e.status
        val challenge = e.headers?.all(WWW_AUTHENTICATE)?.joinToString(", ")?.ifEmpty { null }
        return when {
            status == UNAUTHORIZED || (status == FORBIDDEN && challenge != null) ->
                McpUnauthorized(challenge, "The server refused $what with HTTP $status.", status, authorized)

            status == NOT_FOUND && session != null && what != INITIALIZE -> {
                if (sessionId == session) sessionId = null
                McpSessionExpired("The server no longer knows the session.")
            }

            status != null -> JsonRpc.errorIn(e.body)?.let { McpRpcException(it.code, it.message, it.data, status) }
                ?: McpHttpStatusException(status, e.message ?: "HTTP $status", e)

            e.kind == HttpFailureKind.TIMEOUT && !e.afterResponse && timeout != null ->
                McpTimeoutException(what, timeout)

            else -> McpConnectionException(e.message ?: "The HTTP call failed.", e)
        }
    }

    companion object {
        const val INITIALIZE: String = "initialize"

        private const val JSON = "application/json"
        private const val EVENT_STREAM = "text/event-stream"
        private const val ACCEPT = "Accept"
        private const val CONTENT_TYPE = "Content-Type"
        private const val LAST_EVENT_ID = "Last-Event-ID"
        private const val WWW_AUTHENTICATE = "www-authenticate"
        private const val AUTHORIZATION = "authorization"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val RESUME_ATTEMPTS = 5
        private val DEFAULT_RETRY = 1.seconds
        private val CLOSE_WAIT = 5.seconds

        /** Headers the connection sets itself, which configured ones never replace. */
        private val RESERVED = listOf(
            ACCEPT,
            CONTENT_TYPE,
            LAST_EVENT_ID,
            McpHeaders.PROTOCOL_VERSION,
            McpHeaders.SESSION_ID,
            McpHeaders.METHOD,
            McpHeaders.NAME,
        )
    }
}
