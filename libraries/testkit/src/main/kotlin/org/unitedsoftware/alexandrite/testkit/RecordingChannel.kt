package org.unitedsoftware.alexandrite.testkit

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.channel.TrustOffer
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import org.unitedsoftware.alexandrite.testkit.plugin.ScriptedDeliveries

/**
 * A channel of the instance [instance] that records what the agent shows and sends, answers approval requests as the
 * test scripts, and submits what a test receives through [submitter].
 *
 * A call that breaks the rules of [Channel] or [ReplySink] throws an [IllegalStateException], and stops and fails the
 * [PluginHarness] run that holds the channel. Once its instance stops, it ignores previews and answers each delivery
 * with [STOPPED].
 */
public class RecordingChannel(
    public val instance: ChannelInstanceId = TEST_INSTANCE,
    /** The users who are operators of the instance. */
    operators: Set<String> = emptySet(),
    /** How many characters one platform message holds. */
    private val partLength: Int = DEFAULT_PART_LENGTH,
    private val submitter: TurnSubmitter? = null,
    /** The users whom the instance's config trusts. */
    trusted: Set<String> = emptySet(),
) : Channel {
    private val operators = operators.toSet()
    private val trusted = trusted.toSet()
    private val lock = Any()
    private val log = MutableStateFlow<List<Event>>(emptyList())

    /** The replies by their turn and the background run whose activity alone they show, null for the turn's own. */
    private val opened = LinkedHashMap<Pair<TurnId, RunId?>, RecordedReply>()
    private val capabilities = HashMap<ChatAddress?, ChannelCapabilities>()
    private val deliveries = ScriptedDeliveries()
    private val violationList = mutableListOf<String>()
    private var sentParts = 0
    private var inbound = 0
    private val approvalAnswers = ArrayDeque<ApprovalAnswer>()
    private val offerAnswers = ArrayDeque<ApprovalAnswer>()
    private var approvalHold: CompletableDeferred<Unit>? = null

    /** The answer of each approval request that no scripted answer is left for. */
    @Volatile
    public var defaultApproval: ApprovalAnswer = NO_APPROVER

    /** The answer of each trust offer that no scripted answer is left for. */
    @Volatile
    public var defaultOffer: ApprovalAnswer = NO_APPROVER

    /** Whether a user who is no operator may run every command, which operators alone may when false. */
    @Volatile
    public var guestsMayRun: Boolean = true

    @Volatile
    internal var stopped: Boolean = false

    /** Called on each violation the channel sees. */
    @Volatile
    internal var onProblem: (() -> Unit)? = null

    init {
        require(partLength > 0) { "A platform message holds at least one character, was $partLength." }
    }

    /** What happened on the channel, in order. */
    public val events: List<Event> get() = log.value

    /** The replies the agent opened, in order. */
    public val replies: List<RecordedReply> get() = synchronized(lock) { opened.values.toList() }

    public val sent: List<Event.Sent> get() = events.filterIsInstance<Event.Sent>()

    public val violations: List<String> get() = synchronized(lock) { violationList.toList() }

    /** The approval requests the agent made, in order. */
    public val approvals: List<ApprovalRequest>
        get() = events.filterIsInstance<Event.ApprovalAsked>().map { it.request }

    /** The trust offers the agent made, in order. */
    public val offers: List<TrustOffer>
        get() = events.filterIsInstance<Event.TrustOffered>().map { it.offer }

    /** The reply opened for [turn], or for the activity of its background [run]. */
    public fun reply(turn: TurnId, run: RunId? = null): RecordedReply = synchronized(lock) { opened[turn to run] }
        ?: throw NoSuchElementException("No reply of turn $turn${run?.let { " for run $it" }.orEmpty()} was opened.")

    /** Suspends until a reply is opened for [turn], and returns it. */
    public suspend fun awaitReply(turn: TurnId): RecordedReply {
        awaitEvent { it is Event.Opened && it.request.turn.id == turn && it.request.run == null }
        return reply(turn)
    }

    /** Suspends until an event that [predicate] accepts is recorded, and returns the first such event. */
    public suspend fun awaitEvent(predicate: (Event) -> Boolean): Event =
        log.mapNotNull { events -> events.firstOrNull(predicate) }.first()

    /** What the channel can do in [chat], or in every chat that has none of its own when [chat] is null. */
    public fun scriptCapabilities(capabilities: ChannelCapabilities, chat: ChatAddress? = null) {
        synchronized(lock) { this.capabilities[chat] = capabilities }
    }

    /**
     * Queues the deliveries of the next replies and sends to [chat], or to any chat when it is null. An unscripted
     * delivery reaches the chat as one platform message per part.
     */
    public fun scriptDeliveries(vararg deliveries: Delivery, chat: ChatAddress? = null) {
        synchronized(lock) { this.deliveries.script(deliveries, chat) }
    }

    /** Answers the next approval requests with [answers], in order, once each is let through. */
    public fun scriptApprovals(vararg answers: ApprovalAnswer) {
        synchronized(lock) { approvalAnswers.addAll(answers) }
    }

    /** Answers the next trust offers with [answers], in order. */
    public fun scriptOffers(vararg answers: ApprovalAnswer) {
        synchronized(lock) { offerAnswers.addAll(answers) }
    }

    /** Suspends until the agent made [count] trust offers, and returns them. */
    public suspend fun awaitOffers(count: Int): List<TrustOffer> =
        log.mapNotNull { events -> events.filterIsInstance<Event.TrustOffered>().takeIf { it.size >= count } }
            .first()
            .take(count)
            .map { it.offer }

    /** Makes the approval requests and trust offers from now on wait for [releaseApprovals] before they take their answers. */
    public fun holdApprovals() {
        synchronized(lock) { if (approvalHold == null) approvalHold = CompletableDeferred() }
    }

    /** Lets the waiting approval requests and every later one take their answers. */
    public fun releaseApprovals() {
        synchronized(lock) {
            approvalHold?.complete(Unit)
            approvalHold = null
        }
    }

    /** Suspends until the agent made [count] approval requests, and returns them. */
    public suspend fun awaitApprovals(count: Int): List<ApprovalRequest> =
        log.mapNotNull { events -> events.filterIsInstance<Event.ApprovalAsked>().takeIf { it.size >= count } }
            .first()
            .take(count)
            .map { it.request }

    public fun chat(chat: String = "chat", thread: String? = null): ChatAddress = ChatAddress(instance, chat, thread)

    /** A human user, an operator when [user] is one of the instance's operators, trusted when the config trusts them. */
    public fun user(user: String = "guest"): ChatUser = testUser(user, user in operators, instance, user in trusted)

    /** A message of [sender] in [chat], numbered `in-<n>`, changed by [block]. */
    public fun message(
        text: String,
        chat: ChatAddress = chat(),
        sender: ChatUser = user(),
        block: IncomingMessage.Builder.() -> Unit = {},
    ): IncomingMessage = testMessage(text, chat, sender, "in-${synchronized(lock) { ++inbound }}", block)

    /**
     * An invocation of the command [name] in [chat], carried by a message numbered `in-<n>`, that replies to a message
     * of [replyTo] and names [mentions].
     */
    public fun command(
        name: String,
        arguments: String = "",
        chat: ChatAddress = chat(),
        issuer: ChatUser = user(),
        replyTo: ChatUser? = null,
        mentions: List<ChatUser> = emptyList(),
    ): CommandInvocation = carried(name, arguments, chat, issuer, replyTo, mentions).invocation

    /** Submits a message of [sender] in [chat], changed by [block]. */
    public fun receive(
        text: String,
        chat: ChatAddress = chat(),
        sender: ChatUser = user(),
        block: IncomingMessage.Builder.() -> Unit = {},
    ): Admission = submit(Submission.Message(message(text, chat, sender, block)))

    /**
     * Submits an invocation of the command [name] with the message `/<name> <arguments>` that carried it, replying to a
     * message of [replyTo] and naming [mentions].
     */
    public fun receiveCommand(
        name: String,
        arguments: String = "",
        chat: ChatAddress = chat(),
        issuer: ChatUser = user(),
        replyTo: ChatUser? = null,
        mentions: List<ChatUser> = emptyList(),
    ): Admission = submit(carried(name, arguments, chat, issuer, replyTo, mentions))

    /** Records [submission] as received and submits it. */
    public fun submit(submission: Submission): Admission {
        require(submission.chat.instance == instance) {
            "Recording channel $instance cannot submit a submission of ${submission.chat}."
        }
        val target = checkNotNull(submitter) {
            "Recording channel $instance has no TurnSubmitter: run the agent, or give the harness a " +
                "RecordingTurnSubmitter."
        }
        synchronized(lock) { record(Event.Received(submission)) }
        return target.submit(submission)
    }

    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = synchronized(lock) {
        checkChat(chat, "a question for the capabilities")
        capabilities[chat] ?: capabilities[null] ?: DEFAULT_CAPABILITIES
    }

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int = synchronized(lock) {
        checkChat(chat, "a question for the parts needed")
        parts(text)
    }

    override suspend fun openReply(request: ReplyRequest): ReplySink {
        val turn = request.turn
        val reply = synchronized(lock) {
            violation(
                when {
                    turn.chat.instance != instance -> "a reply of turn ${turn.id} opened for ${turn.chat}."

                    turn.replyTarget != ReplyTarget.CHAT && request.run?.run != turn.lineage?.run ->
                        "a reply of turn ${turn.id}, which replies to its caller."

                    (turn.id to request.run?.run) in opened -> "a second reply of turn ${turn.id}."

                    else -> null
                },
            )
            record(Event.Opened(request))
            RecordedReply(request, this).also { opened[turn.id to request.run?.run] = it }
        }
        return Sink(reply)
    }

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = synchronized(lock) {
        checkChat(chat, "a message sent")
        val delivery = if (stopped) STOPPED else delivery(chat, message, replying = false)
        record(Event.Sent(chat, message, delivery))
        delivery
    }

    /** Records [request] and answers it once approvals are not held, with the next scripted answer or the default. */
    override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer {
        val hold = synchronized(lock) {
            if (request.turn.chat.instance != ChatAddress.AGENTS) checkChat(request.turn.chat, "an approval request")
            record(Event.ApprovalAsked(request))
            approvalHold
        }
        try {
            hold?.await()
        } catch (e: CancellationException) {
            synchronized(lock) { record(Event.ApprovalWithdrawn(request.id)) }
            throw e
        }
        return synchronized(lock) {
            val answer = approvalAnswers.removeFirstOrNull() ?: defaultApproval
            record(Event.ApprovalAnswered(request.id, answer))
            answer
        }
    }

    /** Records [offer] and answers it once approvals are not held, with the next scripted answer or the default. */
    override suspend fun offerTrust(offer: TrustOffer): ApprovalAnswer {
        val hold = synchronized(lock) {
            record(Event.TrustOffered(offer))
            approvalHold
        }
        hold?.await()
        return synchronized(lock) {
            val answer = offerAnswers.removeFirstOrNull() ?: defaultOffer
            record(Event.OfferAnswered(offer.id, answer))
            answer
        }
    }

    override fun mayRun(chat: ChatAddress, issuer: ChatUser, command: String, withArguments: Boolean): Boolean =
        guestsMayRun || issuer.isOperator

    /** Whether [user] is one of the instance's operators. */
    override suspend fun isOperator(chat: ChatAddress, user: UserAddress): Boolean =
        user.instance == instance && user.user in operators

    internal fun <T> read(block: () -> T): T = synchronized(lock, block)

    private fun carried(
        name: String,
        arguments: String,
        chat: ChatAddress,
        issuer: ChatUser,
        replyTo: ChatUser?,
        mentions: List<ChatUser>,
    ): Submission.Command {
        val message = message("/$name $arguments".trimEnd(), chat, issuer)
        val invocation =
            testCommand(name, arguments, chat, issuer, message.ref, message.chatInfo.kind, replyTo, mentions)
        return Submission.Command(invocation, message)
    }

    private fun checkChat(chat: ChatAddress, what: String) {
        violation("$what for $chat, a chat of another instance.".takeIf { chat.instance != instance })
    }

    private fun parts(text: String): Int = maxOf(1, (text.length + partLength - 1) / partLength)

    /** How [message] reaches [chat], a reply of a turn when [replying]. */
    private fun delivery(chat: ChatAddress, message: OutboundMessage, replying: Boolean): Delivery {
        deliveries.next(chat)?.let { return it }
        val parts = parts(message.text)
        val limit = (capabilities[chat] ?: capabilities[null] ?: DEFAULT_CAPABILITIES).maxPartsPerReply
        if (replying && limit != null && parts > limit) {
            return Delivery.NotDelivered(DeliveryFailure.TOO_LONG, "$parts parts exceed the limit of $limit.")
        }
        return Delivery.Delivered(List(parts) { ChannelMessageRef(chat, "out-${++sentParts}") })
    }

    /** Records [problem] and throws it, unless it is null. */
    private fun violation(problem: String?) {
        if (problem == null) return
        val message = "Recording channel $instance saw $problem"
        violationList += message
        onProblem?.invoke()
        throw IllegalStateException(message)
    }

    /** Appends [event] to the log, under the lock so that the log keeps the order of the calls. */
    private fun record(event: Event) {
        log.update { it + event }
    }

    private inner class Sink(private val reply: RecordedReply) : ReplySink {
        private val turn = reply.turn.id

        override suspend fun preview(segment: Int, text: String) {
            synchronized(lock) {
                violation(
                    when {
                        reply.end != null -> "a preview of turn $turn after its reply ended."

                        segment < 0 -> "a preview of turn $turn in segment $segment."

                        segment < reply.segment ->
                            "a preview of turn $turn in segment $segment after segment " +
                                "${reply.segment} froze it."

                        else -> null
                    },
                )
                if (stopped) return
                reply.segment = segment
                reply.previewed += RecordedReply.Preview(segment, text)
                record(Event.Previewed(turn, segment, text))
            }
        }

        override suspend fun startSegment(segment: Int) {
            synchronized(lock) {
                violation(
                    when {
                        reply.end != null -> "segment $segment of turn $turn started after its reply ended."

                        segment < reply.segment ->
                            "segment $segment of turn $turn started after segment ${reply.segment}."

                        else -> null
                    },
                )
                if (stopped) return
                reply.segment = segment
                reply.started += segment
            }
        }

        override suspend fun event(event: TurnEvent) {
            synchronized(lock) {
                violation("a turn event of turn $turn after its reply ended.".takeIf { reply.end != null })
                if (stopped) return
                record(Event.Activity(turn, event))
            }
        }

        override suspend fun complete(message: OutboundMessage): Delivery = synchronized(lock) {
            checkOpen("completed")
            val delivery = if (stopped) STOPPED else delivery(reply.turn.chat, message, replying = true)
            reply.end = Event.Completed(turn, message, delivery).also(::record)
            delivery
        }

        override suspend fun abandon(end: ReplyEnd, vouched: Boolean) {
            synchronized(lock) {
                checkOpen("abandoned")
                reply.end = Event.Abandoned(turn, end).also(::record)
            }
        }

        private fun checkOpen(how: String) {
            violation("the reply of turn $turn $how after it ended.".takeIf { reply.end != null })
        }
    }

    /** Something that happened on a [RecordingChannel]. */
    @SubclassOptInRequired(InternalAlexandriteApi::class)
    public interface Event {
        @Poko
        public class Opened(public val request: ReplyRequest) : Event

        @Poko
        public class Previewed(public val turn: TurnId, public val segment: Int, public val text: String) : Event

        @Poko
        public class Completed(
            public val turn: TurnId,
            public val message: OutboundMessage,
            public val delivery: Delivery,
        ) : Event

        @Poko
        public class Abandoned(public val turn: TurnId, public val end: ReplyEnd) : Event

        @Poko
        public class Activity(public val turn: TurnId, public val event: TurnEvent) : Event

        @Poko
        public class Sent(
            public val chat: ChatAddress,
            public val message: OutboundMessage,
            public val delivery: Delivery,
        ) : Event

        /** A submission the test made through the channel. */
        @Poko
        public class Received(public val submission: Submission) : Event

        @Poko
        public class ApprovalAsked(public val request: ApprovalRequest) : Event

        /** The request [id] got [answer]. */
        @Poko
        public class ApprovalAnswered(public val id: String, public val answer: ApprovalAnswer) : Event

        /** The agent withdrew the request [id] before it was answered. */
        @Poko
        public class ApprovalWithdrawn(public val id: String) : Event

        @Poko
        public class TrustOffered(public val offer: TrustOffer) : Event

        /** The trust offer [id] got [answer]. */
        @Poko
        public class OfferAnswered(public val id: String, public val answer: ApprovalAnswer) : Event
    }

    public companion object {
        public const val DEFAULT_PART_LENGTH: Int = 4096

        /** The delivery of every reply and message once the instance stopped. */
        public val STOPPED: Delivery = Delivery.NotDelivered(DeliveryFailure.TRANSIENT)

        /** What an approval request gets when the test scripted no answer. */
        public val NO_APPROVER: ApprovalAnswer = ApprovalAnswer.Unavailable("no approver answers in this test")

        /** Streaming with the final message in place of the previews, proactive, and in plain text or Markdown. */
        public val DEFAULT_CAPABILITIES: ChannelCapabilities = ChannelCapabilities.builder()
            .streaming(true)
            .finalReplacesPreview(true)
            .proactive(true)
            .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
            .build()
    }
}

/** The reply of one turn on a [RecordingChannel]. */
public class RecordedReply internal constructor(
    public val request: ReplyRequest,
    private val channel: RecordingChannel,
) {
    internal var segment = 0
    internal val previewed = mutableListOf<Preview>()
    internal val started = mutableListOf<Int>()
    internal var end: RecordingChannel.Event? = null

    public val turn: TurnInfo get() = request.turn

    public val previews: List<Preview> get() = channel.read { previewed.toList() }

    /** The last preview of each segment. */
    public val segments: Map<Int, String> get() = previews.associate { it.segment to it.text }

    /** The segments the agent started, in order. */
    public val startedSegments: List<Int> get() = channel.read { started.toList() }

    /** The final message, null until the reply completes. */
    public val completed: OutboundMessage? get() = channel.read { (end as? RecordingChannel.Event.Completed)?.message }

    public val delivery: Delivery? get() = channel.read { (end as? RecordingChannel.Event.Completed)?.delivery }

    /** Why the reply ended without a final message, null unless it did. */
    public val abandoned: ReplyEnd? get() = channel.read { (end as? RecordingChannel.Event.Abandoned)?.end }

    public val ended: Boolean get() = channel.read { end != null }

    /** The turn events shown so far, in order. */
    public val activity: List<TurnEvent>
        get() = channel.events.filterIsInstance<RecordingChannel.Event.Activity>()
            .filter { it.turn == turn.id }
            .map { it.event }

    /** Suspends until the reply completes or is abandoned, and returns it. */
    public suspend fun awaitEnd(): RecordedReply {
        val id = turn.id
        channel.awaitEvent {
            (it is RecordingChannel.Event.Completed && it.turn == id) ||
                (it is RecordingChannel.Event.Abandoned && it.turn == id)
        }
        return this
    }

    @Poko
    public class Preview(public val segment: Int, public val text: String)
}
