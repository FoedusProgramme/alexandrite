package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry

/**
 * Takes the user input of channels, where submit order is dispatch order per chat and turn order per agent and chat.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnSubmitter {
    /** Queues [submission] and returns at once. */
    public fun submit(submission: Submission): Admission
}

/** User input that a channel submits. */
public sealed interface Submission {
    public val chat: ChatAddress
    public val capacity: Capacity

    @Poko
    public class Message(public val message: IncomingMessage, override val capacity: Capacity = Capacity.COUNTED) :
        Submission {
        override val chat: ChatAddress get() = message.chat
    }

    /** A command invocation, which becomes a turn of kind COMMAND. */
    @Poko
    public class Command(
        public val invocation: CommandInvocation,
        /** The message that carried the invocation, null when none did. */
        public val message: IncomingMessage? = null,
        override val capacity: Capacity = Capacity.COUNTED,
    ) : Submission {
        init {
            require(message == null || message.ref == invocation.trigger) {
                "The message that carries a command invocation is its trigger, was ${message?.ref} for " +
                    "${invocation.trigger}."
            }
            require(message == null || message.sender.address == invocation.issuer.address) {
                "The message that carries a command invocation is the issuer's, was ${message?.sender?.address} " +
                    "for ${invocation.issuer.address}."
            }
        }

        override val chat: ChatAddress get() = invocation.chat
    }

    /**
     * A [message] to the sub-agent run [run], which a person in the chat of the turn that started it writes, the way
     * [way] says. For [RunWay.FOLLOW_UP], its ticket ends as absorbed once the run takes the message in, and as taken
     * back when the run ends first; for [RunWay.RESUME] and [RunWay.FORK], it is the ticket of the chat agent's turn
     * that starts the new run and ends with its report.
     */
    @Poko
    public class ToRun(
        public val run: RunId,
        public val message: IncomingMessage,
        public val way: RunWay = RunWay.FOLLOW_UP,
        override val capacity: Capacity = Capacity.COUNTED,
    ) : Submission {
        override val chat: ChatAddress get() = message.chat
    }
}

/** How a sub-agent run takes a message that a person sends it. */
@JvmInline
@Serializable
public value class RunWay internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The running run takes the message in after its current calls, as a turn takes its follow-ups. */
        public val FOLLOW_UP: RunWay = RunWay("follow_up")

        /**
         * The run, which ended, goes on with the message as its next task: a turn of the chat's agent, which an
         * operator alone may ask for, delegates a new run that goes on from the run's conversation, as when its model
         * resumes a run, and ends with the new run's report, which the agent reads in its next turn.
         */
        public val RESUME: RunWay = RunWay("resume")

        /**
         * A new run starts from the conversation of the run, which ended, with the message as its task, as for
         * [RESUME]; the agent does the same, and a channel shows the new run apart from the one it forks.
         */
        public val FORK: RunWay = RunWay("fork")

        /** The values this version knows. */
        public val entries: List<RunWay> = listOf(FOLLOW_UP, RESUME, FORK)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): RunWay = RunWay(id)
    }
}

/** Whether a submission counts against its chat's queue bound. */
public enum class Capacity {
    /** Admitted while the queue is below its bound. */
    COUNTED,

    /** Admitted past the bound, as replays and an operator's commands are. */
    EXEMPT,
}

/** What the agent made of a submission. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface Admission {
    /** Queued as the turn of [ticket]. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Accepted(public val ticket: TurnTicket) : Admission

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Refused(
        public val reason: RefusalReason,
        /** The bound of the full queue, null when the queue was not full. */
        public val queueCapacity: Int? = null,
    ) : Admission {
        init {
            require(queueCapacity == null || queueCapacity > 0) { "A queue capacity is positive, was $queueCapacity." }
        }
    }
}

/** Why the agent refused a submission. */
@JvmInline
@Serializable
public value class RefusalReason internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** The chat's queue holds as many turns as it may. */
        public val QUEUE_FULL: RefusalReason = RefusalReason("queue_full")

        /** The agent is closing. */
        public val SHUTTING_DOWN: RefusalReason = RefusalReason("shutting_down")

        /** The chat's channel instance is not configured. */
        public val UNKNOWN_CHAT: RefusalReason = RefusalReason("unknown_chat")

        /** No agent serves the chat. */
        public val NO_AGENT: RefusalReason = RefusalReason("no_agent")

        /** No sub-agent run of the submission's chat has its id. */
        public val UNKNOWN_RUN: RefusalReason = RefusalReason("unknown_run")

        /** The sub-agent run ended and takes no more messages. */
        public val RUN_ENDED: RefusalReason = RefusalReason("run_ended")

        /** The sender may not write to the sub-agent run: it is neither an operator nor the person it works for. */
        public val NOT_PERMITTED: RefusalReason = RefusalReason("not_permitted")

        /** The sub-agent run goes on, so it takes follow-ups rather than being resumed or forked. */
        public val RUN_RUNNING: RefusalReason = RefusalReason("run_running")

        /** This version does not know what the submission asks for, such as a way of a [RunWay] it does not know. */
        public val UNSUPPORTED: RefusalReason = RefusalReason("unsupported")

        /** The values this version knows. */
        public val entries: List<RefusalReason> = listOf(
            QUEUE_FULL,
            SHUTTING_DOWN,
            UNKNOWN_CHAT,
            NO_AGENT,
            UNKNOWN_RUN,
            RUN_ENDED,
            NOT_PERMITTED,
            RUN_RUNNING,
            UNSUPPORTED,
        )

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): RefusalReason = RefusalReason(id)
    }
}

/** A queued turn. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnTicket {
    public val turn: TurnId

    /** Suspends until the turn ends, which a turn of the same chat must never wait for. */
    public suspend fun outcome(): TurnOutcome

    /** Drops the turn while it is queued or cancels it while it runs, and returns whether it had not ended. */
    public fun cancel(): Boolean
}

/** How a turn ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface TurnOutcome {
    /** Whether the turn left nothing in its history or its chat, so it may be submitted again after a restart. */
    public val replayable: Boolean

    /** The turn ran to its end. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Completed private constructor(
        /** The model's final response, null when the turn ended on a notice or ran a command. */
        public val reply: AssistantEntry?,
        /** How the reply reached each chat it was sent to. */
        public val deliveries: Map<ChatAddress, Delivery>,
    ) : TurnOutcome {
        override val replayable: Boolean get() = false

        public companion object {
            public operator fun invoke(
                reply: AssistantEntry?,
                deliveries: Map<ChatAddress, Delivery> = emptyMap(),
            ): Completed = Completed(reply, deliveries.toMap())
        }
    }

    /** The message joined the running turn [into] as a follow-up. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Absorbed(public val into: TurnId) : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    /**
     * The model answered with nothing usable (blank or refused), so the turn's input was taken back and the chat got a
     * notice.
     */
    @OptIn(InternalAlexandriteApi::class)
    public data object TakenBack : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    @OptIn(InternalAlexandriteApi::class)
    public data object Cancelled : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class Failed(public val message: String) : TurnOutcome {
        override val replayable: Boolean get() = false
    }

    /** The runtime stopped before the turn started, or cut it off. */
    @OptIn(InternalAlexandriteApi::class)
    @Poko
    public class ShutDown(override val replayable: Boolean) : TurnOutcome
}
