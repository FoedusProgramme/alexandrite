package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.internal.portable.ProcessIds
import org.unitedsoftware.alexandrite.internal.process.ProcessTrees
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How to start a stdio server. */
internal class StdioLaunch(
    val command: String,
    val args: List<String>,
    /** The whole environment of the process. */
    val environment: Map<String, String>,
    val cwd: Path? = null,
    /** Starts the process that the builder describes. */
    val start: (ProcessBuilder) -> Process = ProcessBuilder::start,
    /** Closed when the connection ends. */
    val held: AutoCloseable? = null,
    /** Waits for the process and ends it. */
    val trees: ProcessTrees = ProcessTrees.system,
)

/** A server run as a child process that speaks newline-delimited JSON-RPC on its standard streams. */
internal class McpStdioConnection private constructor(
    private val process: Process,
    private val onStderr: (String) -> Unit,
    private val onInvalid: (String) -> Unit,
    private val stopGrace: Duration,
    private val held: AutoCloseable?,
    private val trees: ProcessTrees,
) : McpConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stdin: OutputStream = process.outputStream
    private val writeLock = Mutex()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonRpcMessage.Response>>()
    private val incoming = ClientSide.notifications()
    private val end = CompletableDeferred<McpException>()

    @Volatile
    private var endReason: McpException? = null
    private val closing = AtomicBoolean()

    override val notifications: SharedFlow<JsonRpcMessage.Notification> = incoming

    override val ended: Deferred<McpException> = end

    override var protocolVersion: String? = null

    override val mirrorsParams: Boolean = false

    override val listensLegacy: Boolean = false

    private fun startReaders() {
        val named = ProcessIds.of(process)?.let { " $it" }.orEmpty()
        thread(isDaemon = true, name = "mcp stdout$named") {
            readLines(process.inputStream, MESSAGE_LIMIT, onLong = { onInvalid("A message of more than $it bytes.") }) {
                receive(it)
            }
            val code = try {
                process.waitFor(EXIT_WAIT.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (process.isAlive) null else process.exitValue()
            } catch (e: InterruptedException) {
                null
            }
            finish(
                if (closing.get()) {
                    McpConnectionException("The connection was closed.")
                } else {
                    McpConnectionException(
                        if (code == null) "The server closed its output." else "The server exited with code $code.",
                    )
                },
            )
        }
        thread(isDaemon = true, name = "mcp stderr$named") {
            readLines(process.errorStream, STDERR_LINE, onLong = {}) { onStderr(it.take(STDERR_LINE)) }
        }
    }

    override suspend fun request(
        method: String,
        params: JsonObject,
        meta: JsonObject?,
        headers: Map<String, String>,
        timeout: Duration?,
    ): JsonObject {
        endReason?.let { throw it }
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonRpcMessage.Response>()
        pending[id.toString()] = answer
        var reason = "The client cancelled the request."
        try {
            write(JsonRpc.request(id, method, JsonRpc.withMeta(params, meta)))
            val response = if (timeout == null) {
                answer.await()
            } else {
                withTimeoutOrNull(timeout) { answer.await() } ?: run {
                    reason = "The request timed out."
                    throw McpTimeoutException(method, timeout)
                }
            }
            response.error?.let { throw McpRpcException(it.code, it.message, it.data) }
            return response.result ?: JsonObject(emptyMap())
        } finally {
            pending.remove(id.toString())
            if (!answer.isCompleted && endReason == null) {
                withContext(NonCancellable) {
                    try {
                        notify(ClientSide.CANCELLED, ClientSide.cancelParams(id, reason))
                    } catch (e: McpException) {
                        // The server is gone, so the request is too.
                    }
                }
            }
        }
    }

    override suspend fun notify(method: String, params: JsonObject) {
        write(JsonRpc.notification(method, params))
    }

    override suspend fun listenLegacy(): Boolean = false

    override suspend fun close() {
        if (!closing.compareAndSet(false, true)) {
            end.await()
            return
        }
        withContext(NonCancellable + Dispatchers.IO) {
            val family = trees.descendants(process)
            try {
                stdin.close()
            } catch (e: IOException) {
                // Already closed.
            }
            val exited = withTimeoutOrNull(stopGrace) { trees.awaitExit(process) } != null
            if (!exited) trees.destroy(process, stopGrace)
            family.stop(stopGrace)
            finish(McpConnectionException("The connection was closed."))
            scope.cancel()
        }
    }

    private suspend fun write(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        try {
            writeLock.withLock {
                if (closing.get()) throw IOException("closed")
                withContext(Dispatchers.IO) {
                    stdin.write(bytes)
                    stdin.flush()
                }
            }
        } catch (e: IOException) {
            throw endReason ?: McpConnectionException("Cannot write to the server: ${e.message}", e)
        }
    }

    private fun receive(line: String) {
        if (line.isBlank()) return
        val message = try {
            JsonRpc.parse(line)
        } catch (e: McpProtocolException) {
            onInvalid(line.take(INVALID_QUOTE))
            return
        }
        when (message) {
            is JsonRpcMessage.Response -> pending[idKey(message.id)]?.complete(message)
            is JsonRpcMessage.Notification -> incoming.tryEmit(message)
            is JsonRpcMessage.Request -> scope.launch { answer(message) }
        }
    }

    private suspend fun answer(request: JsonRpcMessage.Request) {
        try {
            write(ClientSide.reply(request))
        } catch (e: McpException) {
            // The server is gone.
        }
    }

    private fun finish(reason: McpException) {
        synchronized(end) {
            if (endReason != null) return
            endReason = reason
        }
        end.complete(reason)
        for (answer in pending.values) answer.completeExceptionally(reason)
        held?.close()
    }

    companion object {
        /** The most bytes of one message. */
        const val MESSAGE_LIMIT: Int = 32 * 1024 * 1024

        /** The most characters of one stderr line handed on. */
        const val STDERR_LINE: Int = 4096

        private const val INVALID_QUOTE = 200
        private val EXIT_WAIT = 1.seconds
        private val DEFAULT_GRACE = 2.seconds

        /**
         * Starts [launch] and reads its output; [onStderr] gets each line of its standard error and [onInvalid] each
         * output line that is no JSON-RPC message.
         */
        suspend fun start(
            launch: StdioLaunch,
            onStderr: (String) -> Unit = {},
            onInvalid: (String) -> Unit = {},
            stopGrace: Duration = DEFAULT_GRACE,
        ): McpStdioConnection {
            LaunchCheck.refusal(launch.command, launch.args)?.let { refusal ->
                launch.held?.close()
                throw McpConnectionException(refusal)
            }
            val builder = ProcessBuilder(listOf(launch.command) + launch.args)
            builder.environment().apply {
                clear()
                putAll(launch.environment)
            }
            launch.cwd?.let { builder.directory(it.toFile()) }
            val process = try {
                withContext(Dispatchers.IO + NonCancellable) { launch.start(builder) }
            } catch (e: IOException) {
                launch.held?.close()
                throw McpConnectionException("Cannot start ${launch.command}: ${e.message}", e)
            }
            return McpStdioConnection(process, onStderr, onInvalid, stopGrace, launch.held, launch.trees)
                .also { it.startReaders() }
        }
    }
}

/** Hands each line of [stream] to [line], a longer line than [limit] bytes to [onLong] instead, until its end. */
internal fun readLines(stream: InputStream, limit: Int, onLong: (Int) -> Unit, line: (String) -> Unit) {
    val buffer = ByteArray(BUFFER)
    val current = ByteArrayOutputStream()
    var skipping = false
    fun emit() {
        if (skipping) {
            skipping = false
        } else {
            var bytes = current.toByteArray()
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
            line(bytes.toString(Charsets.UTF_8))
        }
        current.reset()
    }
    try {
        stream.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                var start = 0
                for (index in 0 until count) {
                    if (buffer[index] != '\n'.code.toByte()) continue
                    if (!skipping) current.write(buffer, start, index - start)
                    emit()
                    start = index + 1
                }
                if (!skipping) {
                    current.write(buffer, start, count - start)
                    if (current.size() > limit) {
                        onLong(limit)
                        skipping = true
                        current.reset()
                    }
                }
            }
        }
        if (current.size() > 0 && !skipping) emit()
    } catch (e: IOException) {
        // The stream closed under the reader.
    }
}

private const val BUFFER = 64 * 1024
