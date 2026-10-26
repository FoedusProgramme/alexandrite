package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.common.link.LinkDecision
import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.qqbot.gateway.QqBotGateway
import org.unitedsoftware.alexandrite.channel.qqbot.i18n.TextKeys
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotChats
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotMedia
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotMessages
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFrame
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotMessageEvent
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.asTextOrNull
import org.unitedsoftware.alexandrite.channel.qqbot.tools.QqBotApis
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the gateway pushes, taken into the agent.
 *
 * The platform pushes a message of a single chat, and of a group only when the message names the bot, so both are the
 * bot's to answer. Everything else a bot of the platform may subscribe to is left for a version that has a shape to
 * hand it to another plugin.
 *
 * The platform may push the same message twice: a message id already read is dropped.
 */
@ChannelInstanceScoped
internal class QqBotInbox(
    private val instance: ChannelInstance,
    private val config: QqBotInstanceConfig,
    private val gateway: QqBotGateway,
    private val media: QqBotMedia,
    private val approvals: QqBotApprovals,
    private val sender: QqBotSender,
    private val rules: QqBotCommandRules,
    private val texts: Texts,
    private val apis: QqBotApis,
    private val api: QqBotApi,
    private val index: QqBotMessageIndex,
    private val gate: QqBotAccessGate,
    private val operators: QqBotOperators,
    private val links: QqBotOperatorLinks,
    private val clock: Clock,
    /** The agent, null while the runtime has none. */
    private val submitter: TurnSubmitter?,
) : Lifecycle {
    private var reading: Job? = null

    /** The openid the platform knows this bot by, which a full-mode group message names it by when it is addressed. */
    @Volatile
    private var botOpenid: String? = null

    /** The bot's own member openid of each group, which `bot_state` or its own messages name. */
    private val botMemberOpenids = ConcurrentHashMap<String, String>()

    /** The groups whose `bot_state` was already asked for, so a refusal is not asked for again and again. */
    private val botStateAsked = ConcurrentHashMap.newKeySet<String>()

    private val seen = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > KEPT
    }

    override suspend fun onOpen() {
        apis.register(instance.id, api)
        identify()
        if (submitter == null) {
            logger.warn("{}: the runtime has no agent, so the messages of {} reach nobody", PLUGIN, instance.id)
            return
        }
        reading = instance.scope.launch { read() }
    }

    /** Asks the platform for the bot's own id, which a full-mode group message may mention it by. */
    private suspend fun identify() {
        val answer = api.get("/users/@me")
        val body = (answer as? QqBotAnswer.Ok)?.body as? JsonObject
        botOpenid = body?.get("id")?.asTextOrNull()
        if (botOpenid == null) logger.debug("{}: the platform did not name the bot itself", PLUGIN)
    }

    override suspend fun onClose() {
        reading?.cancel()
        reading = null
        apis.forget(instance.id)
    }

    private suspend fun read() {
        gateway.frames.collect { frame ->
            // One frame that fails to read must not end the intake: the next message may still reach the agent.
            try {
                read(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("{}: reading a frame of the platform failed: {}", PLUGIN, e.toString())
            }
        }
    }

    /** Takes one frame of the gateway, and the message it dispatches when it dispatches one. */
    private suspend fun read(frame: QqBotFrame) {
        if (frame.op != OP_DISPATCH || frame.t == null) return
        if (frame.t != C2C_MESSAGE_CREATE && frame.t != GROUP_AT_MESSAGE_CREATE && frame.t != GROUP_MESSAGE_CREATE) {
            logger.debug("{}: the platform dispatched {}, which this version leaves alone", PLUGIN, frame.t)
            return
        }
        val event = QqBotWire.message(frame.d) ?: run {
            logger.warn("{}: an event {} carries no message this version knows", PLUGIN, frame.t)
            return
        }
        if (event.author.bot && event.groupOpenid != null) {
            event.author.memberOpenid?.let { botMemberOpenids[event.groupOpenid] = it }
        }
        if (frame.t == GROUP_MESSAGE_CREATE && !addressed(event)) {
            logger.debug(
                "{}: a group message of the full mode, {} in {}, is not addressed to this bot",
                PLUGIN,
                event.id,
                event.groupOpenid,
            )
            return
        }
        if (event.author.bot) return
        if (!first(event.id)) {
            logger.debug("{}: the platform pushed the message {} again", PLUGIN, event.id)
            return
        }
        if (QqBotMessages.text(event).isBlank() && event.attachments.isEmpty() && event.msgElements.isEmpty()) {
            logger.info(
                "{}: the message {} was only a mention of the bot or was empty; no turn is asked",
                PLUGIN,
                event.id,
            )
            return
        }
        val chat = QqBotChats.addressOf(instance.id, event)
        val authorId = QqBotMessages.authorId(event)
        val isOperator = operators.isOperator(chat, authorId)
        val message = QqBotMessages.incoming(instance.id, event, isOperator, media, index)
        // A diagnostic that works before any tool and before pairing: the person can read the id the bot knows them
        // by, which is what `admins` and a `/link` alias are keyed by.
        if (message.text.trim().equals(WHOAMI, ignoreCase = true)) {
            sender.send(message.chat, notice("chat=${message.chat.chat}\nsender=${message.sender.address}"))
            return
        }
        val kind = if (!event.groupOpenid.isNullOrEmpty()) ChatKind.GROUP else ChatKind.DIRECT
        if (!gate.admit(chat, kind, null, message.sender)) return
        val link = QqBotLinkCommands.of(message.text)
        if (link != null) {
            handleLink(link, message)
            return
        }
        val press = QqBotApprovalCommands.of(message.text)
        if (press != null) {
            logger.info("{}: {} answers an approval of {}", PLUGIN, message.sender.address, press.id)
            approvals.answer(press.name, press.id, press.always, message.sender, message.chat)?.let { said ->
                sender.send(message.chat, notice(said))
            }
            return
        }
        val command = QqBotCommands.of(message.text)
        if (command != null) {
            val invocation = CommandInvocation.builder(message.chat, command.name, message.sender)
                .arguments(command.arguments)
                .trigger(message.ref)
                .build()
            val refusal = rules.refusal(invocation, message.chatInfo.kind)
            if (refusal != null) {
                sender.send(message.chat, notice(texts.text(refusal, null, "command" to command.name.lowercase())))
                return
            }
            val capacity = if (message.sender.isOperator) Capacity.EXEMPT else Capacity.COUNTED
            logger.info("{}: the command {} of {} becomes a turn for the agent", PLUGIN, command.name, message.chat)
            report(message, submitter?.submit(Submission.Command(invocation, message, capacity)))
            return
        }
        logger.info("{}: the message {} of {} becomes a turn for the agent", PLUGIN, event.id, message.chat)
        report(message, submitter?.submit(Submission.Message(message)))
    }

    /**
     * Answers a `/link`, `/links` or `/unlink` command of [message], which this instance takes wherever it is sent:
     * asking for a code and reading or revoking a link are an operator's own business, in whichever chat already
     * recognizes them as one.
     */
    private suspend fun handleLink(
        link: QqBotLinkCommands.Parsed,
        message: org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage,
    ) {
        when (link) {
            QqBotLinkCommands.Parsed.Issue -> {
                if (!message.sender.isOperator) {
                    sender.send(message.chat, notice(texts.text(TextKeys.REFUSED_ADMIN, null, "command" to "link")))
                    return
                }
                val code = links.issue(message.sender.address)
                sender.send(
                    message.chat,
                    notice(
                        texts.text(
                            TextKeys.LINK_CODE,
                            null,
                            "code" to code.text,
                            "minutes" to links.codeLifetime.inWholeMinutes,
                        ),
                    ),
                )
            }

            is QqBotLinkCommands.Parsed.Redeem -> {
                val decision = links.redeem(link.code, message.chat, message.sender.address.user)
                sender.send(message.chat, notice(linkDecisionText(decision)))
            }

            QqBotLinkCommands.Parsed.List -> {
                if (!message.sender.isOperator) {
                    sender.send(message.chat, notice(texts.text(TextKeys.REFUSED_ADMIN, null, "command" to "links")))
                    return
                }
                val mine = links.of(message.sender.address)
                val said = if (mine.isEmpty()) {
                    texts.text(TextKeys.LINKS_EMPTY, null)
                } else {
                    mine.joinToString("\n") {
                        texts.text(TextKeys.LINKS_ENTRY, null, "chat" to it.chat.chat, "alias" to it.alias)
                    }
                }
                sender.send(message.chat, notice(said))
            }

            is QqBotLinkCommands.Parsed.Unlink -> {
                if (!message.sender.isOperator) {
                    sender.send(message.chat, notice(texts.text(TextKeys.REFUSED_ADMIN, null, "command" to "unlink")))
                    return
                }
                val chat = link.chat
                if (chat == null) {
                    sender.send(message.chat, notice(texts.text(TextKeys.UNLINK_USAGE, null)))
                    return
                }
                val target = ChatAddress(instance.id, chat)
                val mine = links.of(message.sender.address).firstOrNull { it.chat == target }
                val removed = mine != null && links.unlink(mine.chat, mine.alias)
                sender.send(
                    message.chat,
                    notice(texts.text(if (removed) TextKeys.UNLINK_DONE else TextKeys.UNLINK_NONE, null)),
                )
            }
        }
    }

    /** What a chat is told after [decision] answers a `/link <code>`. */
    private fun linkDecisionText(decision: LinkDecision): String = when (decision) {
        is LinkDecision.Linked -> texts.text(TextKeys.LINK_LINKED, null)
        is LinkDecision.Rejected -> texts.text(TextKeys.LINK_REJECTED, null, "tries" to decision.triesLeft)
        is LinkDecision.Locked -> texts.text(TextKeys.LINK_LOCKED, null, "minutes" to minutesUntil(decision.until))
        LinkDecision.OwnCode -> texts.text(TextKeys.LINK_OWN_CODE, null)
        LinkDecision.Malformed -> texts.text(TextKeys.LINK_MALFORMED, null)
        is LinkDecision.Failed -> texts.text(TextKeys.LINK_FAILED, null)
    }

    /** The whole minutes from now until [until], at least one. */
    private fun minutesUntil(until: Instant): Long =
        Duration.between(clock.instant(), until).toMinutes().coerceAtLeast(1)

    /** [text] as a notice this chat did not ask the agent for. */
    private fun notice(text: String): OutboundMessage = OutboundMessage.builder(text, MessageKind.NOTICE).build()

    /** Whether [id] is a message this inbox reads for the first time. */
    private fun first(id: String): Boolean = synchronized(seen) { seen.put(id, Unit) == null }

    /**
     * Whether a full-mode group message names this bot.
     *
     * The platform pushes every group message in the full mode, and only the mention of the bot makes one a message
     * for the bot. The mention list names the bot by its id, when the platform keeps it, and the text may still start
     * with `<@…>`, which the bot's own id is compared against.
     */
    private suspend fun addressed(event: QqBotMessageEvent): Boolean {
        val group = event.groupOpenid ?: return false
        val me = botOpenid
        val member = botMemberOpenids[group]
        if (event.mentions.any { mention -> mention.bot && namesBot(mention, me, member) }) return true
        val token = LEADING_MENTION.find(event.content)?.groupValues?.get(1) ?: return false
        if (token == me || token == member) return true
        if (member == null && botStateAsked.add(group)) {
            var found = false
            try {
                val answer = api.get("/v2/groups/$group/bot_state")
                val body = (answer as? QqBotAnswer.Ok)?.body as? JsonObject
                val openid = body?.get("member_openid")?.asTextOrNull()
                if (openid != null) {
                    botMemberOpenids[group] = openid
                    found = true
                    return token == openid
                }
                logger.debug("{}: the platform did not name the bot's member openid of {}", PLUGIN, group)
            } finally {
                // A failed lookup must be tried again: the next message may be the one that makes this group work.
                if (!found) botStateAsked.remove(group)
            }
        }
        return false
    }

    /** Whether [mention] is the bot, by the global id or the member openid of its group. */
    private fun namesBot(
        mention: org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotAuthor,
        me: String?,
        member: String?,
    ): Boolean = (me == null && member == null) || mention.id == me || mention.memberOpenid == me ||
        mention.userOpenid == me || mention.id == member || mention.memberOpenid == member ||
        mention.userOpenid == member

    /** Reports a submission the agent refused, which would otherwise be silence nobody can explain. */
    private fun report(message: org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage, admission: Admission?) {
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
        const val PLUGIN = "alexandrite-channel-qqbot"

        const val OP_DISPATCH = 0

        const val C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE"

        const val GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE"

        /** The full-mode event of the groups that the bot takes every message of, not only the ones naming it. */
        const val GROUP_MESSAGE_CREATE = "GROUP_MESSAGE_CREATE"

        /** The command that tells a person the address this instance knows them by, for `admins` and tool rules. */
        const val WHOAMI = "/whoami"

        /** How many message ids the inbox keeps to tell a repeated push from a new message. */
        const val KEPT = 1024

        val LEADING_MENTION = Regex("^\\s*<@!?([^>]+)>")

        val logger = LoggerFactory.getLogger(QqBotInbox::class.java)
    }
}
