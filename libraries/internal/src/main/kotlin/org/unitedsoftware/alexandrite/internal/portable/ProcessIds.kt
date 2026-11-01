package org.unitedsoftware.alexandrite.internal.portable

import java.nio.file.Files
import java.nio.file.Paths

/** Process ids, where `ProcessHandle` or `/proc` tells them. */
public object ProcessIds {
    /** This process's id, null where neither tells it. */
    public val current: Long? by lazy {
        (if (JdkFeatures.processHandles) currentFromHandle() else null)
            ?: currentFromProc()
    }

    /** [process]'s id, null where `Process.pid` is missing. */
    public fun of(process: Process): Long? = if (JdkFeatures.processHandles) pidOf(process) else null

    @JdkOnly
    private fun currentFromHandle(): Long = ProcessHandle.current().pid()

    @JdkOnly
    private fun pidOf(process: Process): Long? = try {
        process.pid()
    } catch (_: UnsupportedOperationException) {
        null
    }

    private fun currentFromProc(): Long? = try {
        Files.readSymbolicLink(Paths.get("/proc/self")).toString().toLongOrNull()
    } catch (_: Exception) {
        null
    }
}
