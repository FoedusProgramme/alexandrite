package org.unitedsoftware.alexandrite.channel.onebot.transport

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import java.util.concurrent.atomic.AtomicLong

/** Where a connection is in its life, which its instance reports to whoever asks. */
public enum class OneBotLinkState {
    /** Nothing was connected yet. */
    CLOSED,

    /** The transport is reaching its implementation, or waiting for it. */
    CONNECTING,

    /** The connection takes calls and reports the events. */
    OPEN,

    /** The connection is closing, or closed on its own. */
    LOST,
    ;

    /** Whether the connection takes calls right now. */
    public val isOpen: Boolean get() = this == OPEN
}

/**
 * One live connection to an OneBot implementation, whichever transport carries it.
 *
 * The plugin calls [send] and reads [events]; nothing outside this package sees the socket or the library behind
 * either. The events end when the connection closes, so a reader stops waiting.
 */
public interface OneBotConnection : AutoCloseable {
    /** Where the connection is. */
    public val state: StateFlow<OneBotLinkState>

    /** The events of the implementation, in the order it reported them. */
    public val events: Flow<OneBotEvent>

    /** Sends one call and returns the answer body, which the caller decodes into its own type. */
    public suspend fun send(action: String, params: JsonObject): JsonObject

    /** Ends the connection and returns once it takes no call anymore. */
    override fun close()
}

/**
 * What every transport of this module shares: the events it reports, the state it reports them under and the close
 * that ends both.
 *
 * A full queue drops its oldest event so the thread the implementation reports on is never blocked, and what it
 * dropped is worked out from what it took less what was read and what still waits, so that a connection that cannot
 * keep up is visible.
 */
internal abstract class AbstractOneBotConnection(private val eventCapacity: Int) : OneBotConnection {
    private val dropped = AtomicLong()
    private val queue = Channel<OneBotEvent>(eventCapacity, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }
    private val mutableState = MutableStateFlow(OneBotLinkState.CLOSED)

    /**
     * How many events this connection dropped because nothing read them fast enough.
     *
     * The queue answers that it took an event even when it displaced one, so the count cannot be read from its answer.
     * It is the events it never delivered, which it names as they are dropped, and it only grows.
     */
    internal val droppedEvents: Long get() = dropped.get()

    override val state: StateFlow<OneBotLinkState> = mutableState.asStateFlow()

    override val events: Flow<OneBotEvent> = queue.receiveAsFlow()

    /** The last failure of the connection, null while it is healthy. */
    @Volatile
    protected var failure: Throwable? = null

    @Volatile
    private var closed = false

    /** Reports [event], dropping the oldest when nothing reads them fast enough. */
    protected fun report(event: OneBotEvent): Boolean = queue.trySend(event).isSuccess

    /** Moves the connection to [state]. */
    protected fun moveTo(state: OneBotLinkState) {
        mutableState.value = state
    }

    /** Records [error] and moves the connection to [OneBotLinkState.LOST]. */
    protected fun lost(error: Throwable) {
        failure = error
        moveTo(OneBotLinkState.LOST)
    }

    /** Ends the events once, so that a reader stops after the ones already reported. */
    protected fun endEvents() {
        queue.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            stop()
        } finally {
            moveTo(OneBotLinkState.CLOSED)
            endEvents()
        }
    }

    /** Ends whatever the transport opened, once [close] was called for the first time. */
    protected abstract fun stop()
}

/** The key a call is answered under, which an implementation returns as it is. */
internal class Echo {
    private val counter = java.util.concurrent.atomic.AtomicLong()

    /** The `echo` of the next call. */
    fun next(): String = "alexandrite-${counter.incrementAndGet()}"
}
