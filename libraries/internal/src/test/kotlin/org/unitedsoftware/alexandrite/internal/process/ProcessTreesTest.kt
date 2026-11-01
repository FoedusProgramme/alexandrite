package org.unitedsoftware.alexandrite.internal.process

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@DisabledOnOs(OS.WINDOWS)
class ProcessTreesTest {
    private val started = mutableListOf<ProcessHandle>()

    @AfterTest
    fun tearDown() {
        started.forEach { it.destroyForcibly() }
    }

    /** A shell that starts a background `sleep`, with the shell's handle and the sleep's. */
    private fun spawner(script: String): Pair<Process, ProcessHandle> {
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        started += process.toHandle()
        val child = process.inputStream.bufferedReader().readLine().trim().toLong()
        val handle = ProcessHandle.of(child).orElseThrow()
        started += handle
        return process to handle
    }

    private fun gone(handle: ProcessHandle): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
        while (handle.isAlive && deadline.hasNotPassedNow()) Thread.sleep(10)
        return !handle.isAlive
    }

    @Test
    fun `a process and the process it started both end`() = runBlocking {
        val (process, child) = spawner("sleep 300 & echo \$!; wait")

        destroyTree(process, 2.seconds)

        assertEquals(true to true, gone(process.toHandle()) to gone(child))
    }

    @Test
    fun `processes that ignore the request to stop are killed after the grace`() = runBlocking {
        val (process, child) = spawner("trap '' TERM; sleep 300 & echo \$!; wait")
        val start = TimeSource.Monotonic.markNow()

        destroyTree(process, 300.milliseconds)

        assertTrue(start.elapsedNow() >= 300.milliseconds)
        assertEquals(true to true, gone(process.toHandle()) to gone(child))
    }

    @Test
    fun `descendants found before the process ends are stopped afterwards`() = runBlocking {
        val (process, child) = spawner("sleep 300 & echo \$!; wait")
        val descendants = ProcessTrees.system.descendants(process)
        process.destroyForcibly()
        ProcessTrees.system.awaitExit(process)

        descendants.stop(2.seconds)

        assertTrue(gone(child))
    }

    // Without ProcessHandle.

    @Test
    fun `without ProcessHandle, the process itself ends and the processes it started are left alone`() = runBlocking {
        val (process, child) = spawner("sleep 300 & echo \$!; wait")
        val descendants = ProcessTrees.direct.descendants(process)

        ProcessTrees.direct.destroy(process, 2.seconds)
        descendants.stop(2.seconds)

        assertTrue(gone(process.toHandle()))
        assertTrue(child.isAlive)
    }

    @Test
    fun `without ProcessHandle, a process that ignores the request to stop is killed after the grace`() = runBlocking {
        val (process, _) = spawner("trap '' TERM; sleep 300 & echo \$!; wait")
        val start = TimeSource.Monotonic.markNow()

        ProcessTrees.direct.destroy(process, 300.milliseconds)

        assertTrue(start.elapsedNow() >= 300.milliseconds)
        assertTrue(gone(process.toHandle()))
    }

    @Test
    fun `without ProcessHandle, waiting for the exit returns once it exits and can be given up`() = runBlocking {
        val quick = ProcessBuilder("/bin/sh", "-c", "exit 3").start()
        ProcessTrees.direct.awaitExit(quick)
        assertEquals(3, quick.exitValue())

        val slow = ProcessBuilder("/bin/sh", "-c", "sleep 300").start()
        started += slow.toHandle()

        assertNull(withTimeoutOrNull(200.milliseconds) { ProcessTrees.direct.awaitExit(slow) })
        assertTrue(slow.isAlive)
    }
}
