package org.unitedsoftware.alexandrite.agent.worker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.control.CommandLanes
import org.unitedsoftware.alexandrite.agent.control.CommandRegistry
import org.unitedsoftware.alexandrite.agent.delegation.DELEGATE_TOOL
import org.unitedsoftware.alexandrite.agent.delegation.DelegatedRuns
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.RunWay
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import java.time.Clock
import java.time.Instant

@Singleton
@Binds(TurnSubmitter::class)
internal class AgentTurnSubmitter(
    private val intakes: ChatIntakes,
    private val workers: TurnWorkers,
    private val commands: CommandRegistry,
    private val lanes: CommandLanes,
    private val runs: DelegatedRuns,
    private val clock: Clock,
) : TurnSubmitter {
    override fun submit(submission: Submission): Admission {
        if (!workers.accepting) return Admission.Refused(RefusalReason.SHUTTING_DOWN)
        val now = clock.instant()
        val turn = newTurnId(now)
        return try {
            when (submission) {
                is Submission.Message -> intakes.dispatch(turn, submission.message, submission.capacity, now)
                is Submission.Command -> command(turn, submission, now)
                is Submission.ToRun -> toRun(turn, submission, now)
            }
        } catch (e: Exception) {
            logger.error("Cannot take the submission {} of {}", turn, submission.chat, e)
            Admission.Accepted(AgentTicket.ended(turn, TurnOutcome.Failed("The agent could not take it: $e")))
        }
    }

    private fun toRun(turn: TurnId, submission: Submission.ToRun, now: Instant): Admission = when (submission.way) {
        RunWay.FOLLOW_UP -> runs.post(turn, submission.run, submission.message, now)
        RunWay.RESUME, RunWay.FORK -> goOn(turn, submission, now)
        else -> Admission.Refused(RefusalReason.UNSUPPORTED)
    }

    /**
     * Queues a turn of the chat's agent that, for the operator who sent [submission], delegates a new run going on
     * from the ended run, with the message as its task, the way its model would resume the run.
     */
    private fun goOn(turn: TurnId, submission: Submission.ToRun, now: Instant): Admission {
        val message = submission.message
        if (!message.sender.isOperator) return Admission.Refused(RefusalReason.NOT_PERMITTED)
        if (runs.isLive(submission.run)) return Admission.Refused(RefusalReason.RUN_RUNNING)
        // The profile, where it is known, names the call as the model's would; the run's record tells it otherwise.
        val profile = runs.profileOf(submission.run)?.let { mapOf("profile" to JsonPrimitive("$it")) }.orEmpty()
        val arguments = JsonObject(
            profile + mapOf("resume" to JsonPrimitive("${submission.run}"), "task" to JsonPrimitive(message.text)),
        )
        val call = ToolCallPart(ToolCallId("operator-$turn"), DELEGATE_TOOL, "$arguments")
        return intakes.dispatch(turn, message, submission.capacity, now, call)
    }

    private fun command(turn: TurnId, command: Submission.Command, now: Instant): Admission {
        val handler = commands.handler(command.invocation.name)
        val carrier = command.message
        return when {
            handler != null && !lanes.mayRun(command.invocation) -> lanes.refused(turn, command.invocation)
            handler != null -> lanes.submit(turn, command, handler, now)
            carrier != null -> intakes.dispatch(turn, carrier, command.capacity, now)
            else -> lanes.unknown(turn, command.invocation)
        }
    }
}

private val logger = LoggerFactory.getLogger(AgentTurnSubmitter::class.java)
