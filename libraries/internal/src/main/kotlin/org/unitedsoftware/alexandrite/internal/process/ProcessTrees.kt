package org.unitedsoftware.alexandrite.internal.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.portable.JdkFeatures
import org.unitedsoftware.alexandrite.internal.portable.JdkOnly
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Waits for processes and ends them with the processes they started. */
public interface ProcessTrees {
    /** Suspends until [process] has exited. */
    public suspend fun awaitExit(process: Process)

    /** The processes [process] has started, as they are now, none where they cannot be listed. */
    public fun descendants(process: Process): Descendants

    /**
     * Ends [process] and the processes it started: asks its descendants and then the process itself to stop, and
     * kills those still running after [grace].
     */
    public suspend fun destroy(process: Process, grace: Duration)

    public companion object {
        /** Through `ProcessHandle` where the JDK has it, else [direct]. */
        public val system: ProcessTrees = if (JdkFeatures.processHandles) HandleTrees else DirectTrees

        /** Reaches only the process itself: its exit by `waitFor`, its end by `destroy` and `destroyForcibly`. */
        public val direct: ProcessTrees = DirectTrees
    }
}

/** Processes found earlier, to end once they may have outlived the process that started them. */
public fun interface Descendants {
    /** Asks those still running to stop, and kills those still running after [grace]. */
    public suspend fun stop(grace: Duration)
}

/** Ends [process] and the processes it started, as [ProcessTrees.system] does. */
public suspend fun destroyTree(process: Process, grace: Duration) {
    ProcessTrees.system.destroy(process, grace)
}

@JdkOnly
private object HandleTrees : ProcessTrees {
    override suspend fun awaitExit(process: Process) {
        process.onExit().await()
    }

    override fun descendants(process: Process): Descendants {
        val handles = process.descendants().toList()
        return Descendants { grace -> stopAll(handles, grace) }
    }

    override suspend fun destroy(process: Process, grace: Duration) {
        // Listed before the process ends, which drops them from its descendants
        val descendants = process.descendants().toList()
        descendants.forEach { stop(it, forcibly = false) }
        process.destroy()
        withTimeoutOrNull(grace) {
            while (process.isAlive || descendants.any { it.isAlive }) delay(POLL)
        }
        val late = if (process.isAlive) process.descendants().toList() else emptyList()
        (descendants + late).filter { it.isAlive }.forEach { stop(it, forcibly = true) }
        if (process.isAlive) process.destroyForcibly()
    }

    private suspend fun stopAll(handles: List<ProcessHandle>, grace: Duration) {
        val alive = handles.filter { it.isAlive }
        alive.forEach { stop(it, forcibly = false) }
        withTimeoutOrNull(grace) { while (alive.any { it.isAlive }) delay(POLL) }
        alive.filter { it.isAlive }.forEach { stop(it, forcibly = true) }
    }

    private fun stop(handle: ProcessHandle, forcibly: Boolean) {
        try {
            if (forcibly) handle.destroyForcibly() else handle.destroy()
        } catch (e: IllegalStateException) {
            // The handle is this process.
        } catch (e: SecurityException) {
            // Not allowed to signal it.
        }
    }
}

private object DirectTrees : ProcessTrees {
    override suspend fun awaitExit(process: Process) {
        runInterruptible(Dispatchers.IO) { process.waitFor() }
    }

    override fun descendants(process: Process): Descendants = Descendants {}

    override suspend fun destroy(process: Process, grace: Duration) {
        logger.debug("Ending {} without the processes it started, which this JVM cannot list", process)
        process.destroy()
        if (withTimeoutOrNull(grace) { awaitExit(process) } == null) process.destroyForcibly()
    }
}

private val POLL = 10.milliseconds

private val logger = LoggerFactory.getLogger(ProcessTrees::class.java)
