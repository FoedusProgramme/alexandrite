package org.unitedsoftware.alexandrite.agent.worker

import kotlinx.coroutines.CancellationException
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.routing.ChatRouter
import org.unitedsoftware.alexandrite.agent.routing.Resolution
import org.unitedsoftware.alexandrite.agent.turn.TurnPlan
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import java.time.Instant

/**
 * One intake per chat, created on demand and removed once idle, which hands the chat's messages to the workers in
 * submit order, holding them while a command of the chat gates it.
 */
@Singleton
internal class ChatIntakes(
    settings: AgentSettings,
    private val lock: SubmitLock,
    private val workers: TurnWorkers,
    private val router: ChatRouter,
) {
    private val perKey = settings.queue.perKey
    private val intakes = HashMap<ChatAddress, Intake>()

    /** The counted messages held for each key. */
    private val heldAt = HashMap<AgentChatKey, Int>()

    val chats: Set<ChatAddress> get() = lock.locked { intakes.keys.toSet() }

    fun dispatch(
        turn: TurnId,
        message: IncomingMessage,
        capacity: Capacity,
        queuedAt: Instant,
        /** The call that the turn makes for its operator first, null when it makes none. */
        operatorCall: ToolCallPart? = null,
    ): Admission = lock.locked {
        val chat = message.chat
        val ticket = AgentTicket(turn) { cancel(it, TurnCancelled(null)) }
        val held = Held(turn, message, capacity, queuedAt, ticket, operatorCall)
        val route = router.resolve(chat)
        when {
            !workers.accepting -> Admission.Refused(RefusalReason.SHUTTING_DOWN)
            route !is Resolution.Served -> Admission.Refused(refusal(route))
            chat !in intakes -> workers.submit(route.key, turn(held, route.key), heldAt[route.key] ?: 0)
            else -> hold(held, route.key)
        }
    }

    /** Holds the chat's messages until [command] lets them go. */
    fun gate(chat: ChatAddress, command: TurnId) {
        lock.locked {
            intakes.getOrPut(chat) { Intake(chat) }.gates += command
        }
    }

    /** Lets the messages that [command] held go on. */
    fun release(chat: ChatAddress, command: TurnId) {
        lock.locked {
            val intake = intakes[chat] ?: return@locked
            if (intake.gates.remove(command)) flush(intake)
        }
    }

    /** Drops [turn] while it is held or queued, cancels it while it runs, and returns whether it was found. */
    fun cancel(turn: TurnId, cause: CancellationException): Boolean = lock.locked {
        for (intake in intakes.values) {
            val held = intake.held.firstOrNull { it.turn == turn } ?: continue
            intake.held.remove(held)
            uncount(held)
            held.ticket.complete(notStarted(cause))
            reclaim(intake)
            return@locked true
        }
        workers.cancel(turn, cause)
    }

    /** Ends every held message as never started. */
    fun shutDown() {
        lock.locked {
            for (intake in intakes.values) {
                intake.held.forEach { it.ticket.complete(TurnOutcome.ShutDown(replayable = true)) }
            }
            intakes.clear()
            heldAt.clear()
        }
    }

    private fun hold(held: Held, key: AgentChatKey): Admission {
        val chat = held.message.chat
        if (held.capacity == Capacity.COUNTED) {
            if (workers.waiting(key) + (heldAt[key] ?: 0) >= perKey) {
                return Admission.Refused(RefusalReason.QUEUE_FULL, perKey)
            }
            held.countedAt = key
            heldAt.merge(key, 1, Int::plus)
        }
        intakes.getOrPut(chat) { Intake(chat) }.held.addLast(held)
        return Admission.Accepted(held.ticket)
    }

    /** Hands the held messages of [intake] to the workers while nothing holds them. */
    private fun flush(intake: Intake) {
        while (intake.gates.isEmpty() && intake.held.isNotEmpty()) {
            val route = router.resolve(intake.chat)
            val held = intake.held.removeFirst()
            uncount(held)
            if (route is Resolution.Served) {
                workers.enqueue(route.key, turn(held, route.key))
            } else {
                held.ticket.complete(TurnOutcome.Failed("No agent serves ${intake.chat}."))
            }
        }
        reclaim(intake)
    }

    private fun uncount(held: Held) {
        val key = held.countedAt ?: return
        heldAt.compute(key) { _, count -> count?.minus(1)?.takeIf { it > 0 } }
    }

    private fun reclaim(intake: Intake) {
        if (intake.held.isEmpty() && intake.gates.isEmpty()) intakes.remove(intake.chat)
    }

    private fun turn(held: Held, key: AgentChatKey): WorkerJob.Turn {
        val message = held.message
        val plan = TurnPlan(
            id = held.turn,
            key = key,
            origin = message.chat,
            kind = TurnKind.MESSAGE,
            input = TurnSource.FromMessage(message),
            actor = message.sender,
            replyTarget = ReplyTarget.CHAT,
            trigger = message.ref,
            queuedAt = held.queuedAt,
            operatorCall = held.operatorCall,
        )
        return WorkerJob.Turn(plan, held.ticket, held.capacity)
    }
}

private class Intake(val chat: ChatAddress) {
    val held = ArrayDeque<Held>()

    /** The commands that gate the chat. */
    val gates = LinkedHashSet<TurnId>()
}

private class Held(
    val turn: TurnId,
    val message: IncomingMessage,
    val capacity: Capacity,
    val queuedAt: Instant,
    val ticket: AgentTicket,
    val operatorCall: ToolCallPart?,
) {
    /** The key the message counts against, null for one that is not counted. */
    var countedAt: AgentChatKey? = null
}

/** Why a chat that [route] serves with no agent is refused. */
internal fun refusal(route: Resolution): RefusalReason =
    if (route == Resolution.Unknown) RefusalReason.UNKNOWN_CHAT else RefusalReason.NO_AGENT
