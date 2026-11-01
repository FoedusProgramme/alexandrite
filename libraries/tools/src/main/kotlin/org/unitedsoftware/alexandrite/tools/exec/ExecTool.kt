package org.unitedsoftware.alexandrite.tools.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.path.homeDirectory
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.internal.tool.booleanArgument
import org.unitedsoftware.alexandrite.internal.tool.countArgument
import org.unitedsoftware.alexandrite.internal.tool.stringArgument
import org.unitedsoftware.alexandrite.internal.tool.stringsArgument
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.sandbox.Contained
import org.unitedsoftware.alexandrite.sdk.sandbox.Sandbox
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.tools.fs.property
import org.unitedsoftware.alexandrite.tools.fs.schema
import org.unitedsoftware.alexandrite.tools.monitor.MonitorOptions
import org.unitedsoftware.alexandrite.tools.monitor.monitoring
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Singleton
@Contribute(Tool::class)
internal class ExecTool(private val floor: HardFloor, private val config: ExecConfig) : ReviewedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = EXEC_TOOL,
        description = "Runs one program with its arguments, without a shell, and returns its exit code and its " +
            "output (stdout and stderr together; of a long output, the start and the end). Give either 'command', " +
            "one line split like a shell splits it (quotes and backslashes; a leading ~ is the home directory) but " +
            "with no pipes, redirects, chaining, variables, globs or substitutions, or 'argv'. Run programs " +
            "directly and set 'cwd' instead of using cd. Prefer separate calls, or a plain chain such as " +
            "sh -c 'make 2>&1 | tail -5 && make test' (only words, quotes, &&, ||, ;, |, cd, 2>&1, >&2 and " +
            "redirects to /dev/null), which runs as plain programs; any " +
            "other shell script is opaque and needs a person's approval more often. The program gets no input and " +
            "a reduced environment, runs in 'cwd' and is stopped with every process it started after " +
            "'timeoutSeconds'. Refuses calls that reach protected places. Set background to true to start " +
            "immediately as a job. A monitor starts a background job " +
            "and wakes you with filtered output batches until the program exits or its lifetime expires. " +
            "Monitor buffers are bounded and excess output can be dropped with a count. When your agent has a " +
            "sandbox, programs run in it: they write only in your workspace, the conversation's directory, trusted " +
            "folders and temp, and reach only allowed hosts through a proxy; name more hosts in 'network', or set " +
            "'sandbox' to false to run outside it, which needs approval more often.",
        parameters = schema {
            property("background", "boolean", "Start immediately as a background job (default false).")
            putJsonObject("monitor") {
                put("type", "object")
                put("additionalProperties", false)
                put("required", JsonArray(listOf(JsonPrimitive("filter"))))
                putJsonObject("properties") {
                    property(
                        "filter",
                        "string",
                        "Literal substring selecting output lines, nonempty and at most 1024 characters.",
                    )
                    property("batchSeconds", "integer", "Batch window, 1..30 seconds (default 3).")
                    property("intervalSeconds", "integer", "Minimum wake interval, 1..3600 seconds (default 10).")
                    property(
                        "maxLifetimeSeconds",
                        "integer",
                        "Maximum monitor lifetime, no longer than timeoutSeconds.",
                    )
                }
            }
            property("command", "string", "The program and its arguments as one line, such as \"./gradlew test\".")
            putJsonObject("argv") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "The program and its arguments, each passed as it is. Instead of 'command'.")
            }
            property(
                "cwd",
                "string",
                "The directory to run in, instead of cd: an absolute path, or one relative to your workspace " +
                    "(~ is home). Default your workspace.",
            )
            property(
                "sandbox",
                "boolean",
                "Whether the program runs in your agent's sandbox, when it has one (default true).",
            )
            putJsonObject("network") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put(
                    "description",
                    "Hosts the sandboxed program may reach beside the allowed ones, such as \"example.com\" or " +
                        "\"127.0.0.1:8080\"; asking for them needs approval.",
                )
            }
            property(
                "timeoutSeconds",
                "integer",
                "How long the program may run, in seconds: at most ${config.maxTimeoutSeconds}, which is the default.",
            )
        },
        risk = ToolRisk.EXEC,
        timeout = config.maxTimeoutSeconds.seconds,
        activity = ToolActivity.EXECUTE,
        titleArguments = listOf("command", "argv"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
        when (val inspection = inspect(arguments, context, running = false)) {
            is Inspection.Problem -> CallReview.builder().problem(inspection.text).build()

            is Inspection.Refused -> CallReview.builder().refusal(inspection.text).build()

            is Inspection.Ready -> with(inspection.call) {
                CallReview.builder()
                    .risk(if (readOnly) ToolRisk.READ_ONLY else ToolRisk.EXEC)
                    .preview(
                        ReviewPreview.Command(
                            argv,
                            cwd,
                            executable,
                            opaque,
                            segments(this),
                            inspection.monitor?.filter,
                        ),
                    )
                    .snapshot(snapshot)
                    .rule(rule)
                    .background(inspection.background)
                    .untrusted(!vetted)
                    .reads((listOf(cwd) + steps.map { it.cwd } + reached).distinct())
                    .person(asks.takeIf { it.isNotEmpty() }?.joinToString("; "))
                    .contained(
                        inspection.sandbox?.let { sandbox ->
                            Contained(sandbox.environment.name, sandbox.boundary(directories(this)), inspection.hosts)
                        },
                    )
                    .build()
            }
        }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val ready = when (val inspection = inspect(arguments, context, running = true)) {
            is Inspection.Problem -> return ToolResult(inspection.text, isError = true)
            is Inspection.Refused -> return ToolResult(inspection.text, isError = true)
            is Inspection.Ready -> inspection
        }
        val call = ready.call
        val monitor = ready.monitor
        val reviewed = context.clearance?.snapshot
        val named = call.argv.first()
        if (reviewed != null && reviewed != call.snapshot) {
            return ToolResult(
                "$named or a file it is given changed since the call was reviewed, so it is not run: call " +
                    "again to have it reviewed anew.",
                isError = true,
            )
        }
        val unvetted = call.programs.filterNot { it.vetted }
        context.touched(call.cwd)
        call.steps.forEach { if (it.cwd != call.cwd) context.touched(it.cwd) }
        call.reached.forEach(context::touched)
        if (unvetted.isNotEmpty()) {
            context.untrusted("command.exec ran ${unvetted.map { it.program }.distinct().joinToString(", ")}")
        }
        val planner = ready.planner
        val sandbox = ready.sandbox
        val lease = try {
            sandbox?.open(sandbox.boundary(directories(call)), ready.hosts)
        } catch (e: IOException) {
            return ToolResult(
                "$named was not started: the sandbox's network proxy could not listen " +
                    "(${e.message ?: e.javaClass.simpleName}).",
                isError = true,
            )
        }
        val launch: (ProcessBuilder) -> Process = if (sandbox == null || lease == null) {
            ProcessBuilder::start
        } else {
            { builder -> sandbox.environment.launch(builder, lease.boundary) }
        }
        var finalMonitorOutput = "[monitor ended]"
        val outcome = try {
            suspend fun runWithLines(onLine: ((String) -> Unit)?): ProgramOutcome = runSteps(
                call,
                planner,
                childEnvironment(System.getenv(), config.environment),
                monitor?.lifetime ?: call.timeout,
                config.maxOutputChars,
                onLine,
                launch,
            )
            if (monitor == null) {
                runWithLines(null)
            } else {
                val monitored = monitoring(monitor, context, ::runWithLines)
                finalMonitorOutput = buildString {
                    append("[monitor ended]")
                    if (monitored.remaining.isNotEmpty()) {
                        append("\nMonitor remaining matched output:\n")
                        append(monitored.remaining.joinToString("\n"))
                    }
                    val dropped = monitored.dropped
                    if (dropped > 0) append("\n[$dropped matching lines dropped by buffer limits]")
                }
                monitored.value
            }
        } catch (e: IOException) {
            return ToolResult(
                "$named could not be started: ${e.message ?: e.javaClass.simpleName}",
                isError = true,
            )
        } finally {
            lease?.close()
        }
        val refused = lease?.refused.orEmpty()
        val output = if (monitor == null) {
            outcome.output.trimEnd().ifEmpty { "[no output]" }
        } else {
            finalMonitorOutput
        }
        val exitCode = outcome.exitCode ?: return if (monitor != null) {
            ToolResult(
                "The monitor ended at its lifetime of ${monitor.lifetime.inWholeSeconds} s and stopped the program " +
                    "with the processes it started.\n$output" + sandboxNote(sandbox, refused, failed = false),
            )
        } else {
            ToolResult(
                "The program timed out after ${call.timeout.inWholeSeconds} s and was stopped with the processes it " +
                    "started. Its output until then:\n$output" + sandboxNote(sandbox, refused, failed = false),
                isError = true,
            )
        }
        return ToolResult(
            "Exit code: $exitCode\n$output" + sandboxNote(sandbox, refused, failed = exitCode != 0),
            isError = exitCode != 0,
        )
    }

    /** What the call would run, or why it cannot run, with the workspace created only when it is [running]. */
    private suspend fun inspect(arguments: JsonObject, context: ToolContext, running: Boolean): Inspection = try {
        val parsed = request(arguments)
        val monitor = monitorOptions(arguments, parsed.timeout)
        val cwd = parsed.cwd?.let { resolveCwd(it, context, running) } ?: run {
            val workspace = context.workspace
                ?: throw ToolRefusal("Give 'cwd': you have no workspace to run in by default.")
            if (running) context.openWorkspace() else workspace
        }
        val planner = Planner(floor, context.turn.agent, context.turn.rootConversation, isOperator(context))
        val missing = parsed.cwd == null && !running
        val request = Request(parsed.argv, cwd, parsed.timeout)
        val call = runInterruptible(Dispatchers.IO) {
            planner.plan(request, cwd, missingAllowed = missing, homeDirectory())
        }
        val sandbox = context.sandbox
        if (sandbox != null && !parsed.contained && sandbox.strict) {
            throw ToolRefusal("Your agent runs every program in its sandbox, so 'sandbox' may not be false.")
        }
        val contained = sandbox?.takeIf { parsed.contained }
        contained?.hostProblem(parsed.hosts)?.let { throw ToolRefusal(it) }
        val hosts = if (contained == null) emptyList() else parsed.hosts
        Inspection.Ready(call, planner, monitor, contained, hosts, parsed.background || monitor != null)
    } catch (e: ToolRefusal) {
        if (e.blocked) Inspection.Refused(e.message) else Inspection.Problem(e.message)
    }

    /** [text] as an absolute directory: a relative one resolved against the workspace, as the default cwd is. */
    private suspend fun resolveCwd(text: String, context: ToolContext, running: Boolean): Path {
        val path = try {
            Paths.get(expandHome(text))
        } catch (e: InvalidPathException) {
            throw ToolRefusal("'cwd' is no valid path.")
        }
        if (path.isAbsolute) return path.normalize()
        val workspace = context.workspace ?: throw ToolRefusal(
            "'cwd' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
        )
        return (if (running) context.openWorkspace() else workspace).resolve(path).normalize()
    }

    private fun request(arguments: JsonObject): ParsedRequest {
        val background = arguments.booleanArgument("background") ?: false
        val command = arguments.stringArgument("command")
        val argv = arguments.stringsArgument("argv")
        val words = when {
            command != null && argv != null -> throw ToolRefusal("Give either 'command' or 'argv', not both.")

            command != null -> CommandLine.split(command, homeDirectory())

            // argv has no quoting, so a word's leading '~' always expands.
            argv != null -> argv.map { expandHome(it) }

            else -> throw ToolRefusal("Give 'command' (a command line) or 'argv' (the program and its arguments).")
        }
        if (words.isEmpty() || words.first().isEmpty()) throw ToolRefusal("The call names no program.")
        if (words.any { '\u0000' in it }) {
            throw ToolRefusal("An argument holds a NUL character, which no program takes.")
        }
        val cwd = arguments.stringArgument("cwd")
        val seconds = arguments.countArgument("timeoutSeconds") ?: config.maxTimeoutSeconds
        if (seconds > config.maxTimeoutSeconds) {
            throw ToolRefusal("'timeoutSeconds' may be at most ${config.maxTimeoutSeconds}.")
        }
        val contained = arguments.booleanArgument("sandbox") ?: true
        val hosts = arguments.stringsArgument("network").orEmpty()
        return ParsedRequest(words, cwd, seconds.seconds, contained, hosts.distinct(), background)
    }

    private fun monitorOptions(arguments: JsonObject, timeout: Duration): MonitorOptions? {
        val value = arguments.optional("monitor") ?: return null
        val monitor = value as? JsonObject ?: throw ToolRefusal("'monitor' must be an object.")
        if (monitor.keys.any { it !in MONITOR_FIELDS }) throw ToolRefusal("Unknown monitor option.")
        val filter = (monitor["filter"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() && it.length <= 1024 }
            ?: throw ToolRefusal("'monitor.filter' must be a nonempty string of at most 1024 characters.")
        fun seconds(name: String, default: Int, maximum: Int): Duration {
            val raw = monitor[name] ?: return default.seconds
            val number = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?.takeIf { it in 1..maximum }
                ?: throw ToolRefusal("'monitor.$name' must be a whole number from 1 to $maximum.")
            return number.seconds
        }
        return MonitorOptions(
            filter,
            seconds("batchSeconds", 3, 30),
            seconds("intervalSeconds", 10, 3600),
            seconds("maxLifetimeSeconds", timeout.inWholeSeconds.toInt(), timeout.inWholeSeconds.toInt()),
        )
    }

    private fun isOperator(context: ToolContext): Boolean =
        context.turn.actorIsOperator && context.turn.kind in OPERATOR_KINDS

    private sealed interface Inspection {
        class Problem(val text: String) : Inspection

        class Refused(val text: String) : Inspection

        /** [sandbox] is the one the call runs in, null on the host, and [hosts] those it asks the sandbox for. */
        class Ready(
            val call: PlannedCall,
            val planner: Planner,
            val monitor: MonitorOptions?,
            val sandbox: Sandbox?,
            val hosts: List<String>,
            /** Whether the call starts as a background job. */
            val background: Boolean,
        ) : Inspection
    }

    /** The call's arguments before 'cwd' is resolved against the workspace. */
    private class ParsedRequest(
        val argv: List<String>,
        val cwd: String?,
        val timeout: Duration,
        /** Whether the program runs in the agent's sandbox when it has one. */
        val contained: Boolean,
        val hosts: List<String>,
        val background: Boolean,
    )
}

/** The directories that the steps of [call] work in. */
private fun directories(call: PlannedCall): List<Path> = (listOf(call.cwd) + call.steps.map { it.cwd }).distinct()

/** What the model is told of a run in [sandbox] whose proxy [refused] hosts, and that may have [failed] for its bounds. */
private fun sandboxNote(sandbox: Sandbox?, refused: List<String>, failed: Boolean): String {
    sandbox ?: return ""
    val notes = mutableListOf<String>()
    if (refused.isNotEmpty()) {
        val hosts = refused.joinToString(", ") { "\"$it\"" }
        notes += "The sandbox's proxy refused ${refused.joinToString()}: call again with \"network\": [$hosts] to " +
            "ask for them."
    }
    if (failed) {
        notes += "It ran in a sandbox that lets it write only in your workspace, the conversation's directory, " +
            "trusted folders and temp, and reach the network only through a proxy, which programs that ignore " +
            "HTTP_PROXY, such as ssh or database clients, cannot use." +
            if (sandbox.strict) "" else " If it failed for that, call again with \"sandbox\": false."
    }
    return notes.joinToString("") { "\n[$it]" }
}

/** The steps of [call] as an approver is shown them, none when it runs its own program. */
private fun segments(call: PlannedCall): List<CommandSegment> {
    if (!call.segmented) return emptyList()
    return call.steps.map { step ->
        val run = step.run
        if (run == null) {
            CommandSegment(step.words, step.cwd, null, step.joint, changesDirectory = true, readOnly = true)
        } else {
            CommandSegment(
                run.argv,
                run.cwd,
                run.executable,
                step.joint,
                opaque = run.opaque,
                readOnly = run.readOnly,
                untrusted = !run.vetted,
                environment = run.environment,
                rule = run.rule,
            )
        }
    }
}

private fun JsonObject.optional(name: String) = this[name]?.takeUnless { it == JsonNull }

/** The kinds of turn whose operator actor makes them an operator's. */
private val OPERATOR_KINDS = setOf(TurnKind.MESSAGE, TurnKind.JOB, TurnKind.DELEGATED)

private val MONITOR_FIELDS = setOf("filter", "batchSeconds", "intervalSeconds", "maxLifetimeSeconds")

/** The name of the tool that runs programs. */
internal const val EXEC_TOOL: String = "command.exec"
