package org.unitedsoftware.alexandrite.channel.telegram.approval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.common.approval.alwaysRulesText
import org.unitedsoftware.alexandrite.channel.common.approval.decideApproval
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.api.AnswerCallbackQuery
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApi
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiException
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure
import org.unitedsoftware.alexandrite.channel.telegram.api.CallbackQuery
import org.unitedsoftware.alexandrite.channel.telegram.api.EditMessageReplyMarkup
import org.unitedsoftware.alexandrite.channel.telegram.api.InlineKeyboardButton
import org.unitedsoftware.alexandrite.channel.telegram.api.InlineKeyboardMarkup
import org.unitedsoftware.alexandrite.channel.telegram.format.FormattedText
import org.unitedsoftware.alexandrite.channel.telegram.format.MAX_MESSAGE_LENGTH
import org.unitedsoftware.alexandrite.channel.telegram.format.Span
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.channel.telegram.i18n.languageOrNull
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.channel.telegram.intake.ChatTitles
import org.unitedsoftware.alexandrite.channel.telegram.reply.Outbox
import org.unitedsoftware.alexandrite.channel.telegram.reply.SendLimiter
import org.unitedsoftware.alexandrite.channel.telegram.reply.Target
import org.unitedsoftware.alexandrite.channel.telegram.reply.targetOf
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.internal.text.cutStartWithEllipsis
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.internal.text.fittingLength
import org.unitedsoftware.alexandrite.internal.text.shellLine
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalTrust
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.TrustOffer
import org.unitedsoftware.alexandrite.sdk.channel.TrustOfferKind
import org.unitedsoftware.alexandrite.sdk.channel.TrustRoot
import org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * Shows approval requests and trust offers as messages with buttons, in the chat of an admin's own turn and else in the
 * admins' direct chats, and takes the first answer an admin gives within the configured time.
 */
@ChannelInstanceScoped
internal class ApprovalDesk(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val gate: AccessGate,
    private val outbox: Outbox,
    private val limiter: SendLimiter,
    private val api: BotApi,
    private val texts: Texts,
    private val settings: ChatSettings?,
    private val titles: ChatTitles,
    private val messages: ApprovalMessages,
) : Lifecycle {
    private val pending = ConcurrentHashMap<String, Pending>()
    private val work = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + work)

    /** The answer of the first admin who answers [request], else how the request ended. */
    suspend fun ask(request: ApprovalRequest): ApprovalAnswer = await(Question.Approval(request))

    /** The answer of the first admin who answers [offer], else how the offer ended. */
    suspend fun offer(offer: TrustOffer): ApprovalAnswer = await(Question.Offer(offer))

    private suspend fun await(question: Question): ApprovalAnswer {
        val chats = recipients(question.turn)
        if (chats.isEmpty()) return UNREACHABLE
        val shown = chats.map { chat ->
            val language = if (chat == question.turn.chat) question.turn.language else settings.languageOrNull(chat)
            Shown(chat, language, render(question, language) ?: return TOO_LARGE)
        }
        val waiting = Pending(question)
        if (pending.putIfAbsent(question.key, waiting) != null) {
            return ApprovalAnswer.Unavailable("another request has the id ${question.id}")
        }
        try {
            for (each in shown) post(waiting, each)
            if (waiting.posted.isEmpty()) return UNREACHABLE
            logger.info(
                "{} asks for {} {} in turn {} in {} chat(s)",
                instance.id,
                question.logged,
                question.id,
                question.turn.id,
                waiting.posted.size,
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
            pending.remove(question.key)
        }
    }

    /** Takes the press [query] of [presser], answering it with a toast where it changes nothing. */
    fun press(query: CallbackQuery, presser: ChatUser) {
        scope.launch { answer(query, presser) }
    }

    override suspend fun onDrain() {
        work.children.toList().joinAll()
    }

    override fun onStop() {
        pending.values.forEach { it.ending.complete(Ending.Answered(ApprovalAnswer.Unavailable("the bot stopped"))) }
    }

    /** The chats a question of [turn] is shown in: the chat of an admin's own turn, else the admins' direct chats. */
    private fun recipients(turn: TurnInfo): List<ChatAddress> {
        val chats = if (turn.actorIsOperator) {
            listOf(turn.chat)
        } else {
            config.adminUserIds.distinct().map { ChatAddress(instance.id, "$it") }
        }
        return chats.filter(gate::isPaired)
    }

    /** [question] as the messages that show it in [language], null when its head alone takes more than one. */
    private fun render(question: Question, language: LanguageTag?): Rendered? = when (question) {
        is Question.Approval -> render(question.request, language)

        is Question.Offer -> render(question.offer, language).let { (title, lines) -> message(title, lines, "") }
            ?.let(::Rendered)
    }

    /**
     * [request] as the messages that show it in [language], with its preview's start and the whole preview as a file
     * when the preview takes more than [MAX_PARTS]; null when its head alone takes more than one.
     */
    private fun render(request: ApprovalRequest, language: LanguageTag?): Rendered? {
        val turn = request.turn
        val title = text(TextKeys.APPROVAL_TITLE, language, "id" to request.id)
        val requester = turn.actor?.let(::named) ?: text(TextKeys.APPROVAL_NOBODY, language)
        val chat = named(turn.chat)
        val agents = request.chain.ifEmpty { listOf(turn.agent) }.joinToString(" → ")
        val lines = mutableListOf<Line>()
        request.taint?.let { taint ->
            val time = DateTimeFormatter.ofPattern(TIME_PATTERN, Locale.ROOT)
                .format(taint.at.atZone(turn.zone ?: ZoneOffset.UTC))
            lines += Line(text(TextKeys.APPROVAL_TAINTED, language, "time" to time), taint.reason)
        }
        lines += Line(text(TextKeys.APPROVAL_AGENT, language, "agent" to agents))
        lines += Line(text(TextKeys.APPROVAL_CHAT, language, "chat" to chat))
        lines += Line(text(TextKeys.APPROVAL_REQUESTER, language, "requester" to requester))
        startedBy(turn.kind)?.let { kind ->
            lines += Line(text(TextKeys.APPROVAL_STARTED_BY, language, "kind" to text(kind, language)))
        }
        lines += Line(text(TextKeys.APPROVAL_REASON, language, "reason" to request.reason))
        request.note?.let { lines += Line(text(TextKeys.APPROVAL_NOTE, language), it) }
        request.untrusted?.let {
            lines += Line(text(TextKeys.APPROVAL_UNTRUSTED, language), it)
            lines += roots(request.roots, turn.agent, language)
        }
        val risk = request.risk.name.lowercase()
        lines += Line(text(TextKeys.APPROVAL_TOOL, language, "tool" to request.tool, "risk" to risk))
        val (introduction, body) = preview(request, language)
        lines += introduction
        if (request.alwaysRules.size > 1 || (trustable(request) && request.alwaysRules.isNotEmpty())) {
            lines += Line(text(TextKeys.APPROVAL_ALWAYS_RULES, language, "rules" to rules(request)))
        }
        val count = body.trimEnd().lines().size
        val counted = Line(text(TextKeys.APPROVAL_LINES, language, "count" to count)).takeIf { count > MAX_CODE_LINES }
        message(title, lines + listOfNotNull(counted), body)?.let { return Rendered(it) }
        val name = "approval-${request.id}.${if (request.preview is ReviewPreview.Diff) "diff" else "txt"}"
        val attached = Line(text(TextKeys.APPROVAL_ATTACHED, language, "count" to count, "file" to name))
        val (head, spans) = head(title, lines + attached, body)
        val room = MAX_MESSAGE_LENGTH - OUTCOME_ROOM - head.length
        if (room <= 0) return null
        val whole = body.trimEnd()
        val parts = split(head, spans, whole.substring(0, cut(whole, room)).trimEnd()) ?: return null
        return Rendered(parts, OutboundFile.builder(name, "text/plain", whole.toByteArray()).asFile(true).build())
    }

    /** [offer] as the title and lines that show it in [language]. */
    private fun render(offer: TrustOffer, language: LanguageTag?): Pair<String, List<Line>> {
        val turn = offer.turn
        val chat = named(turn.chat)
        val (title, head, source) = when (offer.kind) {
            TrustOfferKind.INSTRUCTIONS -> Triple(
                TextKeys.OFFER_TITLE_INSTRUCTIONS,
                TextKeys.OFFER_INSTRUCTIONS,
                TextKeys.OFFER_INSTRUCTIONS_SOURCE,
            )

            TrustOfferKind.TAINTED -> Triple(TextKeys.OFFER_TITLE_MARKED, TextKeys.OFFER_MARKED, TextKeys.OFFER_SOURCE)
        }
        val lines = mutableListOf(
            Line(text(head, language, "chat" to chat)),
            Line(text(source, language), offer.untrusted),
            Line(text(TextKeys.APPROVAL_AGENT, language, "agent" to turn.agent)),
        )
        startedBy(turn.kind)?.let { kind ->
            lines += Line(text(TextKeys.APPROVAL_STARTED_BY, language, "kind" to text(kind, language)))
        }
        lines += roots(offer.roots, turn.agent, language)
        return text(title, language, "id" to offer.id) to lines
    }

    /** What trusting [roots] for [agent] covers, a line each, and the warnings that go with their kinds. */
    private fun roots(roots: List<TrustRoot>, agent: AgentId, language: LanguageTag?): List<Line> {
        val kinds = roots.map { it.kind }.toSet()
        return roots.map { Line(text(covers(it.kind), language, "agent" to agent), it.name) } + listOfNotNull(
            Line(text(TextKeys.APPROVAL_TRUST_FOLDER_WARNING, language)).takeIf { TrustRootKind.FOLDER in kinds },
            Line(text(TextKeys.APPROVAL_TRUST_PERSON_WARNING, language)).takeIf { TrustRootKind.PERSON in kinds },
        )
    }

    /** The messages of a question headed [title] in bold and [lines], with [body] as code below them. */
    private fun message(title: String, lines: List<Line>, body: String): List<FormattedText>? {
        val (head, spans) = head(title, lines, body)
        return split(head, spans, body)
    }

    /** The head of a question's first message, [title] in bold and [lines], and its spans. */
    private fun head(title: String, lines: List<Line>, body: String): Pair<String, List<Span>> {
        val head = StringBuilder(title)
        val spans = mutableListOf(Span("bold", 0, title.length))
        for (line in lines) {
            head.append('\n').append(line.text)
            val code = line.code ?: continue
            head.append(' ')
            spans += Span("code", head.length, code.length)
            head.append(code)
        }
        if (body.isNotBlank()) head.append("\n\n")
        return "$head" to spans
    }

    /** The lines that introduce what [request] shows as code, and that code: its preview, else its arguments. */
    private fun preview(request: ApprovalRequest, language: LanguageTag?): Pair<List<Line>, String> {
        val shown = when (val preview = request.preview) {
            is ReviewPreview.Diff -> listOfNotNull(
                text(TextKeys.APPROVAL_FILE, language, "path" to preview.path),
                text(TextKeys.APPROVAL_NOT_ASCII, language).takeIf { ReviewText.beyondAscii("${preview.path}") },
            ).map(::Line) to preview.unified

            is ReviewPreview.Command -> {
                val program = preview.executable ?: text(TextKeys.APPROVAL_PROGRAM_UNKNOWN, language)
                val segments = preview.segments
                val names = listOf("${preview.cwd}", "$program") + preview.argv +
                    segments.flatMap { listOf("${it.cwd}") + it.argv }
                val contained = request.contained
                listOfNotNull(
                    text(TextKeys.APPROVAL_DIRECTORY, language, "directory" to preview.cwd),
                    text(TextKeys.APPROVAL_PROGRAM, language, "program" to program).takeIf { segments.isEmpty() },
                    text(TextKeys.APPROVAL_NOT_ASCII, language).takeIf { names.any(ReviewText::beyondAscii) },
                    contained?.let { text(TextKeys.APPROVAL_SANDBOX, language, "sandbox" to it.environmentName) }
                        ?: text(TextKeys.APPROVAL_HOST, language),
                    contained?.hosts?.takeIf { it.isNotEmpty() }
                        ?.let { text(TextKeys.APPROVAL_NETWORK, language, "hosts" to it.joinToString()) },
                    text(TextKeys.APPROVAL_BACKGROUND, language).takeIf { request.background },
                    text(TextKeys.APPROVAL_OPAQUE, language).takeIf { preview.opaque },
                    text(TextKeys.APPROVAL_STEPS, language, "count" to segments.size).takeIf { segments.isNotEmpty() },
                    text(TextKeys.APPROVAL_TRUST_OPAQUE, language).takeIf {
                        preview.opaque && request.untrusted != null &&
                            request.roots.any { it.kind == TrustRootKind.PROGRAM }
                    },
                ).map(::Line) + listOfNotNull(
                    preview.monitor?.let { Line(text(TextKeys.APPROVAL_MONITOR, language), it) },
                ) to shellLine(preview.argv) + steps(segments)
            }

            is ReviewPreview.Text -> emptyList<Line>() to preview.text

            is ReviewPreview.OutgoingFile -> return listOfNotNull(
                text(TextKeys.APPROVAL_FILE, language, "path" to preview.path),
                text(TextKeys.APPROVAL_NOT_ASCII, language).takeIf { ReviewText.beyondAscii("${preview.path}") },
                text(TextKeys.APPROVAL_SIZE, language, "bytes" to preview.size, "readable" to readable(preview.size)),
                text(TextKeys.APPROVAL_SHA256, language, "sha256" to preview.sha256),
                text(TextKeys.APPROVAL_DESTINATION, language, "chat" to named(preview.destination)),
                text(TextKeys.APPROVAL_CAPTION, language).takeIf { preview.caption != null },
            ).map(::Line) to preview.caption.orEmpty()

            else -> null
        }
        if (shown != null && shown.second.isNotBlank()) return shown
        return listOf(Line(text(TextKeys.APPROVAL_ARGUMENTS, language))) to request.arguments
    }

    /** [chat] as an approver sees it: its title where the channel knows one, and its address. */
    private fun named(chat: ChatAddress): String =
        titles.title(chat)?.let { "${ReviewText.escape(it)} ($chat)" } ?: "$chat"

    /** Sends [shown] to its chat, recording and noting each part as it is sent, undone if a later part fails. */
    private suspend fun post(waiting: Pending, shown: Shown) {
        val question = waiting.question
        val target = targetOf(shown.chat) ?: return
        val keyboard = keyboard(question, shown.language)
        val sent = mutableListOf<Posted>()
        var attachment: Long? = null
        try {
            shown.rendered.attachment?.let { file ->
                attachment = outbox.paced(target) { outbox.sendDocument(target, file) }
                    .also { messages.note(target.chat, it, waiting.noted) }
            }
            for ((index, part) in shown.parts.withIndex()) {
                val last = index == shown.parts.lastIndex
                val id = outbox.paced(target) { outbox.sendText(target, part, null, keyboard.takeIf { last }) }
                messages.note(target.chat, id, waiting.noted)
                val posted = Posted(shown.chat, target, id, part, shown.language)
                waiting.posted += posted
                sent += posted
            }
        } catch (e: BotApiException) {
            outbox.checkToken(e)
            logger.warn(
                "{} could not show {} {} in chat {}: {}",
                instance.id,
                question.logged,
                question.id,
                shown.chat,
                e.message,
            )
            sent.forEach {
                waiting.posted.remove(it)
                delete(it.chat, it.target, it.id)
            }
            attachment?.let { delete(shown.chat, target, it) }
        }
    }

    /** Deletes the message [id] of a question, so no part without buttons is left in the chat. */
    private suspend fun delete(chat: ChatAddress, target: Target, id: Long) {
        try {
            outbox.paced(target) { api.deleteMessage(target.chat, id) }
        } catch (e: BotApiException) {
            outbox.checkToken(e)
            logger.warn("{} could not delete an approval message in chat {}: {}", instance.id, chat, e.message)
        }
    }

    private fun keyboard(question: Question, language: LanguageTag?): InlineKeyboardMarkup = when (question) {
        is Question.Approval -> keyboard(question.request, language)
        is Question.Offer -> keyboard(question.offer, language)
    }

    /**
     * Allow and deny first, then one button that allows for good, trusts the roots or does both: trusting where the
     * call would taint and roots are offered, allowing for good where the request offers it. A call that would taint
     * with no roots to trust offers instead to allow it and trust its output this once.
     */
    private fun keyboard(request: ApprovalRequest, language: LanguageTag?): InlineKeyboardMarkup {
        val allowKey = if (request.untrusted != null) TextKeys.APPROVAL_ALLOW_MARK else TextKeys.APPROVAL_ALLOW
        val allow = InlineKeyboardButton(text(allowKey, language), "$PREFIX${request.id}:$ALLOW")
        val deny = InlineKeyboardButton(text(TextKeys.APPROVAL_DENY, language), "$PREFIX${request.id}:$DENY")
        val roots = request.roots.takeIf { trustable(request) }.orEmpty()
        val always = request.alwaysRules.isNotEmpty()
        val more = when {
            roots.isNotEmpty() -> {
                val (one, all) = if (always) {
                    TextKeys.APPROVAL_ALWAYS_TRUST_ROOT to TextKeys.APPROVAL_ALWAYS_TRUST_ALL
                } else {
                    TextKeys.APPROVAL_ALLOW_TRUST_ROOT to TextKeys.APPROVAL_ALLOW_TRUST_ALL
                }
                val label = if (roots.size == 1) {
                    text(one, language, "root" to label(roots.single(), language))
                } else {
                    text(all, language, "count" to roots.size)
                }
                InlineKeyboardButton(label, "$PREFIX${request.id}:${if (always) ALWAYS_TRUST else TRUST}")
            }

            request.untrusted != null ->
                InlineKeyboardButton(text(TextKeys.APPROVAL_ALLOW_ONCE, language), "$PREFIX${request.id}:$ONCE")

            always -> {
                val label = text(TextKeys.APPROVAL_ALWAYS, language, "rule" to ending(rules(request)))
                InlineKeyboardButton(label, "$PREFIX${request.id}:$ALWAYS")
            }

            else -> null
        }
        return InlineKeyboardMarkup(listOfNotNull(listOf(allow, deny), more?.let(::listOf)))
    }

    /** Keeping first, then trusting; for project instructions trusting first, then not now. */
    private fun keyboard(offer: TrustOffer, language: LanguageTag?): InlineKeyboardMarkup {
        val roots = offer.roots
        if (offer.kind == TrustOfferKind.INSTRUCTIONS) {
            val agent = offer.turn.agent
            val trust = if (roots.size > 1) {
                text(TextKeys.OFFER_TRUST_FOLDERS, language, "count" to roots.size, "agent" to agent)
            } else {
                text(TextKeys.OFFER_TRUST_FOLDER, language, "root" to label(roots.single(), language), "agent" to agent)
            }
            return InlineKeyboardMarkup(
                listOf(
                    listOf(
                        InlineKeyboardButton(trust, "$OFFER_PREFIX${offer.id}:$TRUST"),
                        InlineKeyboardButton(text(TextKeys.OFFER_NOT_NOW, language), "$OFFER_PREFIX${offer.id}:$KEEP"),
                    ),
                ),
            )
        }
        val keep = text(TextKeys.OFFER_KEEP, language)
        val trust = if (roots.size > 1) {
            text(TextKeys.OFFER_TRUST_CLEAR_ALL, language, "count" to roots.size)
        } else {
            text(TextKeys.OFFER_TRUST_CLEAR, language, "root" to label(roots.single(), language))
        }
        return InlineKeyboardMarkup(
            listOf(
                listOf(
                    InlineKeyboardButton(keep, "$OFFER_PREFIX${offer.id}:$KEEP"),
                    InlineKeyboardButton(trust, "$OFFER_PREFIX${offer.id}:$TRUST"),
                ),
            ),
        )
    }

    /** [root] as a button names it: its kind and its name, cut at its start to fit. */
    private fun label(root: TrustRoot, language: LanguageTag?): String {
        val kind = text(kindWord(root.kind), language)
        return "$kind ${cutStartWithEllipsis(root.name, (MAX_LABEL - kind.length - 1).coerceAtLeast(MIN_NAME))}"
    }

    private suspend fun answer(query: CallbackQuery, presser: ChatUser) {
        val press = query.data?.let(PRESS::matchEntire)?.destructured
        val chat = query.message?.let { ChatAddress(instance.id, "${it.chat.id}") }
        val language = chat?.let { settings.languageOrNull(it) }
        val toast = when {
            press == null -> null

            !presser.isOperator -> {
                logger.info("{}: {}, who is no admin, pressed a button of an approval", instance.id, presser.address)
                text(TextKeys.APPROVAL_ADMINS_ONLY, language)
            }

            else -> {
                val (prefix, id, action) = press
                val waiting = pending["$prefix$id"]
                val answer = waiting?.question?.let { answerOf(it, action, presser) }
                if (answer != null && waiting.ending.complete(Ending.Answered(answer))) {
                    null
                } else {
                    text(TextKeys.APPROVAL_NOT_PENDING, language)
                }
            }
        }
        try {
            limiter.message(null) { api.answerCallbackQuery(AnswerCallbackQuery(query.id, toast)) }
        } catch (e: BotApiException) {
            outbox.checkToken(e)
            logger.debug("{} could not answer a button press: {}", instance.id, e.message)
        }
    }

    /** What pressing [action] under [question] answers, null for a button that the question does not show. */
    private fun answerOf(question: Question, action: String, presser: ChatUser): ApprovalAnswer? = when (question) {
        is Question.Approval -> {
            val request = question.request
            when (action) {
                // The presser is already known an operator by the time answerOf() runs, so decideApproval() never
                // refuses these; it only spares this file the "always grants nothing with no rule to grant" rule
                // channel-common, onebot and qqbot now share.
                DENY -> decideApproval(request, presser, allow = false, always = false)

                ALLOW -> decideApproval(request, presser, allow = true, always = false)

                ALWAYS -> decideApproval(request, presser, allow = true, always = true)

                TRUST -> ApprovalAnswer.Allowed(presser, trust = ApprovalTrust.ROOTS).takeIf { trustable(request) }

                ALWAYS_TRUST ->
                    ApprovalAnswer.Allowed(
                        presser,
                        always = request.alwaysRules.isNotEmpty(),
                        trust = ApprovalTrust.ROOTS,
                    )
                        .takeIf { trustable(request) }

                ONCE -> ApprovalAnswer.Allowed(presser, trust = ApprovalTrust.OUTPUT_ONCE)
                    .takeIf { request.untrusted != null && !trustable(request) }

                else -> null
            }
        }

        is Question.Offer -> when (action) {
            KEEP -> ApprovalAnswer.Denied(presser)
            TRUST -> ApprovalAnswer.Allowed(presser, trust = ApprovalTrust.ROOTS)
            else -> null
        }
    }

    /** Shows how [waiting] ended under each of its messages and takes their buttons away. */
    private fun conclude(waiting: Pending, ending: Ending) {
        waiting.noted.state = ending.state(waiting.question)
        val posted = waiting.posted.toList()
        if (posted.isEmpty()) return
        val question = waiting.question
        logger.info(
            "{}: {} {} of turn {} {}",
            instance.id,
            question.logged,
            question.id,
            question.turn.id,
            ending.logged,
        )
        scope.launch {
            for (message in posted) {
                val line = outcome(question, ending, message.language) ?: continue
                val shown = message.text
                edit(message, FormattedText("${shown.text}\n\n$line", shown.entities))
            }
        }
    }

    private suspend fun edit(message: Posted, text: FormattedText) {
        try {
            outbox.paced(message.target) { outbox.editText(message.target, message.id, text) }
        } catch (e: BotApiException) {
            if (e.failure == BotApiFailure.NOT_MODIFIED) return
            outbox.checkToken(e)
            logger.warn(
                "{} could not show the outcome of an approval in chat {}: {}",
                instance.id,
                message.chat,
                e.message,
            )
            try {
                outbox.paced(message.target) {
                    api.editMessageReplyMarkup(EditMessageReplyMarkup(message.target.chat, message.id))
                }
            } catch (e: BotApiException) {
                logger.warn("{} could not take the buttons away in chat {}: {}", instance.id, message.chat, e.message)
            }
        }
    }

    /** The line that tells how [question] ended in [language], null for no line. */
    private fun outcome(question: Question, ending: Ending, language: LanguageTag?): String? = when (question) {
        is Question.Approval -> outcome(question.request, ending, language)
        is Question.Offer -> outcome(question.offer, ending, language)
    }

    private fun outcome(request: ApprovalRequest, ending: Ending, language: LanguageTag?): String? = when (ending) {
        Ending.Expired -> text(TextKeys.APPROVAL_EXPIRED, language)

        Ending.Withdrawn -> text(TextKeys.APPROVAL_WITHDRAWN, language)

        is Ending.Answered -> when (val answer = ending.answer) {
            is ApprovalAnswer.Allowed -> {
                val rule = request.alwaysRules.firstOrNull()?.takeIf { answer.always }?.let { ending(rules(request)) }
                val name = named(answer.by)
                val trusted = answer.trust == ApprovalTrust.ROOTS
                when {
                    rule != null && trusted -> text(
                        TextKeys.APPROVAL_ALLOWED_ALWAYS_TRUSTED,
                        language,
                        "name" to name,
                        "rule" to rule,
                        "roots" to listed(request.roots),
                    )

                    rule != null -> text(TextKeys.APPROVAL_ALLOWED_ALWAYS, language, "name" to name, "rule" to rule)

                    answer.trust == ApprovalTrust.OUTPUT_ONCE ->
                        text(TextKeys.APPROVAL_ALLOWED_ONCE, language, "name" to name)

                    trusted ->
                        text(
                            TextKeys.APPROVAL_ALLOWED_TRUSTED,
                            language,
                            "name" to name,
                            "roots" to listed(request.roots),
                        )

                    request.untrusted != null -> text(TextKeys.APPROVAL_ALLOWED_MARKED, language, "name" to name)

                    else -> text(TextKeys.APPROVAL_ALLOWED, language, "name" to name)
                }
            }

            is ApprovalAnswer.Denied -> text(TextKeys.APPROVAL_DENIED, language, "name" to named(answer.by))

            else -> null
        }
    }

    private fun outcome(offer: TrustOffer, ending: Ending, language: LanguageTag?): String? {
        if (offer.kind == TrustOfferKind.INSTRUCTIONS) return instructionsOutcome(offer, ending, language)
        return when (ending) {
            Ending.Expired -> text(TextKeys.OFFER_EXPIRED_MARKED, language)

            Ending.Withdrawn -> text(TextKeys.OFFER_WITHDRAWN, language)

            is Ending.Answered -> when (val answer = ending.answer) {
                is ApprovalAnswer.Allowed ->
                    text(TextKeys.OFFER_TRUSTED, language, "name" to named(answer.by), "roots" to listed(offer.roots))

                is ApprovalAnswer.Denied -> text(TextKeys.OFFER_KEPT, language, "name" to named(answer.by))

                else -> null
            }
        }
    }

    private fun instructionsOutcome(offer: TrustOffer, ending: Ending, language: LanguageTag?): String? =
        when (ending) {
            Ending.Expired -> text(TextKeys.OFFER_EXPIRED_INSTRUCTIONS, language)

            Ending.Withdrawn -> text(TextKeys.OFFER_WITHDRAWN, language)

            is Ending.Answered -> when (val answer = ending.answer) {
                is ApprovalAnswer.Allowed -> text(
                    TextKeys.OFFER_TRUSTED_INSTRUCTIONS,
                    language,
                    "name" to named(answer.by),
                    "roots" to listed(offer.roots),
                    "agent" to offer.turn.agent,
                )

                is ApprovalAnswer.Denied -> text(TextKeys.OFFER_DECLINED, language, "name" to named(answer.by))

                else -> null
            }
        }

    private fun text(key: String, language: LanguageTag?, vararg args: Pair<String, Any?>): String =
        texts.text(key, language, *args)

    /** A line of a request's head, ending in [code] set apart as text that a model or untrusted content wrote. */
    private class Line(val text: String, val code: String? = null)

    /** A question that the desk shows: an approval request or a trust offer. */
    private sealed interface Question {
        val id: String
        val turn: TurnInfo

        /** The key of the question among the pending ones, its callback prefix and its id. */
        val key: String

        /** What the log calls it. */
        val logged: String

        class Approval(val request: ApprovalRequest) : Question {
            override val id: String get() = request.id
            override val turn: TurnInfo get() = request.turn
            override val key: String get() = "$PREFIX$id"
            override val logged: String get() = "approval of ${request.tool}"
        }

        class Offer(val offer: TrustOffer) : Question {
            override val id: String get() = offer.id
            override val turn: TurnInfo get() = offer.turn
            override val key: String get() = "$OFFER_PREFIX$id"
            override val logged: String get() = "trust offer"
        }
    }

    /** One question on its way, with the messages that carry its buttons. */
    private class Pending(val question: Question) {
        val ending = CompletableDeferred<Ending>()
        val posted: MutableList<Posted> = CopyOnWriteArrayList()
        val noted = when (question) {
            is Question.Approval -> ApprovalMessages.Request(question.id, question.request.tool)
            is Question.Offer -> ApprovalMessages.Request(question.id, null)
        }
    }

    /** The messages of a question, and the file that holds a preview too long for them. */
    private class Rendered(val parts: List<FormattedText>, val attachment: OutboundFile? = null)

    /** The messages of a request in [chat] in [language]. */
    private class Shown(val chat: ChatAddress, val language: LanguageTag?, val rendered: Rendered) {
        val parts: List<FormattedText> get() = rendered.parts
    }

    /** The message [id] that carries the buttons of a request in [chat], reading [text]. */
    private class Posted(
        val chat: ChatAddress,
        val target: Target,
        val id: Long,
        val text: FormattedText,
        val language: LanguageTag?,
    )

    private sealed interface Ending {
        val answer: ApprovalAnswer
        val logged: String

        /** How [question] ended, as a quote of one of its messages tells. */
        fun state(question: Question): String

        class Answered(override val answer: ApprovalAnswer) : Ending {
            override val logged: String
                get() = when (answer) {
                    is ApprovalAnswer.Allowed ->
                        "is allowed by ${answer.by.address}" +
                            when (answer.trust) {
                                ApprovalTrust.ROOTS -> ", trusted"
                                ApprovalTrust.OUTPUT_ONCE -> ", its output trusted once"
                                else -> ""
                            }

                    is ApprovalAnswer.Denied -> "is denied by ${answer.by.address}"

                    else -> "ends: $answer"
                }

            override fun state(question: Question): String = when (answer) {
                is ApprovalAnswer.Allowed -> when {
                    question is Question.Offer -> "trusted by "
                    answer.always && answer.trust != ApprovalTrust.NONE -> "allowed for good and trusted by "
                    answer.always -> "allowed for good by "
                    answer.trust != ApprovalTrust.NONE -> "allowed and trusted by "
                    else -> "allowed by "
                } + named(answer.by)

                is ApprovalAnswer.Denied -> when {
                    question !is Question.Offer -> "denied by "
                    question.offer.kind == TrustOfferKind.INSTRUCTIONS -> "declined by "
                    else -> "marked tainted by "
                } + named(answer.by)

                else -> "no longer waiting"
            }
        }

        data object Expired : Ending {
            override val answer: ApprovalAnswer = ApprovalAnswer.Expired
            override val logged: String = "expired"

            override fun state(question: Question): String = "expired"
        }

        data object Withdrawn : Ending {
            override val answer: ApprovalAnswer = ApprovalAnswer.Unavailable("the request was withdrawn")
            override val logged: String = "is withdrawn"

            override fun state(question: Question): String = "withdrawn"
        }
    }

    /** Whether [request] offers to trust its roots: it would taint, and offers some. */
    private fun trustable(request: ApprovalRequest): Boolean = request.untrusted != null && request.roots.isNotEmpty()

    private companion object {
        const val PREFIX = "apv:"
        const val OFFER_PREFIX = "tro:"
        const val ALLOW = "a"
        const val ALWAYS = "A"
        const val TRUST = "t"
        const val ALWAYS_TRUST = "T"
        const val ONCE = "o"
        const val DENY = "d"
        const val KEEP = "k"
        val PRESS = Regex("(apv:|tro:)([A-Za-z0-9_-]{1,32}):([aAtTodk])")

        val UNREACHABLE = ApprovalAnswer.Unavailable("no administrator can be reached")
        val TOO_LARGE = ApprovalAnswer.Unavailable("too large to review")
    }
}

/** The text key of the line that names what trusting a root of [kind] covers. */
private fun covers(kind: TrustRootKind): String = when (kind) {
    TrustRootKind.FOLDER -> TextKeys.APPROVAL_TRUSTS_FOLDER
    TrustRootKind.DOMAIN -> TextKeys.APPROVAL_TRUSTS_DOMAIN
    TrustRootKind.PROGRAM -> TextKeys.APPROVAL_TRUSTS_PROGRAM
    TrustRootKind.PERSON -> TextKeys.APPROVAL_TRUSTS_PERSON
    TrustRootKind.TOOL_SOURCE -> TextKeys.APPROVAL_TRUSTS_SOURCE
    else -> TextKeys.APPROVAL_TRUSTS_OTHER
}

/** The text key of the word for a root of [kind]. */
private fun kindWord(kind: TrustRootKind): String = when (kind) {
    TrustRootKind.FOLDER -> TextKeys.TRUST_KIND_FOLDER
    TrustRootKind.DOMAIN -> TextKeys.TRUST_KIND_DOMAIN
    TrustRootKind.PROGRAM -> TextKeys.TRUST_KIND_PROGRAM
    TrustRootKind.PERSON -> TextKeys.TRUST_KIND_PERSON
    TrustRootKind.TOOL_SOURCE -> TextKeys.TRUST_KIND_SOURCE
    else -> TextKeys.TRUST_KIND_OTHER
}

/** The names of [roots], each cut at its start, as one text. */
private fun listed(roots: List<TrustRoot>): String = roots.joinToString(", ") { ending(it.name) }

/** The rules that allowing [request] for good grants, as one text. */
private fun rules(request: ApprovalRequest): String = alwaysRulesText(request.alwaysRules)

/** [segments] one per line under a blank one, each after how it follows the one before it, none for no segments. */
private fun steps(segments: List<CommandSegment>): String = segments.withIndex().joinToString("") { (index, segment) ->
    val joint = segment.joint?.let { "${it.operator} " }.orEmpty()
    val variables = segment.environment.entries.joinToString("") { (name, value) ->
        "${shellLine(listOf("$name=$value"))} "
    }
    (if (index == 0) "\n\n" else "\n") + "${index + 1}. $joint$variables${shellLine(segment.argv)}"
}

/** [user]'s name as an approver sees it, escaped. */
private fun named(user: ChatUser): String =
    shortened(ReviewText.escape(user.displayName) + user.username?.let { " (@${ReviewText.escape(it)})" }.orEmpty())

/** [text], cut at its start to [MAX_SHOWN] characters, so that a rule keeps its program and first word. */
private fun ending(text: String): String = cutStartWithEllipsis(text, MAX_SHOWN)

private fun shortened(text: String): String = cutWithEllipsis(text, MAX_SHOWN)

/** [bytes] in the largest unit that keeps a whole number in front. */
private fun readable(bytes: Long): String {
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = "B"
    for (next in units) {
        if (value < UNIT) break
        value /= UNIT
        unit = next
    }
    return if (unit == "B") "$bytes B" else String.format(Locale.ROOT, "%.1f %s", value, unit)
}

/** The text key of what started a turn of [kind] that its requester did not send now, null for a message. */
private fun startedBy(kind: TurnKind): String? = when (kind) {
    TurnKind.MESSAGE, TurnKind.COMMAND, TurnKind.APPROVAL -> null
    TurnKind.REMINDER -> TextKeys.APPROVAL_KIND_REMINDER
    TurnKind.JOB -> TextKeys.APPROVAL_KIND_JOB
    TurnKind.DELEGATED -> TextKeys.APPROVAL_KIND_DELEGATED
    TurnKind.HEARTBEAT -> TextKeys.APPROVAL_KIND_HEARTBEAT
    TurnKind.AGENT_MESSAGE -> TextKeys.APPROVAL_KIND_AGENT_MESSAGE
    else -> TextKeys.APPROVAL_KIND_OTHER
}

/**
 * [head] with its [spans] and [body] as code, in messages that leave room for an outcome and number every message after
 * the first; null when they take more than [MAX_PARTS].
 */
private fun split(head: String, spans: List<Span>, body: String): List<FormattedText>? {
    val chunks = mutableListOf<String>()
    var rest = body.trimEnd()
    var room = MAX_MESSAGE_LENGTH - OUTCOME_ROOM - head.length
    if (room <= 0) return null
    while (true) {
        if (chunks.size == MAX_PARTS) return null
        val end = cut(rest, room)
        chunks += rest.substring(0, end).trimEnd()
        rest = rest.substring(end).removePrefix("\n")
        if (rest.isEmpty()) break
        room = MAX_MESSAGE_LENGTH - OUTCOME_ROOM - NUMBER_ROOM
    }
    return chunks.mapIndexed { index, chunk ->
        val prefix = if (index == 0) head else "(${index + 1}/${chunks.size})\n"
        val shown = (if (index == 0) spans.filter { it.length > 0 } else emptyList()) +
            listOfNotNull(Span("pre", prefix.length, chunk.length).takeIf { chunk.isNotEmpty() })
        FormattedText(prefix + chunk, shown)
    }
}

/** Where [text] is cut to fit [room]: at its last line break that fits, else at [room] outside a surrogate pair. */
private fun cut(text: String, room: Int): Int {
    if (text.length <= room) return text.length
    val lineBreak = text.lastIndexOf('\n', room)
    if (lineBreak > 0) return lineBreak
    return fittingLength(text, room)
}

/** How many messages one request may take. */
private const val MAX_PARTS = 4

private const val UNIT = 1024.0

/** The most lines of a preview that a request shows without counting them in its head. */
private const val MAX_CODE_LINES = 12

private const val TIME_PATTERN = "yyyy-MM-dd HH:mm xxx"

/** The characters a request's last message keeps free for the line that tells how it ended. */
private const val OUTCOME_ROOM = 512

/** The characters a later message of a request keeps free for its number. */
private const val NUMBER_ROOM = 16

/** The characters of a name or a rule that a request shows. */
private const val MAX_SHOWN = 64

/** The characters of a root that a button shows, its kind included. */
private const val MAX_LABEL = 40

/** The characters of a root's name that a button shows at least. */
private const val MIN_NAME = 16

private val logger = LoggerFactory.getLogger(ApprovalDesk::class.java)
