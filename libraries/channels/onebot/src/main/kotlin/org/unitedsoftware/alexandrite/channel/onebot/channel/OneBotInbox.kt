package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.onebot.api.OneBotApi
import org.unitedsoftware.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotChats
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotMessages
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotNotices
import org.unitedsoftware.alexandrite.channel.onebot.mapping.QuotedMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.GetMessage
import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotMessageInfo
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.channel.onebot.transport.OneBotWire
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.ChannelPoints
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter

/**
 * What the implementation reports, taken into the agent.
 *
 * A message the bot is addressed by, and a command wherever it appears, becomes a submission that the agent queues as
 * a turn of its chat. Addressed means what it means to every channel of this project: a direct chat always, and a
 * group when the message names the bot or answers one of its messages. Everything else a group says is not a turn,
 * which is what keeps the traffic of a group out of the agent.
 *
 * Every other event, a notice, a request or a heartbeat, is read and dropped here: it is not a turn, and this version
 * has no way to hand one to another plugin. The connection can carry it, so the way out is a decision about the shape
 * of that interface, and until it is taken a plugin that needs a notice or a request goes through [OneBotApi] for the
 * state it wants. An instance whose runtime has no agent accepts the events and hands them nowhere, which is reported
 * once so that the silence is visible.
 */
@ChannelInstanceScoped
internal class OneBotInbox(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val link: OneBotLink,
    /** What this instance sends, which answers an approval command. */
    private val sender: OneBotSender,
    /** The approvals this instance asks its admins about. */
    private val approvals: OneBotApprovals,
    /** The messages this instance sent, which tell a reply to the bot from a reply to somebody else. */
    private val sent: OneBotSentMessages,
    /** What this instance read not long ago, which is what a message answering one says it answered. */
    private val recent: OneBotRecentMessages,
    /** Who may run each command, which nothing else asks. */
    private val rules: OneBotCommandRules,
    /** The chats this instance serves, and the code a chat it does not serve is offered. */
    private val gate: OneBotAccessGate,
    /** The texts of this channel for people. */
    private val texts: Texts,
    /** What reads the bytes of an image or a voice message the implementation reported. */
    private val media: OneBotMedia,
    /** Where a report that is not a message is published for the plugins that subscribe to it. */
    private val hooks: Hooks,
    /** The API of this instance, which a tool of the runtime reads to act on a chat of it. */
    private val api: OneBotApi,
    /** Where this instance is served from, for whoever acts on its chats. */
    private val apis: OneBotApis,
    /** The agent, null while the runtime has none. */
    private val submitter: TurnSubmitter?,
) : Lifecycle {
    private var reading: Job? = null

    /** The message ids this inbox already took, which an implementation may report again. */
    private val seen = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > KEPT
    }

    /** Whether this is the first report of the message [id]. */
    private fun first(id: String): Boolean = seen.put(id, Unit) == null

    override suspend fun onStart() {
        // The instance is served from here on, whatever else it does: a tool that acts on a chat of it finds its API
        // whether or not the runtime has an agent to take its messages.
        apis.register(instance.id, api)
        if (submitter == null) {
            logger.warn(
                "{}: the runtime has no agent, so the messages of channel instance {} reach nobody",
                PLUGIN,
                instance.id,
            )
            return
        }
        reading = instance.scope.launch { read() }
    }

    override suspend fun onClose() {
        reading?.cancel()
        reading = null
        apis.forget(instance.id)
    }

    private suspend fun read() {
        link.events.collect { event ->
            // A report of anything but a message is published for whoever cares: it is not a turn, and nothing else
            // would carry it to a plugin. It is published whether or not the chat is served, because a chat that is not
            // served yet is where somebody asks to join.
            if (event !is OneBotEvent.Message) {
                OneBotNotices.of(instance.id, event, event.selfId.value)?.let { hooks.fire(ChannelPoints.NOTICE, it) }
                return@collect
            }
            if (!first(event.messageId.value)) {
                logger.debug("{}: the implementation reported message {} again", instance.id, event.messageId)
                return@collect
            }
            val chat = OneBotChats.addressOf(instance.id, event)
            // Every message is kept as it arrives, whether or not it is meant for the bot: what a later message answers
            // is usually one of these, and the one it answers need not have been meant for the bot at all.
            recent.remember(chat, event.messageId.value, OneBotMessages.quotable(instance.id, event))
            // A command is run wherever it is written; anything else has to be meant for the bot, or a group's talk
            // would each become a turn of the agent.
            val command = OneBotCommands.of(event)
            // A reply names the message it answers, and after a restart the sent ids are gone; reading the message
            // back tells whether the bot wrote it, so a restart does not make its old messages unrecognizable.
            val answered = quoted(chat, event)
            val addressed = addressed(event, answered)
            if (command == null && !addressed) {
                logger.debug("{}: a message in {} names nobody of this instance, and is no command", instance.id, chat)
                return@collect
            }
            // A message that only names the bot says nothing to answer: the Telegram channel ignores one, and so does
            // this one, unless it also carries media, quotes a message, or asks for a command.
            if (command == null && !saysSomething(event)) {
                logger.debug("{}: a message in {} names the bot and says nothing; no turn is asked", instance.id, chat)
                return@collect
            }
            // Nothing of a chat that is not paired passes, commands included: a bot that was invited somewhere answers
            // and obeys nobody until somebody says it may.
            val asked = OneBotMessages.senderOf(instance.id, event, config.admins)
            val kind = if (event is OneBotEvent.Message.Private) ChatKind.DIRECT else ChatKind.GROUP
            val ref = ChannelMessageRef(chat, event.messageId.value)
            if (!gate.admit(chat, kind, null, ref, asked, addressed)) return@collect
            // An approval is answered here, since the command registry of the runtime knows nothing of it, and a
            // command it does not claim is refused to everyone but an operator.
            if (command != null && approvals.answers(command.name)) {
                val answer = approvals.answer(event, command, asked)
                if (answer != null) {
                    sender.send(chat, said(answer))
                }
                return@collect
            }
            val message = OneBotMessages.incoming(instance.id, event, config.admins, answered, media)
            if (command != null) {
                // Who may run a command is the channel's question to ask: the agent's control takes the operator of a
                // change as a parameter and does not judge it.
                val invocation = invocationOf(command, message)
                val refusal = rules.refusal(invocation, message.chatInfo.kind)
                if (refusal != null) {
                    sender.send(chat, said(text(refusal, "command" to invocation.name.lowercase())))
                    return@collect
                }
            }
            val submission = if (command == null) Submission.Message(message) else commandOf(command, message)
            logger.info(
                "{}: {} in {} from {} becomes a turn for the agent",
                instance.id,
                if (command == null) "a message" else command.name,
                chat,
                asked.address,
            )
            report(message, submitter?.submit(submission))
        }
    }

    /** Whether [event] carries anything but the mention that addressed the bot: text, media, or a quote. */
    private fun saysSomething(event: OneBotEvent.Message): Boolean = event.message.segments.any { segment ->
        when (segment) {
            is OneBotSegment.Text -> segment.text.isNotBlank()
            is OneBotSegment.Image, is OneBotSegment.Record, is OneBotSegment.Video, is OneBotSegment.File -> true
            is OneBotSegment.Reply -> true
            else -> false
        }
    }

    /**
     * What [event] answers, as the channel read it, null when it answers nothing.
     *
     * What the channel kept is read first, which is what a chat normally answers. Only a message older than what is
     * kept is read back from the implementation, and that read can fail: a message may be withdrawn, or unknown to the
     * implementation, and a chat goes on whether or not what a message quoted can be read.
     */
    private suspend fun quoted(chat: ChatAddress, event: OneBotEvent.Message): QuotedMessage? {
        val id = event.message.segments.filterIsInstance<OneBotSegment.Reply>().firstOrNull()?.id ?: return null
        recent.find(chat, id.value)?.let { return it }
        val result = try {
            link.call("get_msg", GetMessage(id).toJson())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("{} cannot read what message {} answers: {}", instance.id, event.messageId, e.toString())
            return null
        }
        val data = (result as? OneBotResult.Ok)?.data ?: return null
        return try {
            val info = OneBotWire.decodeFromJsonElement(OneBotMessageInfo.serializer(), data)
            OneBotMessages.quotable(instance.id, info, event.selfId.value).also { recent.remember(chat, id.value, it) }
        } catch (e: Exception) {
            logger.debug("{} cannot read the report about message {}: {}", instance.id, id, e.toString())
            null
        }
    }

    /** The message that says [text] as the reply to a command, distinct from the reply to a turn. */
    private fun said(text: String): OutboundMessage = OutboundMessage.builder(text, MessageKind.REPLY).build()

    /** [text] in the host's language, with [args] put where they are named. */
    private fun text(key: String, vararg args: Pair<String, Any?>): String = texts.text(key, null, *args)

    /** The invocation [command] makes in [message]. */
    private fun invocationOf(command: OneBotCommands.Parsed, message: IncomingMessage): CommandInvocation =
        CommandInvocation.builder(message.chat, command.name, message.sender)
            .arguments(command.arguments)
            .trigger(message.ref)
            .build()

    /** The submission of [command] in [message], which an operator's commands pass the queue with. */
    private fun commandOf(command: OneBotCommands.Parsed, message: IncomingMessage): Submission.Command =
        Submission.Command(
            invocation = invocationOf(command, message),
            message = message,
            capacity = if (message.sender.isOperator) Capacity.EXEMPT else Capacity.COUNTED,
        )

    /** Whether the bot of this instance is addressed by [event], where [answered] is what it answered. */
    private fun addressed(event: OneBotEvent.Message, answered: QuotedMessage?): Boolean = OneBotCommands.addressed(
        event = event,
        isDirect = event is OneBotEvent.Message.Private,
        namesTheBot = { selfId -> event.message.segments.any { it is OneBotSegment.At && it.names(selfId) } },
        answersTheBot = { id -> sent.holds(id) || (answered?.authorIsBot == true) },
    )

    /**
     * Reports a submission the agent refused.
     *
     * A message the agent refuses never becomes a turn, so it is said here: the reason is what tells an operator
     * that the chat is not linked, which nothing else reports.
     */
    private fun report(message: IncomingMessage, admission: Admission?) {
        if (admission !is Admission.Refused) return
        logger.warn(
            "{}: instance {} took a message of {} no further: {}",
            PLUGIN,
            instance.id,
            message.chat,
            admission.reason,
        )
    }

    private companion object {
        const val PLUGIN = "alexandrite-channel-onebot"

        /** How many message ids the inbox keeps to tell a repeated report from a new message. */
        const val KEPT = 1024

        val logger: Logger = LoggerFactory.getLogger(OneBotInbox::class.java)
    }
}

/**
 * The commands of a message, and whether the bot is addressed by one.
 *
 * A command starts a message and is named after a `/`, which is how every channel of this project spells one: the
 * prefix is configurable nowhere yet, so it is not configurable here either. The name is written as one is on the
 * platform the other channel serves: ASCII letters, digits and underscores, one to 32 of them, optionally followed by
 * `@target`. Text that starts with a slash but holds no such name is not a command, so a group message like
 * `/随便说点什么` is not heard by the bot unless it names the bot otherwise, as it is not on that platform.
 */
internal object OneBotCommands {
    /** The prefix a command is named after. */
    const val PREFIX = "/"

    /** A command token: the prefix, a name, and optionally the target a name belongs to. */
    private val TOKEN = Regex(PREFIX + "([A-Za-z0-9_]{1,32})(?:@[A-Za-z0-9_]{1,32})?")

    /** The command [event] carries, null when its text names none. */
    fun of(event: OneBotEvent.Message): Parsed? {
        val text = textOf(event).trimStart()
        val token = text.takeWhile { !it.isWhitespace() }
        val match = TOKEN.matchEntire(token) ?: return null
        return Parsed(match.groupValues[1], text.removePrefix(token).trim())
    }

    /**
     * Whether [event] is a message the bot is addressed by.
     *
     * A direct chat is the bot's. A group is the bot's when the message names it or answers one of its messages, and
     * what it answers is told by [answersTheBot], since the message names the id it answers but says nothing of who
     * sent it.
     */
    fun addressed(
        event: OneBotEvent.Message,
        isDirect: Boolean,
        namesTheBot: (String) -> Boolean,
        answersTheBot: (String) -> Boolean,
    ): Boolean {
        if (isDirect) return true
        val selfId = event.selfId.value
        if (namesTheBot(selfId)) return true
        val answered = event.message.segments.filterIsInstance<OneBotSegment.Reply>().firstOrNull() ?: return false
        return answersTheBot(answered.id.value)
    }

    /** The text of [event], which is every text segment of it and nothing else. */
    private fun textOf(event: OneBotEvent.Message): String =
        event.message.segments.filterIsInstance<OneBotSegment.Text>().joinToString("") { it.text }

    /** The parts of a command: its [name] and its [arguments], which may be empty. */
    internal class Parsed(val name: String, val arguments: String)
}

/** Whether [this] mention names [selfId], or names everyone. */
private fun OneBotSegment.At.names(selfId: String): Boolean = isAll || userId == selfId
