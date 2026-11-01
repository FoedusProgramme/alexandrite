package org.unitedsoftware.alexandrite.runtime.lifecycle

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.portable.JdkOnly
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.Termination
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import sun.misc.Signal
import sun.misc.SignalHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** How long a shutdown hook waits for a stop beyond the shutdown grace. */
private val SHUTDOWN_HOOK_MARGIN = 5.seconds

internal suspend fun runUntilSignal(
    spec: RuntimeSpec,
    block: suspend AlexandriteRuntime.() -> Unit,
    signals: Signals,
    hookMargin: Duration = SHUTDOWN_HOOK_MARGIN,
): Termination {
    val run = RuntimeRun(spec, RuntimeRun.RUN_CANCELLED)
    val ended = CountDownLatch(1)
    val received = AtomicBoolean()
    val trap = signals.trap { signal ->
        if (received.compareAndSet(false, true)) {
            run.stop(StopRequest.shutdown("received SIG${signal.name}"))
        } else {
            val status = 128 + signal.number
            logger.warn("{}: received SIG{} while stopping, halting with status {}", run.name, signal.name, status)
            signals.halt(status)
        }
    } ?: signals.onShutdown {
        run.stop(StopRequest.shutdown("the JVM is shutting down"))
        val bound = spec.config.shutdownGrace + hookMargin
        if (!ended.await(bound.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            logger.warn("{}: the stop did not end within {}, the JVM shuts down regardless", run.name, bound)
        }
    }
    try {
        return run.runBlock(block)
    } finally {
        ended.countDown()
        trap.close()
    }
}

internal class HostSignal(val name: String, val number: Int)

/** Where [runUntilSignal] receives signals. */
internal interface Signals {
    /** Calls [handler] on each SIGTERM and SIGINT until the result is closed, or returns null where it cannot. */
    fun trap(handler: (HostSignal) -> Unit): AutoCloseable?

    /** Runs [hook] when the JVM shuts down, until the result is closed. */
    fun onShutdown(hook: () -> Unit): AutoCloseable

    fun halt(status: Int)
}

internal object JvmSignals : Signals {
    private val NAMES = listOf("TERM", "INT")

    override fun trap(handler: (HostSignal) -> Unit): AutoCloseable? = try {
        SunMiscSignals.trap(NAMES, handler)
    } catch (e: LinkageError) {
        logger.warn("Cannot handle signals, stopping in a shutdown hook instead: {}", e.toString())
        null
    } catch (e: IllegalArgumentException) {
        logger.warn("Cannot handle signals, stopping in a shutdown hook instead: {}", e.toString())
        null
    }

    override fun onShutdown(hook: () -> Unit): AutoCloseable {
        val thread = Thread(hook, "alexandrite-shutdown")
        Runtime.getRuntime().addShutdownHook(thread)
        return AutoCloseable {
            try {
                Runtime.getRuntime().removeShutdownHook(thread)
            } catch (ignored: IllegalStateException) {
            }
        }
    }

    override fun halt(status: Int) {
        Runtime.getRuntime().halt(status)
    }
}

/** The JVM's signal handlers, shared by every trap and given back when the last one closes. */
@JdkOnly
private object SunMiscSignals {
    private val traps = mutableListOf<Trap>()
    private var previous: List<Pair<Signal, SignalHandler>> = emptyList()

    private class Trap(val handler: (HostSignal) -> Unit)

    @Synchronized
    fun trap(names: List<String>, handler: (HostSignal) -> Unit): AutoCloseable {
        if (traps.isEmpty()) previous = install(names)
        val trap = Trap(handler)
        traps += trap
        return AutoCloseable { release(trap) }
    }

    @Synchronized
    private fun release(trap: Trap) {
        if (traps.remove(trap) && traps.isEmpty()) {
            restore(previous)
            previous = emptyList()
        }
    }

    private fun install(names: List<String>): List<Pair<Signal, SignalHandler>> {
        val installed = mutableListOf<Pair<Signal, SignalHandler>>()
        try {
            for (name in names) {
                val signal = Signal(name)
                installed += signal to Signal.handle(signal, ::dispatch)
            }
        } catch (e: IllegalArgumentException) {
            restore(installed)
            throw e
        }
        return installed
    }

    private fun dispatch(signal: Signal) {
        val current = synchronized(this) { traps.toList() }
        for (trap in current) trap.handler(HostSignal(signal.name, signal.number))
    }

    private fun restore(handlers: List<Pair<Signal, SignalHandler>>) {
        for ((signal, handler) in handlers.asReversed()) Signal.handle(signal, handler)
    }
}
