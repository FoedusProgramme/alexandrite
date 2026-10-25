package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotChats
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyActivity
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart

/**
 * The QQ official bot front end of one channel instance.
 *
 * A message the platform pushes becomes an [org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage] and goes to
 * the agent through the submitter, and what the agent answers goes back through [QqBotSender]. A message in a group
 * cannot be changed after it is sent, so a reply there is one message of its own; a single chat serves the platform's
 * streaming endpoint, so the growing answer is shown in one message that later pieces replace.
 */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "qqbot")
internal class QqBotChannel(
    private val instance: ChannelInstance,
    private val sender: QqBotSender,
    private val approvals: QqBotApprovals,
    private val typing: QqBotTyping,
    private val progress: QqBotProgress,
    private val replies: QqBotReplyRegistry,
    private val rules: QqBotCommandRules,
    private val config: QqBotInstanceConfig,
) : Channel {
    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities =
        if (config.streaming && QqBotChats.kind(chat) == ChatKind.DIRECT) {
            STREAMING_CAPABILITIES
        } else {
            CAPABILITIES
        }
    override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer = approvals.ask(request)

    override fun mayRun(chat: ChatAddress, issuer: ChatUser, command: String, withArguments: Boolean): Boolean =
        rules.mayRun(chat, issuer.isOperator, command, withArguments)

    /** Whether [user] is one of the instance's configured admins, who are operators in every chat of the bot. */
    override suspend fun isOperator(chat: ChatAddress, user: UserAddress): Boolean =
        user.instance == instance.id && user.user in config.admins

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        if (text.isEmpty()) 0 else 1

    override suspend fun openReply(request: ReplyRequest): ReplySink {
        typing.start(request.turn.chat)
        return Sink(request).also { replies.open(request.turn.id, it) }
    }

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = sender.send(chat, message)

    /** The reply of one turn, which streams in a single chat and sends one message in a group. */
    private inner class Sink(private val request: ReplyRequest) :
        ReplySink,
        QqBotWords {
        /**
         * The one streaming message of this reply, which every segment replaces.
         *
         * The platform cannot delete a streaming message, so a segment that started its own message would leave the
         * previous segment's text behind as a stray message. Replacing the same message across segments keeps the
         * conversation to what the answer says.
         */
        private var stream: QqBotStream? = null

        /** The tool calls seen so far, which the streaming message shows until the answer replaces them. */
        private val activities = LinkedHashMap<String, String>()

        /** What the model has said so far, apart from the tool lines the stream shows before it. */
        private var answer: String = ""

        /** The tool lines of a non-streaming chat that wait for their next notice. */
        private val waiting = ArrayList<String>()

        /** When the last notice of this reply was sent, and whether one was sent. */
        private var noticedAt: Long = 0
        private var noticed: Boolean = false

        /** Serializes this reply's stream, answer and detached-run state across its coroutines. */
        private val lock = Mutex()

        /** The background runs whose activity this reply still shows after its own answer. */
        private val detached = LinkedHashMap<RunId, Detached>()

        /** The answer this reply sent while a background run still writes to its stream. */
        private var parentFinal: String? = null

        private val streaming = config.streaming && QqBotChats.kind(request.turn.chat) == ChatKind.DIRECT &&
            request.trigger != null

        override suspend fun detachActivity(run: DelegatedRun): ReplyActivity? = lock.withLock {
            if (progress.isDeferred(request.turn.id) || run.call.value !in activities) return@withLock null
            Detached(run.run).also { detached[run.run] = it }
        }

        /** The activity of a background run, shown after the reply that delegated it ended. */
        private inner class Detached(private val run: RunId) : ReplyActivity {
            private var closed = false

            override suspend fun event(event: TurnEvent) = lock.withLock {
                if (closed) return@withLock
                val started = event as? TurnEvent.ToolCallStarted ?: return@withLock
                if (started.run != run) return@withLock
                val line = activityLine(started)
                val current = stream
                if (streaming && current != null && !progress.isDeferred(request.turn.id)) {
                    if (!current.append("\n\n" + line)) {
                        sender.send(
                            request.turn.chat,
                            OutboundMessage.builder(line, MessageKind.NOTICE).build(),
                        )
                    }
                } else {
                    sender.send(
                        request.turn.chat,
                        OutboundMessage.builder(line, MessageKind.NOTICE).build(),
                    )
                }
            }

            override suspend fun close(status: ToolCallStatus) = lock.withLock {
                if (closed) return@withLock
                closed = true
                detached.remove(run)
                if (detached.isEmpty() && parentFinal != null) {
                    stream?.finishAppended()
                    parentFinal = null
                }
            }
        }

        override suspend fun preview(segment: Int, text: String) = lock.withLock {
            if (!streaming) {
                logger.debug(
                    "The preview of segment {} of {} is not sent: a group message of this platform is not changed",
                    segment,
                    request.turn.id,
                )
                return@withLock
            }
            if (text.isEmpty() || progress.isDeferred(request.turn.id)) return@withLock
            val trigger = request.trigger ?: return@withLock
            val current = streamOf(trigger)
            val chunk = next(answer, text)
            answer = text
            if (chunk.isNotEmpty()) current.append(chunk)
        }

        override suspend fun startSegment(segment: Int) {
            // The platform has one streaming message per reply, not one per segment; the next preview replaces it.
        }

        /** Sends the words of a model response that calls a tool, which a non-streaming chat would otherwise lose. */
        override suspend fun showResponse(message: AssistantEntry) {
            if (streaming) return
            val said = message.parts.filterIsInstance<TextPart>().joinToString("\n\n") { it.text }
            if (said.isBlank()) return
            sender.send(
                request.turn.chat,
                OutboundMessage.builder(said, MessageKind.NOTICE).markup(Markup.MARKDOWN).build(),
            )
        }

        override suspend fun event(event: TurnEvent) = lock.withLock {
            if (!config.progress || progress.isDeferred(request.turn.id)) return@withLock
            val started = event as? TurnEvent.ToolCallStarted ?: return@withLock
            activities[started.id.value] = activityLine(started)
            if (streaming) {
                val trigger = request.trigger ?: return@withLock
                val current = streamOf(trigger)
                val line = if (current.shownText.isEmpty()) activityLine(started) else "\n\n" + activityLine(started)
                current.append(line)
            } else {
                waiting += activityLine(started)
                val now = System.currentTimeMillis()
                if (!noticed || now - noticedAt >= NOTICE_INTERVAL_MS) tellWaiting()
            }
        }

        override suspend fun complete(message: OutboundMessage): Delivery = lock.withLock {
            try {
                if (message.files.isNotEmpty()) {
                    // A file message of this platform has no caption, and the stream cannot carry the file. Close the
                    // stream where it is and send the files as the message the platform takes.
                    stream?.abandon()
                    return@withLock sender.send(request.turn.chat, withTrigger(message))
                }
                if (!streaming) tellWaiting()
                if (streaming && !progress.isDeferred(request.turn.id)) {
                    val trigger = request.trigger
                    if (trigger != null) {
                        val current = streamOf(trigger)
                        val chunk = next(answer, message.text)
                        if (chunk.isNotEmpty()) current.append(chunk)
                        if (detached.isNotEmpty()) {
                            parentFinal = message.text
                            val id = current.messageId
                            if (id != null) {
                                return@withLock Delivery.Delivered(listOf(ChannelMessageRef(request.turn.chat, id)))
                            }
                        } else {
                            val finished = current.finishAppended()
                            if (finished != null) return@withLock finished
                        }
                    }
                }
                sender.send(request.turn.chat, withTrigger(message))
            } finally {
                replies.close(request.turn.id)
                progress.done(request.turn.id)
                typing.stop(request.turn.chat)
            }
        }

        @Suppress("UNUSED_PARAMETER")
        override suspend fun abandon(end: ReplyEnd, vouched: Boolean) = lock.withLock {
            logger.info("The reply of {} was abandoned: {}", request.turn.id, end)
            replies.close(request.turn.id)
            progress.abandon(request.turn.id)
            detached.clear()
            parentFinal = null
            stream?.abandon()
            typing.stop(request.turn.chat)
        }

        /** Sends the tool lines a non-streaming chat has not been told yet, as one notice. */
        private suspend fun tellWaiting() {
            if (waiting.isEmpty()) return
            val lines = waiting.toList()
            waiting.clear()
            noticed = true
            noticedAt = System.currentTimeMillis()
            sender.send(
                request.turn.chat,
                OutboundMessage.builder(lines.joinToString("\n"), MessageKind.NOTICE).build(),
            )
        }

        /** What [text] adds to [previous]: its suffix, or a new paragraph when it does not continue it. */
        private fun next(previous: String, text: String): String = when {
            text.isEmpty() -> ""
            previous.isEmpty() -> text
            text.startsWith(previous) -> text.removePrefix(previous)
            else -> "\n\n" + text
        }

        /** The streaming message of this reply, which [trigger] answers, registered for its background jobs. */
        private suspend fun streamOf(trigger: ChannelMessageRef): QqBotStream {
            stream?.let { return it }
            val created = QqBotStream(sender, request.turn.chat, trigger.id, sender.sequenceFor(trigger.id))
            stream = created
            progress.attach(request.turn.id, request.turn.chat, created)
            return created
        }

        /** One line of the activity message, which the answer replaces when it arrives. */
        private fun activityLine(event: TurnEvent.ToolCallStarted): String {
            val title = event.title.takeIf { it.isNotBlank() && it != event.tool }
            val line = if (title == null) "⏳ ${event.tool}" else "⏳ ${event.tool} — $title"
            return event.agent?.let { "⏳ $it · ${line.removePrefix("⏳ ")}" } ?: line
        }

        /** [message] as an answer to the message the turn was started by, where it answers nothing yet. */
        private fun withTrigger(message: OutboundMessage): OutboundMessage {
            val trigger = request.trigger ?: return message
            if (message.replyTo != null) return message
            return message.rebuild { replyTo(trigger) }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(QqBotChannel::class.java)

        val TOOL_EVENTS: Set<TurnEventKind> =
            setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED)

        /** How long tool lines of a non-streaming chat wait before they are told again; the first is told at once. */
        const val NOTICE_INTERVAL_MS: Long = 8_000L

        /** What this channel can do in a group: text and markdown, no streaming, no files and no live events. */
        val CAPABILITIES: ChannelCapabilities = ChannelCapabilities.builder()
            .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
            .streaming(false)
            .finalReplacesPreview(false)
            .proactive(true)
            .events(TOOL_EVENTS)
            .files(true)
            .maxFileBytes(QQ_BOT_MAX_FILE_BYTES)
            .maxPartsPerReply(null)
            .build()

        /** What this channel can do in a single chat, where the platform streams a message while it is written. */
        val STREAMING_CAPABILITIES: ChannelCapabilities = ChannelCapabilities.builder()
            .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
            .streaming(true)
            .finalReplacesPreview(true)
            .proactive(true)
            .events(TOOL_EVENTS)
            .files(true)
            .maxFileBytes(QQ_BOT_MAX_FILE_BYTES)
            .maxPartsPerReply(null)
            .build()
    }
}
