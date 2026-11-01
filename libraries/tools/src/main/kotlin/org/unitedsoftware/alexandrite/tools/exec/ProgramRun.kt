package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.internal.portable.JdkOnly
import org.unitedsoftware.alexandrite.internal.portable.ProcessIds
import org.unitedsoftware.alexandrite.internal.process.ProcessTrees
import org.unitedsoftware.alexandrite.internal.text.shellLine
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How a program run ended: [exitCode] is null when it timed out, and its own limit stopped it when [limited]. */
internal class ProgramOutcome(val exitCode: Int?, val output: String, val limited: Boolean = false)

/** A program of a pipeline: [command] with [environment], its streams sent as [redirects] say, for [limit] at most. */
internal class Stage(
    val command: List<String>,
    val environment: Map<String, String>,
    val redirects: List<Redirect> = emptyList(),
    val limit: Duration? = null,
)

/**
 * Runs [stages] in [cwd] as a pipeline, each reading what the one before writes and the first no input, all for at most
 * [timeout], keeping [maxChars] of what they show; the exit code is the last program's.
 *
 * A program and the processes it started are destroyed when its limit or [timeout] passes or the call is cancelled.
 */
internal suspend fun runPipeline(
    stages: List<Stage>,
    cwd: Path,
    timeout: Duration,
    maxChars: Int,
    onLine: ((String) -> Unit)? = null,
    start: (ProcessBuilder) -> Process = ProcessBuilder::start,
    trees: ProcessTrees = ProcessTrees.system,
    discard: ProcessBuilder.Redirect? = DISCARD,
): ProgramOutcome {
    val sinks = stages.mapIndexed { index, stage -> sinks(stage.redirects, last = index == stages.lastIndex) }
    val processes = mutableListOf<Process>()
    try {
        withContext(Dispatchers.IO + NonCancellable) {
            for ((index, stage) in stages.withIndex()) processes += start(builder(stage, cwd, sinks[index], discard))
        }
        val output = OutputCollector(maxChars)
        val threads = connect(processes, stages, sinks, output, onLine, discard != null)
        val limited = BooleanArray(stages.size)
        val finished = withTimeoutOrNull(timeout) {
            coroutineScope {
                for ((index, process) in processes.withIndex()) {
                    val limit = stages[index].limit?.takeIf { it < timeout } ?: continue
                    launch {
                        if (withTimeoutOrNull(limit) { trees.awaitExit(process) } == null) {
                            limited[index] = true
                            withContext(NonCancellable) { trees.destroy(process, STOP_GRACE) }
                        }
                    }
                }
                for (process in processes) trees.awaitExit(process)
            }
        } != null
        if (!finished) stop(processes, trees)
        val read = TimeSource.Monotonic.markNow() + READER_WAIT
        runInterruptible(Dispatchers.IO) {
            for (thread in threads) thread.join((-read.elapsedNow()).inWholeMilliseconds.coerceAtLeast(1))
        }
        val code = when {
            !finished -> null
            limited.last() -> TIMED_OUT
            else -> processes.last().exitValue()
        }
        return ProgramOutcome(code, output.text(), finished && limited.last())
    } catch (e: CancellationException) {
        stop(processes, trees)
        throw e
    } catch (e: IOException) {
        stop(processes, trees)
        throw e
    }
}

/** Where a program's stream goes: into the next program, into what the call shows, or nowhere. */
private enum class Sink { NEXT, SHOWN, DISCARDED }

/** Where the output and the errors of a program go after [redirects], when it is the [last] of its pipeline. */
private fun sinks(redirects: List<Redirect>, last: Boolean): Pair<Sink, Sink> {
    var output = if (last) Sink.SHOWN else Sink.NEXT
    var errors = Sink.SHOWN
    for (redirect in redirects) {
        when (redirect) {
            Redirect.ERRORS_TO_OUTPUT -> errors = output

            Redirect.OUTPUT_TO_ERRORS -> output = errors

            Redirect.OUTPUT_DISCARDED -> output = Sink.DISCARDED

            Redirect.ERRORS_DISCARDED -> errors = Sink.DISCARDED

            Redirect.BOTH_DISCARDED -> {
                output = Sink.DISCARDED
                errors = Sink.DISCARDED
            }

            Redirect.NO_INPUT -> Unit
        }
    }
    return output to errors
}

/**
 * What starts [stage] in [cwd], its output and errors joined where [sinks] sends both one way. A discarded stream goes
 * to [discard], or is left to [connect] to read without it.
 */
private fun builder(
    stage: Stage,
    cwd: Path,
    sinks: Pair<Sink, Sink>,
    discard: ProcessBuilder.Redirect?,
): ProcessBuilder {
    val (output, errors) = sinks
    val builder = ProcessBuilder(stage.command).directory(cwd.toFile())
        .redirectErrorStream(output == errors && output != Sink.DISCARDED)
    if (discard != null) {
        if (output == Sink.DISCARDED) builder.redirectOutput(discard)
        if (errors == Sink.DISCARDED) builder.redirectError(discard)
    }
    builder.environment().apply {
        clear()
        putAll(stage.environment)
    }
    return builder
}

/**
 * Starts the threads that pass the streams of [processes] on as [sinks] say, into [output] or into the next program,
 * whose input otherwise ends at once, and that read the discarded ones unless they were [redirected].
 */
private fun connect(
    processes: List<Process>,
    stages: List<Stage>,
    sinks: List<Pair<Sink, Sink>>,
    output: OutputCollector,
    onLine: ((String) -> Unit)?,
    redirected: Boolean,
): List<Thread> {
    val threads = mutableListOf<Thread>()
    val feeds = arrayOfNulls<InputStream>(processes.size)
    for ((index, process) in processes.withIndex()) {
        val named = named(process)
        fun route(stream: InputStream, sink: Sink) {
            when (sink) {
                Sink.NEXT -> feeds[index + 1] = stream

                Sink.SHOWN -> threads += thread(isDaemon = true, name = "command.exec output$named") {
                    output.drain(stream, onLine)
                }

                Sink.DISCARDED -> threads += thread(isDaemon = true, name = "command.exec discard$named") {
                    discard(stream)
                }
            }
        }
        val (out, errors) = sinks[index]
        val joined = out == errors && out != Sink.DISCARDED
        if (out != Sink.DISCARDED || !redirected) route(process.inputStream, out)
        if (!joined && (errors != Sink.DISCARDED || !redirected)) route(process.errorStream, errors)
    }
    for ((index, process) in processes.withIndex()) {
        val feed = feeds[index]
        if (feed == null || Redirect.NO_INPUT in stages[index].redirects) {
            feed?.close()
            process.outputStream.close()
        } else {
            threads += thread(isDaemon = true, name = "command.exec pipe${named(process)}") {
                pump(feed, process.outputStream)
            }
        }
    }
    return threads
}

/** Copies [input] to [output] as it comes, until either ends. */
private fun pump(input: InputStream, output: OutputStream) {
    try {
        input.use {
            output.use {
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
            }
        }
    } catch (e: IOException) {
        // The reading program ended.
    }
}

/** Reads [input] to its end, keeping nothing. */
private fun discard(input: InputStream) {
    try {
        input.use {
            val buffer = ByteArray(CHUNK)
            while (it.read(buffer) >= 0) continue
        }
    } catch (e: IOException) {
        // The stream closed under the reader.
    }
}

/** ` <pid>` of [process] for a thread's name, empty where the JDK does not tell it. */
private fun named(process: Process): String = ProcessIds.of(process)?.let { " $it" }.orEmpty()

/** Destroys [processes] with the processes they started. */
private suspend fun stop(processes: List<Process>, trees: ProcessTrees) = withContext(NonCancellable) {
    coroutineScope { for (process in processes) launch { trees.destroy(process, STOP_GRACE) } }
}

/** `Redirect.DISCARD`, null where the JDK lacks it. */
private val DISCARD: ProcessBuilder.Redirect? = discardRedirect()

@JdkOnly
private fun discardRedirect(): ProcessBuilder.Redirect? = try {
    ProcessBuilder.Redirect.DISCARD
} catch (e: LinkageError) {
    null
}

/**
 * The variables of [parent] that a program gets: the usual ones and those named in [extra]. They include `TERM` and
 * every `LC_*` on purpose, which an MCP server's minimal environment leaves out, since a command's output is read like
 * a terminal's.
 */
internal fun childEnvironment(parent: Map<String, String>, extra: List<String>): Map<String, String> {
    val passed = parent.filterKeys { nameIs(it, PASSED) || nameStartsWith(it, "LC_") || nameIs(it, extra) }
    if (passed.variable("PATH") != null) return passed
    return passed + ("PATH" to SYSTEM_DIRECTORIES.joinToString(File.pathSeparator))
}

/** Keeps the first and the last characters of an output, counting the ones cut between them. */
internal class OutputCollector(limit: Int) {
    private val headLimit = limit - limit / 2
    private val head = StringBuilder()
    private val tail = CharArray(limit / 2)
    private var tailStart = 0
    private var tailSize = 0
    private var cut = 0L

    fun drain(stream: InputStream, onLine: ((String) -> Unit)? = null) {
        val line = StringBuilder()
        var omitted = 0L
        fun reportLine() {
            val truncation = if (omitted > 0) " [$omitted characters omitted]" else ""
            onLine?.invoke(line.toString().trimEnd('\r') + truncation)
            line.setLength(0)
            omitted = 0
        }
        try {
            stream.reader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(CHUNK)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    append(chunk, count)
                    if (onLine != null) {
                        for (index in 0 until count) {
                            val character = chunk[index]
                            if (character == '\n') {
                                reportLine()
                            } else if (line.length < CHUNK) {
                                line.append(character)
                            } else {
                                omitted++
                            }
                        }
                    }
                }
            }
            if (line.isNotEmpty()) reportLine()
        } catch (e: IOException) {
            // The stream closed under the reader.
        }
    }

    @Synchronized
    fun append(chars: CharArray, count: Int) {
        val headed = minOf(count, headLimit - head.length)
        head.appendRange(chars, 0, headed)
        for (index in headed until count) {
            if (tail.isEmpty()) {
                cut++
            } else if (tailSize < tail.size) {
                tail[(tailStart + tailSize++) % tail.size] = chars[index]
            } else {
                tail[tailStart] = chars[index]
                tailStart = (tailStart + 1) % tail.size
                cut++
            }
        }
    }

    /** The output, with a note where characters were cut. */
    @Synchronized
    fun text(): String {
        var first = head.toString()
        var last = String(CharArray(tailSize) { tail[(tailStart + it) % tail.size] })
        if (cut == 0L) return first + last
        var dropped = cut
        if (first.lastOrNull()?.isHighSurrogate() == true) {
            first = first.dropLast(1)
            dropped++
        }
        if (last.firstOrNull()?.isLowSurrogate() == true) {
            last = last.drop(1)
            dropped++
        }
        return "$first\n[… $dropped characters cut …]\n$last"
    }
}

private val POSIX_PASSED = setOf("PATH", "HOME", "USER", "LANG", "TERM", "TMPDIR")

/** What a Windows program needs as well: where the system, the temporary files and the user's own files are. */
private val WINDOWS_PASSED = setOf(
    "SystemRoot", "SystemDrive", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "HOMEDRIVE",
    "HOMEPATH", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "COMMONPROGRAMFILES",
    "PUBLIC", "USERNAME", "USERDOMAIN", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS",
)

private val PASSED: Set<String> = POSIX_PASSED + if (POSIX_HOST) emptySet() else WINDOWS_PASSED

/** How long a program and its processes have to stop before they are killed. */
private val STOP_GRACE = 1.seconds

/** How long the output is still read after the program ended. */
private val READER_WAIT = 1.seconds

private const val CHUNK = 8192

/**
 * Runs the steps of [call] in turn, without a shell, each program started by [launch] with [environment] and its own
 * variables, all of them for at most [timeout], keeping [maxChars] of their output.
 *
 * A step is checked again by [planner] right before it runs, and the call stops where the check finds a change. The
 * steps of a pipeline run together, each checked before any starts.
 */
internal suspend fun runSteps(
    call: PlannedCall,
    planner: Planner,
    environment: Map<String, String>,
    timeout: Duration,
    maxChars: Int,
    onLine: ((String) -> Unit)? = null,
    launch: (ProcessBuilder) -> Process = ProcessBuilder::start,
): ProgramOutcome {
    val start = TimeSource.Monotonic.markNow()
    val single = call.steps.size == 1
    val shown = mutableListOf<String>()
    var status = 0
    var cwd = call.cwd
    var index = 0
    while (index < call.steps.size) {
        val step = call.steps[index]
        var end = index + 1
        while (call.steps.getOrNull(end)?.joint == SegmentJoint.PIPE) end++
        val pipeline = call.steps.subList(index, end)
        index = end
        val header = "$ " + pipeline.joinToString(" | ") { piped ->
            (listOf(shellLine(piped.words)) + piped.redirects.map { it.text }).joinToString(" ")
        }
        val runs = when (step.joint) {
            SegmentJoint.AND -> status == 0
            SegmentJoint.OR -> status != 0
            else -> true
        }
        if (!runs) {
            shown += "$header [not run]"
            continue
        }
        val run = step.run
        if (run == null) {
            val target = step.words[1]
            val checked = recheck(call, step, planner) { planner.changed(target, cwd) }
            when {
                checked is Checked.Stopped -> return stopped(shown, header, checked.why, single, maxChars)

                Files.isDirectory(step.cwd) -> {
                    cwd = step.cwd
                    status = 0
                    shown += header
                }

                else -> {
                    status = 1
                    shown += "$header [exit 1]\ncd: $target: no such directory"
                }
            }
        } else {
            val programs = pipeline.mapNotNull { piped -> piped.run?.let { piped to it } }
            for ((piped, program) in programs) {
                val checked = recheck(call, piped, planner) {
                    planner.program(program.argv, cwd, program.timeout, program.environment, program.limit).executable
                }
                if (checked is Checked.Stopped) return stopped(shown, header, checked.why, single, maxChars)
            }
            val left = timeout - start.elapsedNow()
            if (!left.isPositive()) return ProgramOutcome(null, joined(shown, maxChars))
            val stages = programs.map { (piped, program) ->
                Stage(
                    listOf(program.resolved.toString()) + program.argv.drop(1),
                    environment + program.environment,
                    piped.redirects,
                    program.limit,
                )
            }
            val outcome = runPipeline(stages, cwd, left, maxChars, onLine, launch)
            val output = outcome.output.trimEnd()
            val code = outcome.exitCode
            if (single) {
                val note = if (outcome.limited) {
                    "\n[stopped by its timeout after ${run.limit?.inWholeSeconds} s]"
                } else {
                    ""
                }
                return ProgramOutcome(code, output + note)
            }
            val ending = when {
                code == null -> " [timed out]"
                outcome.limited -> " [stopped by its timeout, exit $code]"
                code != 0 -> " [exit $code]"
                else -> ""
            }
            shown += header + ending + if (output.isEmpty()) "" else "\n$output"
            code ?: return ProgramOutcome(null, joined(shown, maxChars))
            status = code
        }
        val next = call.steps.getOrNull(index)?.joint
        if (call.errexit && status != 0 && (next == null || next == SegmentJoint.THEN)) break
    }
    return ProgramOutcome(status, joined(shown, maxChars))
}

/** Whether [step] of [call] still plans as it did, where [planned] plans it again. */
private fun recheck(call: PlannedCall, step: PlannedStep, planner: Planner, planned: () -> Path): Checked {
    val again = try {
        planned()
    } catch (e: ToolRefusal) {
        return Checked.Stopped(e.message)
    }
    val expected = step.run?.executable ?: step.cwd
    if (again != expected) {
        return Checked.Stopped("${step.words.first()} changed since ${call.argv.first()} was reviewed.")
    }
    if (!call.asks.containsAll(planner.asks)) {
        return Checked.Stopped("${step.words.first()} reaches a place the floor asks a person about since review.")
    }
    return Checked.Same
}

private sealed interface Checked {
    data object Same : Checked

    class Stopped(val why: String) : Checked
}

/** The outcome of a call stopped at the step of [header] for [why]. */
private fun stopped(shown: List<String>, header: String, why: String, single: Boolean, maxChars: Int): ProgramOutcome {
    if (single) return ProgramOutcome(NOT_RUN, "[not run: $why]")
    return ProgramOutcome(NOT_RUN, joined(shown + "$header [not run: $why]", maxChars))
}

/** [blocks] one after the other, cut in the middle to [maxChars]. */
private fun joined(blocks: List<String>, maxChars: Int): String {
    val text = blocks.joinToString("\n")
    if (text.length <= maxChars) return text
    val collector = OutputCollector(maxChars)
    collector.append(text.toCharArray(), text.length)
    return collector.text()
}

/** The exit code of a program that its `timeout` stopped. */
private const val TIMED_OUT = 124

/** The exit code of a step that could not run. */
private const val NOT_RUN = 126
