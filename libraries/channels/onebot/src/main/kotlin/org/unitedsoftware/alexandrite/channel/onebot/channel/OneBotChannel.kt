package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.onebot.api.OneBotApi
import org.unitedsoftware.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotMessages
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
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import kotlinx.coroutines.channels.Channel as Signal

/**
 * The OneBot front end of one channel instance.
 *
 * A message the implementation reported becomes an [IncomingMessage] and goes to the agent through the
 * [TurnSubmitter], and what the agent answers goes back as segments over the actions of the standard. Which
 * transport carries either is [OneBotLink]'s business, not this class's.
 *
 * What the model says while it works reaches a chat as whole messages, one per request of the turn: the hook of
 * [OneBotWordsHook] hands each response to the reply of its turn as the response ends, because a message of this
 * platform cannot be added to after it is sent.
 */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "onebot")
internal class OneBotChannel(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val sender: OneBotSender,
    /** The words this instance says to people. */
    private val texts: Texts,

    /** The approvals this instance asks its admins about. */
    private val approvals: OneBotApprovals,
    /** The chats this instance serves, which decides whether it speaks unprompted. */
    private val gate: OneBotAccessGate,
    /** The agent, null while the runtime has none. */
    private val submitter: TurnSubmitter?,
    /** The replies of this instance's turns, which the words of each response are handed to. */
    private val replies: OneBotReplyRegistry,
    /** What waits to be told in each conversation, which every reply of that conversation shares. */
    private val activities: OneBotActivities,
    private val rules: OneBotCommandRules,
) : Channel {
    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = OneBotMessages.capabilities(
        proactive = gate.isPaired(chat),
        progress = config.progress,
        streaming = config.streaming,
    )

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        if (text.isEmpty()) 0 else 1

    override suspend fun openReply(request: ReplyRequest): ReplySink =
        Sink(this, request).also { replies.open(request.turn.id, it) }

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = sender.send(chat, message)

    override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer = approvals.ask(request)

    override fun mayRun(chat: ChatAddress, issuer: ChatUser, command: String, withArguments: Boolean): Boolean =
        rules.mayRun(chat, issuer.isOperator, command, withArguments)

    /** Whether [user] is one of the instance's configured admins, who are operators in every chat of the bot. */
    override suspend fun isOperator(chat: ChatAddress, user: UserAddress): Boolean =
        user.instance == instance.id && user.user in config.admins

    /**
     * The reply of one turn, which delivers the words of each of the turn's requests and the final message.
     *
     * A message of this platform cannot be added to later, so [showResponse] sends a response whole, once, as the hook
     * hands it over. [preview] keeps nothing: the growing text a channel that can change its messages shows is not what
     * this one says.
     */
    private class Sink(private val channel: OneBotChannel, private val request: ReplyRequest) :
        ReplySink,
        OneBotWords {
        // The steps of every turn working in this chat wait here, so a chat hears them once.
        private val progress = channel.activities.of(request.turn.chat)

        override suspend fun detachActivity(run: DelegatedRun): ReplyActivity? {
            if (!channel.config.progress) return null
            return Detached(run)
        }

        /** The activity of a background run, told as the notices OneBot already uses for a working turn. */
        private inner class Detached(private val run: DelegatedRun) : ReplyActivity {
            override suspend fun event(event: TurnEvent) {
                val started = event as? TurnEvent.ToolCallStarted ?: return
                if (started.run != run.run) return
                post(
                    startedMessage(
                        ProgressCall(started.tool, started.activity, started.title, started.agent, started.run),
                        channel.texts,
                        request.turn.language,
                    ),
                )
            }

            override suspend fun close(status: ToolCallStatus) = Unit
        }

        /** What the sender beside the turn is to say next, under [lock] because an ending reply writes it too. */
        private val lock = Any()
        private var waiting: String? = null

        /** Woken whenever something waits to be said. */
        private val wake = Signal<Unit>(Signal.CONFLATED)

        /** The sender working beside the turn, null until something waits to be said. */
        private var sender: Job? = null

        /** Whether the reply has ended, after which no more activity is launched for it. */
        private var closed = false

        override suspend fun preview(segment: Int, text: String) {
            if (!channel.config.streaming) return
            // The previews of a growing segment are frequent and are never sent, so only a reader of the debug log
            // is told of them.
            logger.debug(
                "The preview of segment {} ({} characters) is not sent; the response is sent as it ends",
                segment,
                text.length,
            )
        }

        override suspend fun showResponse(message: AssistantEntry) {
            val said = message.parts.filterIsInstance<TextPart>().joinToString("\n\n") { it.text }
            if (said.isBlank()) return
            logger.info(
                "The response of {} ({} characters) goes to {}",
                request.turn.id,
                said.length,
                request.turn.chat,
            )
            // The model writes Markdown, and a message of this platform shows none: the channel renders it as the
            // plain text a chat reads, as it does for the answer that ends a turn.
            say(said, Markup.MARKDOWN)
        }

        override suspend fun event(event: TurnEvent) {
            if (!channel.config.progress) return
            when (event) {
                is TurnEvent.ToolCallStarted -> {
                    val call = progress.started(
                        event.id,
                        event.activity,
                        event.agent,
                        tool = event.tool,
                        title = event.title,
                        run = event.run,
                    ) ?: return
                    logger.info("The first call of {} is told: {}", request.turn.id, event.tool)
                    post(startedMessage(call, channel.texts, request.turn.language))
                }

                is TurnEvent.ToolCallUpdated -> {
                    if (event.status !in ENDED) return
                    progress.ended(event.id, event.status, event.content, event.run)
                    logger.debug("The call {} ended, and {} steps wait to be told", event.id, progress.counted())
                    val tally = progress.take()
                    if (tally == null) {
                        logger.debug("What ended is held back until the interval passes")
                        return
                    }
                    post(summaryMessage(tally, channel.texts, request.turn.language))
                }

                else -> return
            }
        }

        /**
         * Hands [said] to the sender beside the turn, which this returns from at once.
         *
         * A turn waits for every event it is handed, and sending here would hold it for one call of the platform per
         * message about its own work. The sender takes what waits and sends it beside the turn instead, so nothing the
         * turn does waits on a message about it.
         */
        private fun post(said: String) {
            synchronized(lock) {
                if (closed) return
                waiting = said
                if (sender == null) {
                    sender = channel.instance.scope.launch(CoroutineName("onebot activity of ${request.turn.id}")) {
                        while (true) {
                            wake.receive()
                            while (true) {
                                val next = synchronized(lock) { waiting?.also { waiting = null } } ?: break
                                try {
                                    say(next)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logger.warn(
                                        "An activity of {} did not reach {}: {}",
                                        request.turn.id,
                                        request.turn.chat,
                                        e.toString(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            wake.trySend(Unit)
        }

        /** Sends what the sender holds, and stops it, so that nothing of it is still on its way. */
        private suspend fun settle() {
            synchronized(lock) { closed = true }
            val last = synchronized(lock) { waiting?.also { waiting = null } }
            val current = synchronized(lock) { sender }
            current?.let {
                it.cancel()
                it.join()
            }
            synchronized(lock) { sender = null }
            if (last != null) say(last)
        }

        /** Sends [said] to the chat of this turn as a message of its own, in [markup]. */
        private suspend fun say(said: String, markup: Markup = Markup.PLAIN) {
            val message = OutboundMessage.builder(said, MessageKind.REPLY).markup(markup).build()
            channel.sender.send(request.turn.chat, message)
        }

        override suspend fun complete(message: OutboundMessage): Delivery {
            logger.info(
                "The turn of {} ended: its {} waiting steps are told before the answer, and its reply closes",
                request.turn.chat,
                progress.counted(),
            )
            try {
                settle()
                progress.flush()?.let { say(summaryMessage(it, channel.texts, request.turn.language)) }
                return channel.sender.send(request.turn.chat, withTrigger(message))
            } finally {
                channel.replies.close(request.turn.id)
                channel.activities.forget(request.turn.chat)
            }
        }

        /** [message] as the answer to the message this turn answers, so the chat shows it as a quote reply. */
        private fun withTrigger(message: OutboundMessage): OutboundMessage {
            val trigger = request.trigger ?: return message
            if (message.replyTo != null) return message
            return message.rebuild { replyTo(trigger) }
        }

        @Suppress("UNUSED_PARAMETER")
        override suspend fun abandon(end: ReplyEnd, vouched: Boolean) {
            logger.info(
                "The turn of {} was abandoned: its {} waiting steps are told, and its reply closes",
                request.turn.chat,
                progress.counted(),
            )
            try {
                settle()
                progress.flush()?.let { say(summaryMessage(it, channel.texts, request.turn.language)) }
            } finally {
                channel.replies.close(request.turn.id)
                channel.activities.forget(request.turn.chat)
            }
        }
    }
}

private val logger = LoggerFactory.getLogger(OneBotChannel::class.java)
