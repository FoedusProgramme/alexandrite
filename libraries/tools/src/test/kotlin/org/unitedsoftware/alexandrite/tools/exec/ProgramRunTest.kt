package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.process.ProcessTrees
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Pipelines run without `ProcessHandle`. */
@DisabledOnOs(OS.WINDOWS)
class ProgramRunTest {
    @TempDir
    lateinit var directory: Path

    private fun stage(script: String, vararg redirects: Redirect, limit: Duration? = null) =
        Stage(listOf("/bin/sh", "-c", script), mapOf("PATH" to "/usr/bin:/bin"), redirects.toList(), limit)

    private fun run(vararg stages: Stage, timeout: Duration = 20.seconds): ProgramOutcome = runBlocking {
        runPipeline(stages.toList(), directory, timeout, MAX_CHARS, trees = ProcessTrees.direct)
    }

    @Test
    fun `a pipeline passes its output on and ends with the last program's exit code`() {
        val outcome = run(stage("echo one; echo two"), stage("tr a-z A-Z; exit 3"))

        assertEquals(3, outcome.exitCode)
        assertEquals("ONE\nTWO", outcome.output.trim())
    }

    @Test
    fun `a program writing more than a pipe holds to a discarded stream finishes`() {
        val output = run(stage("$MEGABYTE; echo shown >&2", Redirect.OUTPUT_DISCARDED))
        val both = run(stage("$MEGABYTE; $MEGABYTE >&2; exit 4", Redirect.BOTH_DISCARDED))

        assertEquals(0 to "shown", output.exitCode to output.output.trim())
        assertEquals(4 to "", both.exitCode to both.output)
    }

    @Test
    fun `a call ends with its program while a process it left running holds the discarded streams`() {
        val start = TimeSource.Monotonic.markNow()
        val outcome = run(stage("sleep 3 & echo started >&2", Redirect.BOTH_DISCARDED))

        assertEquals(0 to "", outcome.exitCode to outcome.output)
        assertTrue(start.elapsedNow() < 900.milliseconds)
    }

    @Test
    fun `a program's own limit and the call's timeout stop it`() {
        val start = TimeSource.Monotonic.markNow()
        val limited = run(stage("sleep 30", limit = 300.milliseconds))
        val timedOut = run(stage("sleep 30"), timeout = 300.milliseconds)

        assertEquals(124, limited.exitCode)
        assertTrue(limited.limited)
        assertNull(timedOut.exitCode)
        assertFalse(timedOut.limited)
        assertTrue(start.elapsedNow() < 10.seconds)
    }

    private companion object {
        const val MAX_CHARS = 10_000
        const val MEGABYTE = "head -c 1000000 /dev/zero"
    }
}
