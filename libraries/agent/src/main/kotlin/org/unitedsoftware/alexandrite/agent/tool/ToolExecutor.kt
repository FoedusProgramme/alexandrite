package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.cancel.TurnShutdown
import org.unitedsoftware.alexandrite.agent.cancel.turnCause
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.job.Detached
import org.unitedsoftware.alexandrite.agent.job.Detaching
import org.unitedsoftware.alexandrite.agent.job.Jobs
import org.unitedsoftware.alexandrite.agent.job.backgroundResult
import org.unitedsoftware.alexandrite.agent.permission.MandateGrants
import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.sandbox.CallSandboxes
import org.unitedsoftware.alexandrite.agent.trust.TaintLedger
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.internal.text.fittingLength
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The result the model reads of a call cancelled while it ran. */
internal const val CANCELLED_RESULT: String = "Cancelled (outcome unknown)"

/** The characters of a tool result's text that the model reads. */
internal const val MAX_RESULT_CHARS: Int = 200_000

/** How a tool call ended. */
internal class ToolCallEnd(
    /** Not stored yet, with its media inline. */
    val result: ToolResultEntry,
    /** What the chat is told of the call that did not run, null for nothing. */
    val notice: String?,
    /** The paths that the call reported as touched. */
    val touched: List<Path> = emptyList(),
    /** The trusted instructions that the call loads after its round. */
    val loads: List<UserEntry> = emptyList(),
    /** The [touched] paths that may taint the conversation. */
    val tainting: List<Path> = touched,
)

/** Runs the tool calls of a turn's responses. */
@Singleton
internal class ToolExecutor(
    private val gate: ToolGate,
    settings: AgentSettings,
    private val workspaces: Workspaces,
    private val directories: ConversationDirectories,
    private val jobs: Jobs,
    private val grants: MandateGrants,
    private val sandboxes: CallSandboxes,
    private val ledger: TaintLedger,
) {
    private val shutdownWait = shutdownWait(settings)

    /**
     * How [call] in [turn] ends, where the turn offers [tools], may call those named in [callable], a tool may run
     * for [timeout] unless it declares longer, untrusted content marks [taint] and the turn does its part through
     * [calls].
     */
    suspend fun execute(
        turn: TurnInfo,
        call: ToolCallPart,
        tools: Map<String, Tool>,
        timeout: Duration,
        callable: Set<String>,
        taint: TurnTaint,
        calls: TurnCalls,
        trigger: ChannelMessageRef?,
    ): ToolCallEnd {
        val tool = tools[call.name]
            ?: return ended(call, "No tool named '${call.name}' is offered.", NotRunReason.UNKNOWN_TOOL, calls)
        if (call.name !in callable) {
            return ended(call, "This turn may not call '${call.name}'.", NotRunReason.NOT_PERMITTED, calls)
        }
        val arguments = call.parseArguments()
            ?: return ended(call, "The arguments are not a JSON object.", NotRunReason.INVALID_ARGUMENTS, calls)
        val limit = tool.definition.timeout?.let { maxOf(timeout, it + DECLARED_TIMEOUT_GRACE) } ?: timeout
        val reviewing = AgentToolContext(
            turn,
            call.id,
            workspaces,
            directories,
            null,
            taint,
            trigger,
            sandboxes = sandboxes,
        )
        val admission = try {
            gate.admit(GateCall(turn, call, tool, arguments, limit, taint, reviewing, calls))
        } finally {
            reviewing.close()
        }
        return when (admission) {
            is GateResult.Refused -> {
                calls.ended(call.id, ToolCallStatus.NOT_RUN, null)
                ToolCallEnd(textResult(call, admission.text, admission.outcome), admission.notice)
            }

            is GateResult.Admitted -> {
                calls.starting(call.id)
                calls.running(call.id)
                val delegating = calls as? DelegatingTurn
                val context = AgentToolContext(
                    turn,
                    call.id,
                    workspaces,
                    directories,
                    admission.clearance,
                    taint,
                    trigger,
                    delegating,
                    startsInBackground = admission.background && tool !is InTurnTool,
                    granting = { grants.grant(turn, tool) },
                    trust = admission.trust,
                    written = admission.preview.writtenFile,
                    sandboxes = sandboxes,
                )
                admission.untrusted?.let(context::report)
                val result = if (tool is InTurnTool) {
                    inTurn(turn, call, tool, arguments, context)
                } else {
                    runTool(turn, call, tool, arguments, limit, context)
                }
                recordWrites(admission, result.outcome, taint)
                val content = contentOf(tool.definition.activity, admission.preview, result, admission.scratch)
                calls.ended(call.id, statusOf(result.outcome), content)
                ToolCallEnd(result, null, context.touchedPaths, context.loads, context.taintingPaths)
            }
        }
    }

    /** Notes in the ledger the file that the call that [admission] let run wrote, when its turn [taint] is tainted. */
    private suspend fun recordWrites(admission: GateResult.Admitted, outcome: ToolOutcome, taint: TurnTaint) {
        val written = admission.preview.writtenFile ?: return
        val reason = taint.known?.reason ?: return
        if (admission.clearance.kind == ClearanceKind.NONE || outcome != ToolOutcome.Succeeded) return
        ledger.wrote(written, reason)
    }

    private suspend fun runTool(
        turn: TurnInfo,
        call: ToolCallPart,
        tool: Tool,
        arguments: JsonObject,
        timeout: Duration,
        context: AgentToolContext,
    ): ToolResultEntry {
        var detached = false
        val explicit = context.startsInBackground
        try {
            val running = CoroutineScope(currentCoroutineContext().minusKey(Job)).async(
                start = if (explicit) CoroutineStart.LAZY else CoroutineStart.DEFAULT,
            ) { tool.execute(arguments, context) }
            val returned = try {
                val foreground = if (explicit) {
                    Duration.ZERO
                } else if (turn.lineage == null) {
                    minOf(jobs.foreground(turn.agent), timeout)
                } else {
                    timeout
                }
                (if (explicit) null else withTimeoutOrNull(foreground) { running.await() }) ?: run {
                    val job = jobs.detach(
                        Detaching(
                            turn,
                            call,
                            callTitle(tool.definition, arguments),
                            running,
                            context,
                            foreground,
                            timeout,
                        ),
                    )
                    if (job is Detached.Started) {
                        detached = true
                        return backgroundResult(call, job.id)
                    }
                    if (explicit) {
                        job as Detached.Kept
                        stop(running, turn, call, ABANDON_AFTER)
                        return textResult(
                            call,
                            "The command was not started: ${job.why}.",
                            ToolOutcome.NotRun(job.reason),
                        )
                    }
                    withTimeoutOrNull(timeout - foreground) { running.await() }
                }
            } catch (e: CancellationException) {
                if (currentCoroutineContext().isActive) return textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
                stop(running, turn, call, if (turnCause(e) is TurnShutdown) shutdownWait else ABANDON_AFTER)
                throw e
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                logger.warn("Tool {} failed in turn {}: {}", call.name, turn.id, e.toString())
                return failedResult(call, e)
            }
            if (returned == null) {
                logger.warn("Tool {} timed out in turn {} after {}", call.name, turn.id, timeout)
                stop(running, turn, call, ABANDON_AFTER)
                return timedOutResult(call, timeout)
            }
            val outcome = if (returned.isError) ToolOutcome.Failed else ToolOutcome.Succeeded
            return ToolResultEntry(null, call.id, call.name, shaped(returned.content), outcome)
        } finally {
            if (!detached) context.close()
        }
    }

    /** How [call] of [tool] ends, run in the turn's coroutine. */
    private suspend fun inTurn(
        turn: TurnInfo,
        call: ToolCallPart,
        tool: InTurnTool,
        arguments: JsonObject,
        context: AgentToolContext,
    ): ToolResultEntry {
        var detached = false
        try {
            val result = tool.call(arguments, context)
            detached = result.outcome is ToolOutcome.Detached
            return ToolResultEntry(null, call.id, call.name, result.content, result.outcome)
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) return textResult(call, CANCELLED_RESULT, ToolOutcome.Cancelled)
            throw e
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            logger.warn("Tool {} failed in turn {}: {}", call.name, turn.id, e.toString())
            return failedResult(call, e)
        } finally {
            if (!detached) context.close()
        }
    }

    /** Cancels [running] and waits at most [wait] for it to end. */
    private suspend fun stop(running: Job, turn: TurnInfo, call: ToolCallPart, wait: Duration) {
        withContext(NonCancellable) {
            running.cancel()
            if (withTimeoutOrNull(wait) { running.join() } == null) {
                logger.warn(
                    "Tool {} still runs {} after its call {} in turn {} was cancelled: it is abandoned and its " +
                        "result discarded",
                    call.name,
                    wait,
                    call.id,
                    turn.id,
                )
            }
        }
    }

    private suspend fun ended(call: ToolCallPart, text: String, reason: NotRunReason, calls: TurnCalls): ToolCallEnd {
        calls.ended(call.id, ToolCallStatus.NOT_RUN, null)
        return ToolCallEnd(textResult(call, text, ToolOutcome.NotRun(reason)), null)
    }
}

internal fun textResult(call: ToolCallPart, text: String, outcome: ToolOutcome): ToolResultEntry =
    ToolResultEntry(null, call.id, call.name, listOf(TextPart(text)), outcome)

/** The result of [call], whose tool threw [error]. */
internal fun failedResult(call: ToolCallPart, error: Throwable): ToolResultEntry {
    val why = error.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    return textResult(call, "The tool failed: $why", ToolOutcome.Failed)
}

/** The result of [call], whose tool ran past [timeout] and was stopped. */
internal fun timedOutResult(call: ToolCallPart, timeout: Duration): ToolResultEntry =
    textResult(call, "The call timed out after ${timeout.inWholeSeconds} s.", ToolOutcome.Failed)

/** The [ToolCallStatus] a finished call's [outcome] is shown as. */
private fun statusOf(outcome: ToolOutcome): ToolCallStatus = when (outcome) {
    ToolOutcome.Succeeded -> ToolCallStatus.COMPLETED
    is ToolOutcome.Detached -> ToolCallStatus.BACKGROUNDED
    is ToolOutcome.NotRun -> ToolCallStatus.NOT_RUN
    else -> ToolCallStatus.FAILED
}

/**
 * What a finished call of [activity] is shown with: the review's [preview] for an edit, [result]'s tail for a run.
 *
 * A [scratch] edit shows no diff, as its target lies in the turn's own conversation directory.
 */
private fun contentOf(
    activity: ToolActivity,
    preview: ReviewPreview?,
    result: ToolResultEntry,
    scratch: Boolean,
): TurnEvent.Content? = when (activity) {
    ToolActivity.EDIT -> (preview as? ReviewPreview.Diff)
        ?.takeIf { result.outcome == ToolOutcome.Succeeded && !scratch }
        ?.let { TurnEvent.Content.Preview(it) }

    ToolActivity.EXECUTE -> textOf(result.content).takeIf { it.isNotBlank() }
        ?.let { TurnEvent.Content.Output(tailLines(it, OUTPUT_TAIL_LINES)) }

    else -> null
}

/** The text of [parts], its other kinds left out. */
private fun textOf(parts: List<ToolOutputPart>): String =
    parts.filterIsInstance<TextPart>().joinToString("") { it.text }

/** The last [max] lines of [text]. */
private fun tailLines(text: String, max: Int): String {
    val lines = text.lines()
    return if (lines.size <= max) text else lines.takeLast(max).joinToString("\n")
}

/** How many lines of a run's output a [TurnEvent.Content.Output] keeps. */
private const val OUTPUT_TAIL_LINES = 20

/** [content] with its text escaped and cut after [MAX_RESULT_CHARS] characters, where a note says so. */
internal fun shaped(content: List<ToolOutputPart>): List<ToolOutputPart> {
    val escaped = content.map { if (it is TextPart) TextPart(Escaper.HEADERS.text(it.text)) else it }
    val total = escaped.sumOf { (it as? TextPart)?.text?.length ?: 0 }
    if (total <= MAX_RESULT_CHARS) return escaped
    var left = MAX_RESULT_CHARS
    val kept = escaped.mapNotNull { part ->
        if (part !is TextPart) return@mapNotNull part
        val text = part.text
        val end = fittingLength(text, left)
        left = if (end < text.length) 0 else left - end
        if (end == 0) null else TextPart(text.substring(0, end))
    }
    val note = "\n[The result was cut here: it held $total characters, of which the model reads $MAX_RESULT_CHARS.]"
    return kept + TextPart(note)
}

/** How long a stopped tool may take to end before it is abandoned. */
internal val ABANDON_AFTER: Duration = 5.seconds

/** How long a tool stopped at a shutdown may take to end before it is abandoned. */
internal fun shutdownWait(settings: AgentSettings): Duration =
    minOf(ABANDON_AFTER, settings.shutdown.cancelJoinSeconds.seconds / 2)

/** How much longer than its declared timeout a tool may run. */
private val DECLARED_TIMEOUT_GRACE = 5.seconds

private val logger = LoggerFactory.getLogger(ToolExecutor::class.java)
