package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.unitedsoftware.alexandrite.channel.onebot.i18n.TextKeys
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotChats
import org.unitedsoftware.alexandrite.channel.onebot.mapping.OneBotMessages
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * Shows approval requests as messages an admin answers by replying to them, and takes the first answer within the
 * configured time.
 *
 * The message that carries a request is what names it: the implementation reports what a message answers as an id, so
 * the id of the message this desk sent is matched against that and nothing else. Several requests can wait in one chat
 * without being confused, because each was carried by a message of its own. A request is also answered by writing its
 * id, which is what a client that cannot reply with needs; an id that matches several requests is never guessed at.
 *
 * A chat other than the one a request was shown in cannot answer it, and a request that two admins answer at once is
 * answered by the first of them.
 */
@ChannelInstanceScoped
internal class OneBotApprovals(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val sender: OneBotSender,
    private val texts: Texts,
    /** The chats this instance serves, whose pairing a chat of no operator may need before it can answer. */
    private val gate: OneBotAccessGate,
    /** The language of each chat, null while the runtime has no agent to ask. */
    private val settings: ChatSettings?,
) : Lifecycle {
    private val pending = ConcurrentHashMap<String, Pending>()
    private val work = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + work)

    /** The answer of the first admin who answers [request], else how the request ended. */
    suspend fun ask(request: ApprovalRequest): ApprovalAnswer {
        val chats = recipients(request)
        if (chats.isEmpty()) {
            // A chat that never paired is offered its code when somebody addresses the bot in it, never sent a
            // request it cannot answer.
            logger.warn(
                "{} cannot ask for approval {}: no administrator chat of it is paired",
                instance.id,
                request.id,
            )
            return UNREACHABLE
        }
        val waiting = Pending(request)
        if (pending.putIfAbsent(request.id, waiting) != null) {
            return ApprovalAnswer.Unavailable("another request has the id ${request.id}")
        }
        try {
            // A chat nobody could be shown in is no reach, and what the platform said is the reason a call was refused
            // rather than that no administrator exists.
            val why = chats.mapNotNull { post(waiting, it, languageOf(waiting.request, it)) }.firstOrNull()
            if (waiting.carried.isEmpty()) {
                logger.warn("{} could not show approval {} in any chat: {}", instance.id, request.id, why)
                return if (why ==
                    null
                ) {
                    UNREACHABLE
                } else {
                    ApprovalAnswer.Unavailable("the platform took no message of it: $why")
                }
            }
            logger.info(
                "{} asks for approval {} of {} in turn {} in {} chat(s)",
                instance.id,
                request.id,
                request.tool,
                request.turn.id,
                waiting.carried.size,
            )
            withTimeoutOrNull(config.approvalMinutes.minutes) { waiting.ending.await() }
            waiting.ending.complete(Ending.Expired)
            val ending = waiting.ending.await()
            conclude(waiting, ending)
            return ending.answer
        } catch (e: CancellationException) {
            waiting.ending.complete(Ending.Withdrawn)
            conclude(waiting, Ending.Withdrawn)
            throw e
        } finally {
            pending.remove(request.id)
        }
    }

    /** Whether [name] is a command this desk answers, in any case. */
    fun answers(name: String): Boolean = name.equals(APPROVE, ignoreCase = true) || name.equals(DENY, ignoreCase = true)

    /**
     * Takes the approval command [command] that [by] wrote in [event], and the text to reply with, null when the reply
     * would say nothing.
     */
    suspend fun answer(event: OneBotEvent.Message, command: OneBotCommands.Parsed, by: ChatUser): String? {
        val chat = OneBotChats.addressOf(instance.id, event)
        if (!by.isOperator) return text(TextKeys.APPROVAL_ADMINS_ONLY, language(chat))
        val words = command.arguments.split(' ').filter { it.isNotBlank() }
        val always = words.any { it.equals(ALWAYS, ignoreCase = true) }
        val code = words.firstOrNull { !it.equals(ALWAYS, ignoreCase = true) }
        return when (val resolution = resolve(chat, event, code)) {
            Resolution.None -> text(TextKeys.APPROVAL_NOTHING_WAITS, language(chat))

            is Resolution.Listing -> listing(resolution.requests, language(chat))

            is Resolution.Ambiguous -> several(resolution.ids, language(chat))

            is Resolution.One -> decide(
                resolution.id,
                by,
                command.name.equals(APPROVE, ignoreCase = true),
                always,
                language(chat),
            )
        }
    }

    override suspend fun onDrain() {
        work.children.toList().joinAll()
    }

    override fun onStop() {
        pending.values.forEach { it.ending.complete(Ending.Answered(ApprovalAnswer.Unavailable("the bot stopped"))) }
    }

    /**
     * The chats [request] is shown in: the chat of an admin's own turn, else the paired direct chats of the admins.
     *
     * The pairing gate is not bypassed for an approval: a chat that never paired is offered its code when somebody
     * addresses the bot in it, and a request is not sent where it could not be answered.
     */
    private fun recipients(request: ApprovalRequest): List<ChatAddress> {
        val turn = request.turn
        return if (turn.actorIsOperator) {
            listOf(turn.chat)
        } else {
            config.admins.distinct()
                .map { OneBotChats.privateChat(instance.id, UserId(it)) }
                .filter { gate.isPaired(it) }
        }
    }

    /** Sends the message with [waiting]'s request to [chat], and says what went wrong when it was not shown. */
    private suspend fun post(waiting: Pending, chat: ChatAddress, language: LanguageTag?): String? {
        val text = render(waiting.request, language)
        // An approval is worth more than one try: an implementation that times out on a message often takes the next
        // one, and a request nobody was shown refuses the call for a reason that is nobody's. What the platform said
        // is kept, so that a request that reached no chat is not answered as though no admin could be reached.
        var why: String? = null
        for (attempt in 1..APPROVAL_TRIES) {
            val delivery = try {
                sender.send(chat, OutboundMessage.builder(text, MessageKind.REPLY).build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                why = e.message
                logger.warn(
                    "{} could not show approval {} in chat {} (try {}): {}",
                    instance.id,
                    waiting.request.id,
                    chat,
                    attempt,
                    e.message,
                )
                null
            }
            val delivered = delivery as? Delivery.Delivered
            if (delivered != null) {
                val carried = waiting.carried.getOrPut(chat) { ConcurrentHashMap.newKeySet() }
                delivered.messages.forEach { carried += it.id }
                return null
            }
            if (delivery != null) {
                why = delivery.toString()
                logger.warn(
                    "{} could not show approval {} in chat {} (try {}): {}",
                    instance.id,
                    waiting.request.id,
                    chat,
                    attempt,
                    delivery,
                )
            }
            if (attempt < APPROVAL_TRIES) delay(APPROVAL_RETRY_MILLIS)
        }
        return why ?: "the platform took no message of it"
    }

    /** What the command of [chat] names, read from the message it replied to and else from its id. */
    private fun resolve(chat: ChatAddress, event: OneBotEvent.Message, code: String?): Resolution {
        val replied = event.message.segments.filterIsInstance<OneBotSegment.Reply>().firstOrNull()?.id?.value
        if (replied != null) {
            val held = pending.values.filter { replied in (it.carried[chat] ?: emptySet()) }
            return when (held.size) {
                1 -> Resolution.One(held.single().request.id)
                0 -> Resolution.None
                else -> Resolution.Ambiguous(held.map { it.request.id })
            }
        }
        val waiting = pending.values.filter { chat in it.carried.keys }
        if (code ==
            null
        ) {
            return if (waiting.isEmpty()) Resolution.None else Resolution.Listing(waiting.map { it.request })
        }
        // An id written out is matched exactly first: a request whose id is the whole of what was written is the one
        // meant, even when the id of another request starts with the same letters.
        waiting.firstOrNull { it.request.id == code }?.let { return Resolution.One(it.request.id) }
        val matched = waiting.filter { it.request.id.startsWith(code) }
        return when (matched.size) {
            1 -> Resolution.One(matched.single().request.id)
            0 -> Resolution.None
            else -> Resolution.Ambiguous(matched.map { it.request.id })
        }
    }

    /**
     * Answers the request [id], and what to reply with, null when nothing is said.
     *
     * An answer that is taken is said by the outcome of the request, which goes to every chat it was shown in, so
     * nothing is said in the chat the command was written in as well: that would say the same thing twice. Only an
     * answer that could not be taken is said here, because nothing else reports it.
     */
    private fun decide(id: String, by: ChatUser, allowed: Boolean, always: Boolean, language: LanguageTag?): String? {
        val waiting = pending[id] ?: return text(TextKeys.APPROVAL_NOT_PENDING, language)
        val request = waiting.request
        val answer = if (allowed) {
            ApprovalAnswer.Allowed(by, always && request.alwaysRules.isNotEmpty())
        } else {
            ApprovalAnswer.Denied(by)
        }
        if (!waiting.ending.complete(Ending.Answered(answer))) {
            return text(TextKeys.APPROVAL_NOT_PENDING, language)
        }
        return null
    }

    /** The requests that wait in the chat the command was written in. */
    private fun listing(requests: List<ApprovalRequest>, language: LanguageTag?): String {
        val entries = requests.map {
            text(
                TextKeys.APPROVAL_LIST_ENTRY,
                language,
                "id" to it.id,
                "tool" to it.tool,
                "reason" to it.reason,
            )
        }
        return listOf(text(TextKeys.APPROVAL_LIST_TITLE, language))
            .plus(entries)
            .plus(text(TextKeys.APPROVAL_LIST_HINT, language))
            .joinToString("\n")
    }

    /** The ids that a written id matched, which name no one request. */
    private fun several(ids: List<String>, language: LanguageTag?): String =
        text(TextKeys.APPROVAL_SEVERAL, language, "ids" to ids.joinToString(", "))

    /** Says how [waiting] ended, in each chat that was shown it. */
    private fun conclude(waiting: Pending, ending: Ending) {
        val request = waiting.request
        logger.info("{}: approval {} of turn {} {}", instance.id, request.id, request.turn.id, ending.logged)
        // The standard has no way to change a message, so how a request ended is said in a message of its own rather
        // than written into the one that carried it.
        val chats = waiting.carried.keys.toList()
        scope.launch {
            for (chat in chats) {
                val line = outcome(request, ending, language(chat)) ?: continue
                try {
                    sender.send(chat, OutboundMessage.builder(line, MessageKind.REPLY).build())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(
                        "{} could not say how approval {} ended in {}: {}",
                        instance.id,
                        request.id,
                        chat,
                        e.message,
                    )
                }
            }
        }
    }

    /** The line that tells how [request] ended in [language], null for no line. */
    private fun outcome(request: ApprovalRequest, ending: Ending, language: LanguageTag?): String? = when (ending) {
        Ending.Expired -> text(TextKeys.APPROVAL_OUTCOME_EXPIRED, language, "id" to request.id)

        Ending.Withdrawn -> text(TextKeys.APPROVAL_OUTCOME_WITHDRAWN, language, "id" to request.id)

        is Ending.Answered -> when (val answer = ending.answer) {
            is ApprovalAnswer.Allowed -> if (answer.always) {
                text(
                    TextKeys.APPROVAL_OUTCOME_ALLOWED_ALWAYS,
                    language,
                    "id" to request.id,
                    "name" to named(answer.by),
                    "rule" to request.alwaysRules.joinToString(", ") { it.display },
                )
            } else {
                text(
                    TextKeys.APPROVAL_OUTCOME_ALLOWED,
                    language,
                    "id" to request.id,
                    "name" to named(answer.by),
                )
            }

            is ApprovalAnswer.Denied -> text(
                TextKeys.APPROVAL_OUTCOME_DENIED,
                language,
                "id" to request.id,
                "name" to named(answer.by),
            )

            else -> null
        }
    }

    /** [request] as the message an approver reads in [language]. */
    private fun render(request: ApprovalRequest, language: LanguageTag?): String = buildString {
        val turn = request.turn
        append(text(TextKeys.APPROVAL_TITLE, language, "id" to request.id)).append('\n')
        append(text(TextKeys.APPROVAL_AGENT, language, "agent" to turn.agent)).append('\n')
        append(text(TextKeys.APPROVAL_CHAT, language, "chat" to turn.chat)).append('\n')
        turn.actor?.let {
            append(text(TextKeys.APPROVAL_REQUESTER, language, "requester" to it.displayName)).append('\n')
        }
        append(text(TextKeys.APPROVAL_REASON, language, "reason" to request.reason)).append('\n')
        append(
            text(
                TextKeys.APPROVAL_TOOL,
                language,
                "tool" to request.tool,
                "risk" to request.risk.name.lowercase(),
            ),
        ).append("\n\n")
        append(body(request, language))
        append("\n\n").append(text(TextKeys.APPROVAL_ANSWER, language))
        if (request.alwaysRules.size == 1) {
            append(' ').append(
                text(TextKeys.APPROVAL_ALWAYS_RULE, language, "rule" to request.alwaysRules.first().display),
            )
        } else if (request.alwaysRules.size > 1) {
            append(' ').append(
                text(
                    TextKeys.APPROVAL_ALWAYS_RULES,
                    language,
                    "rules" to request.alwaysRules.joinToString(", ") { it.display },
                ),
            )
        }
    }

    /** What [request] is about in [language]: its preview, else its arguments. */
    private fun body(request: ApprovalRequest, language: LanguageTag?): String {
        val said = when (val preview = request.preview) {
            is ReviewPreview.Diff ->
                text(TextKeys.APPROVAL_FILE, language, "path" to preview.path) + "\n\n" + preview.unified

            is ReviewPreview.Command -> buildString {
                append(text(TextKeys.APPROVAL_DIRECTORY, language, "directory" to preview.cwd)).append('\n')
                append(
                    text(
                        TextKeys.APPROVAL_PROGRAM,
                        language,
                        "program" to (preview.executable ?: text(TextKeys.APPROVAL_PROGRAM_UNKNOWN, language)),
                    ),
                )
                if (preview.opaque) append(' ').append(text(TextKeys.APPROVAL_OPAQUE, language))
                append("\n\n").append(commandLine(preview.argv))
            }

            is ReviewPreview.Text -> preview.text

            else -> text(TextKeys.APPROVAL_ARGUMENTS, language) + "\n" + request.arguments
        }
        return if (said.length <= MAX_BODY) said else said.take(MAX_BODY) + "\n…"
    }

    /** One request on its way, with the messages that carry it by the chat they are in. */
    private class Pending(val request: ApprovalRequest) {
        val ending = CompletableDeferred<Ending>()

        /** The ids of the messages that carry the request, by the chat each was sent to. */
        val carried = ConcurrentHashMap<ChatAddress, MutableSet<String>>()
    }

    /** What a command names. */
    private sealed interface Resolution {
        data object None : Resolution

        class One(val id: String) : Resolution

        class Ambiguous(val ids: List<String>) : Resolution

        class Listing(val requests: List<ApprovalRequest>) : Resolution
    }

    private sealed interface Ending {
        val answer: ApprovalAnswer
        val logged: String

        class Answered(override val answer: ApprovalAnswer) : Ending {
            override val logged: String
                get() = when (answer) {
                    is ApprovalAnswer.Allowed -> "is allowed by ${answer.by.address}"
                    is ApprovalAnswer.Denied -> "is denied by ${answer.by.address}"
                    else -> "ends: $answer"
                }
        }

        data object Expired : Ending {
            override val answer: ApprovalAnswer = ApprovalAnswer.Expired
            override val logged: String = "expired"
        }

        data object Withdrawn : Ending {
            override val answer: ApprovalAnswer = ApprovalAnswer.Unavailable("the request was withdrawn")
            override val logged: String = "is withdrawn"
        }
    }

    private companion object {
        const val APPROVE = "approve"
        const val DENY = "deny"
        const val ALWAYS = "always"

        val UNREACHABLE = ApprovalAnswer.Unavailable("no administrator can be reached")

        /** How often the message that carries a request is tried, and how long between two tries. */
        const val APPROVAL_TRIES: Int = 3

        const val APPROVAL_RETRY_MILLIS: Long = 1_000
    }

    /** [key] in [language], with [args] put where they are named. */
    private fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String =
        texts.text(key, language, *args)

    /** The language of [chat], null when the runtime has no agent to ask and the host's language applies. */
    private suspend fun language(chat: ChatAddress): LanguageTag? = try {
        settings?.snapshot(chat)?.effectiveLanguage
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("The language of chat {} is unknown: {}", chat, e.toString())
        null
    }

    /** The language [request] is shown in [chat] with: the chat of its own turn keeps the language of that turn. */
    private suspend fun languageOf(request: ApprovalRequest, chat: ChatAddress): LanguageTag? =
        if (chat == request.turn.chat) request.turn.language else language(chat)
}

/** [user]'s name as an approver sees it. */
private fun named(user: ChatUser): String = user.displayName + user.username?.let { " (@$it)" }.orEmpty()

/** [argv] as one command line, each word that a shell would split or expand in single quotes. */
private fun commandLine(argv: List<String>): String = argv.joinToString(" ") { word ->
    val plain = word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in PLAIN }
    if (plain) word else "'${word.replace("'", "'\\''")}'"
}

/** The characters a shell takes as part of a word. */
private const val PLAIN = "-_./=:,+@%"

/** How much of an approval's body one chat message carries. */
private const val MAX_BODY = 1_500

private val logger = LoggerFactory.getLogger(OneBotApprovals::class.java)
