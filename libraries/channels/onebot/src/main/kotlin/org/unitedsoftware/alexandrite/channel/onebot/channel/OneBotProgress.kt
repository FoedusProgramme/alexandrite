package org.unitedsoftware.alexandrite.channel.onebot.channel

import org.unitedsoftware.alexandrite.channel.onebot.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import kotlin.jvm.Synchronized

/** The statuses a call has ended with, after which it is spoken of in the past tense. */
internal val ENDED: Set<ToolCallStatus> =
    setOf(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED, ToolCallStatus.BACKGROUNDED)

/**
 * The statuses a call is worth a line of its own for: the ones a summary line cannot say.
 *
 * A chat whose messages cannot be added to is told of its work in messages of its own, so a line per call would say
 * what the summary above it already said, once per window. What a summary cannot say, and so is told, is that a call
 * failed, was moved to the background, waits for an approval or did not run at all.
 */
private val NOTABLE: Set<ToolCallStatus> =
    setOf(ToolCallStatus.FAILED, ToolCallStatus.BACKGROUNDED, ToolCallStatus.WAITING_APPROVAL, ToolCallStatus.NOT_RUN)

/** The tool of a call that runs a sub-agent, whose line names the run. */
private const val DELEGATE_TOOL: String = "agent.delegate"

/** The characters one message about a stretch of steps may hold before its oldest lines are left out. */
internal const val MAX_ACTIVITY_CHARS: Int = 1500

/** What separates the work of one agent from the work of another in the summary line of a message. */
private const val AGENT_SEPARATOR: String = " / "

/** What separates two kinds of work of the same agent in the summary line of a message. */
private const val KIND_SEPARATOR: String = " · "

/**
 * One call of a turn, as much of it as a chat is told: what it is, how it is doing, and what it left behind.
 *
 * [run] is the sub-agent run a call belongs to, which a delegation is named by so that a chat tells two of them apart.
 */
internal class ProgressCall(
    val tool: String,
    val activity: ToolActivity,
    val title: String,
    val agent: AgentId?,
    var run: RunId? = null,
) {
    /** How the call is doing, which the update of the call sets. */
    var status: ToolCallStatus = ToolCallStatus.PENDING

    /** What the call left behind, which the update of the call sets. */
    var content: TurnEvent.Content? = null

    /** Whether the call delegates its task to a sub-agent, whose run its title names. */
    val delegates: Boolean get() = tool == DELEGATE_TOOL

    /** Whether the line of this call belongs to a sub-agent, or to the turn itself. */
    val by: AgentId? get() = agent
}

/** How many calls of each kind a conversation ended since the last message about it. */
internal class ProgressTally(val steps: List<ProgressCall>)

/**
 * What a conversation has done since the last thing said about it.
 *
 * A chat whose messages cannot be rewritten is told of the work in messages of its own, each a line that sums up the
 * kinds of call in it and a line for each call, the newest kept and the oldest left out when the message would be too
 * long. A call is told as it starts and again as it ends, at most once per interval.
 */
internal class OneBotProgress(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var toldAt: Long? = null

    /** How long this conversation waits now, which grows while it keeps working and starts over when it falls quiet. */
    private var interval: Long = intervalMillis
    private var opened = false
    private val calls = HashMap<ToolCallId, ProgressCall>()
    private val ended = ArrayList<ProgressCall>()

    /**
     * Notes that the call [id] of [activity] started, taken by [agent], which is null for a call of the turn itself.
     *
     * The call is returned when it is the first of its conversation, which is the one told at once, and null after.
     */
    @Synchronized
    fun started(
        id: ToolCallId,
        activity: ToolActivity,
        agent: AgentId?,
        tool: String = "",
        title: String = "",
        run: RunId? = null,
    ): ProgressCall? {
        val call = ProgressCall(tool, activity, title, agent, run)
        calls[id] = call
        if (opened) return null
        opened = true
        toldAt = now()
        return call
    }

    /** Notes that the call [id] ended with [status] and [content], and keeps it to be told. */
    @Synchronized
    fun ended(
        id: ToolCallId,
        status: ToolCallStatus = ToolCallStatus.COMPLETED,
        content: TurnEvent.Content? = null,
        run: RunId? = null,
    ) {
        val call = calls.remove(id) ?: return
        call.status = status
        call.content = content
        call.run = run ?: call.run
        ended += call
    }

    /**
     * What waits to be told whether or not the interval has passed, for a reply that is ending.
     *
     * Nothing waits once the reply is over, so its last steps are told.
     */
    @Synchronized
    fun flush(): ProgressTally? {
        if (ended.isEmpty()) return null
        toldAt = now()
        return ProgressTally(ended.toList()).also { ended.clear() }
    }

    /** What waits to be told, when it is worth telling: null while nothing waits or the interval has not passed. */
    @Synchronized
    fun take(): ProgressTally? {
        if (ended.isEmpty()) return null
        val told = toldAt
        val at = now()
        if (told != null && at - told < interval) return null
        // A conversation that keeps working is told less often, up to a ceiling; one that fell quiet starts over. A
        // burst of sub-agents then does not answer with a message every few seconds, and a slow turn is not left
        // silent for a minute.
        interval = if (told != null && at - told > interval * 2) {
            DEFAULT_INTERVAL_MS
        } else {
            minOf(interval * 2, DEFAULT_MAX_INTERVAL_MS)
        }
        toldAt = at
        return ProgressTally(ended.toList()).also { ended.clear() }
    }

    /** How many calls ended and wait to be told. */
    @Synchronized
    fun counted(): Int = ended.size

    /**
     * Whether this conversation holds nothing to tell and no call still running.
     *
     * Only the pacing of what is told is kept besides, so a conversation that holds nothing may be forgotten rather
     * than kept for as long as the channel runs.
     */
    @Synchronized
    fun idle(): Boolean = calls.isEmpty() && ended.isEmpty()

    internal companion object {
        /** How long a conversation works quietly before what it has ended since is told again. */
        const val DEFAULT_INTERVAL_MS: Long = 8_000

        /** The longest a conversation that keeps working waits between two messages about it. */
        const val DEFAULT_MAX_INTERVAL_MS: Long = 60_000
    }
}

/** What one call says as it starts: the present tense of what the channel has words for, and the call's own line. */
internal fun startedMessage(call: ProgressCall, texts: Texts, language: LanguageTag?): String =
    activityMessage(listOf(call), texts, language)

/**
 * What the calls of [tally] say once they ended: one line that sums up their kinds, then a line for each call.
 *
 * Lines the message cannot hold are left out from the oldest, which the line above them counts.
 */
internal fun summaryMessage(tally: ProgressTally, texts: Texts, language: LanguageTag?): String =
    activityMessage(tally.steps, texts, language)

/** [calls] as one message: their summary line, then their own lines. */
internal fun activityMessage(calls: List<ProgressCall>, texts: Texts, language: LanguageTag?): String {
    val summary = summaryLine(calls, texts, language)
    val lines = calls
        // A delegation has no line of its own: the summary already names the run it stands for and says whether it
        // still runs, so a line about the call would only say the same thing again in the words of the tool.
        .filter { it.status in NOTABLE && !it.delegates }
        .mapNotNull { callLine(it, texts, language) }
        .filter { it.isNotBlank() }
    if (lines.isEmpty()) return summary
    val room = MAX_ACTIVITY_CHARS - summary.length - 1
    var used = 0
    var kept = 0
    for (line in lines.asReversed()) {
        val next = used + line.length + if (kept > 0) 1 else 0
        val omitted = lines.size - kept - 1
        val notice = if (omitted > 0) omittedNotice(omitted, texts, language).length + 1 else 0
        if (next + notice > room) break
        used = next
        kept++
    }
    val omitted = lines.size - kept
    val shown = lines.takeLast(kept)
    val details = (if (omitted > 0) listOf(omittedNotice(omitted, texts, language)) + shown else shown)
        .joinToString("\n")
    return "$summary\n$details"
}

/**
 * The kinds of [calls] in the order each first appears, each delegation alone and each agent apart.
 *
 * A chat reads who did what, so the calls of a sub-agent are counted on their own.
 */
private fun groupedByKind(calls: List<ProgressCall>): Collection<List<ProgressCall>> {
    val byKind = LinkedHashMap<Pair<AgentId?, Any>, MutableList<ProgressCall>>()
    for (call in calls) {
        val kind: Any = if (call.delegates) call else call.activity
        byKind.getOrPut(call.by to kind) { mutableListOf() } += call
    }
    return byKind.values
}

/**
 * One phrase per kind of [calls], the work of each agent kept together, as `使用了 2 个工具 · 读取了 3 个文件`.
 *
 * The kinds of one agent are joined by a middle dot and the work of another agent stands beside them after a slash,
 * so that a chat reads where the turn's own work ends and a sub-agent's begins.
 */
private fun summaryLine(calls: List<ProgressCall>, texts: Texts, language: LanguageTag?): String {
    val byAgent = LinkedHashMap<AgentId?, MutableList<List<ProgressCall>>>()
    for (group in groupedByKind(calls)) byAgent.getOrPut(group.first().by) { mutableListOf() } += group
    return byAgent.entries.joinToString(AGENT_SEPARATOR) { (agent, kinds) ->
        val said = kinds.joinToString(KIND_SEPARATOR) { group ->
            val first = group.first()
            if (first.delegates) delegation(first, texts, language) else counted(group, texts, language)
        }
        // A delegation already names its run, where the turn's own calls need no name: only a sub-agent's other calls
        // are named, so that a chat reads whose they were.
        if (agent == null) {
            said
        } else {
            texts.text(TextKeys.ACTIVITY_CALL_BY_AGENT, language, "agent" to agent.value, "line" to said)
        }
    }
}

/** What a kind of [group] says: its verb in the tense of the group, and how many of it there were. */
private fun counted(group: List<ProgressCall>, texts: Texts, language: LanguageTag?): String {
    val activity = group.first().activity
    val ran = group.filterNot { it.status == ToolCallStatus.NOT_RUN }
    if (ran.isEmpty()) {
        val noun = noun(activity, group.size, texts, language)
        return texts.text(TextKeys.ACTIVITY_SUMMARY_NOT_RUN, language, "noun" to noun)
    }
    val past = ran.all { it.status in ENDED }
    val verb = texts.text(TextKeys.activityVerb(kind(activity), past), language)
    return "$verb ${noun(activity, ran.size, texts, language)}"
}

/** The delegation [call] stood for, named by the run it belongs to. */
private fun delegation(call: ProgressCall, texts: Texts, language: LanguageTag?): String {
    val key = if (call.status in ENDED) TextKeys.ACTIVITY_DELEGATE_PAST else TextKeys.ACTIVITY_DELEGATE_PRESENT
    return texts.text(key, language, "agent" to named(call))
}

/** What [call] is named by: its title, with the run beside it where the title does not already name that run. */
private fun named(call: ProgressCall): String {
    val run = call.run ?: return call.title
    return if (call.title.contains(run.value)) call.title else "${call.title} (${run.value})"
}

/** What one call did to something, as the words of its kind and the count of it. */
private fun noun(activity: ToolActivity, count: Int, texts: Texts, language: LanguageTag?): String =
    texts.text(TextKeys.activityNoun(kind(activity), count != 1), language, "count" to count)

/** One call's own line: what it is, and what became of it. */
private fun callLine(call: ProgressCall, texts: Texts, language: LanguageTag?): String {
    val title = call.title.take(MAX_TITLE_CHARS)
    val line = when (call.status) {
        ToolCallStatus.WAITING_APPROVAL ->
            texts.text(TextKeys.ACTIVITY_CALL_WAITING_APPROVAL, language, "tool" to call.tool, "title" to title)

        ToolCallStatus.NOT_RUN ->
            texts.text(TextKeys.ACTIVITY_CALL_NOT_RUN, language, "tool" to call.tool, "title" to title)

        ToolCallStatus.BACKGROUNDED ->
            texts.text(TextKeys.ACTIVITY_CALL_BACKGROUNDED, language, "tool" to call.tool, "title" to title)

        else -> title + suffix(call, texts, language)
    }
    val agent = call.by ?: return line
    return texts.text(TextKeys.ACTIVITY_CALL_BY_AGENT, language, "agent" to agent.value, "line" to line)
}

/** What an ended call's line adds after its title: an exit code, a diff's counts, or that it failed. */
private fun suffix(call: ProgressCall, texts: Texts, language: LanguageTag?): String {
    when (val content = call.content) {
        is TurnEvent.Content.Output -> EXIT_CODE.find(content.tail)?.groupValues?.get(1)?.let {
            return " — " + texts.text(TextKeys.ACTIVITY_CALL_EXIT, language, "code" to it)
        }

        is TurnEvent.Content.Preview -> (content.preview as? ReviewPreview.Diff)?.let {
            lineCounts(it.unified)
        }?.let { (added, removed) ->
            return " (" + texts.text(TextKeys.ACTIVITY_CALL_DIFF, language, "added" to added, "removed" to removed) +
                ")"
        }

        else -> Unit
    }
    return if (call.status == ToolCallStatus.FAILED) " — " + texts.text(TextKeys.ACTIVITY_CALL_FAILED, language) else ""
}

/** The line that counts the [omitted] calls a message could not hold. */
private fun omittedNotice(omitted: Int, texts: Texts, language: LanguageTag?): String = texts.text(
    if (omitted ==
        1
    ) {
        TextKeys.ACTIVITY_OMITTED_ONE
    } else {
        TextKeys.ACTIVITY_OMITTED_MANY
    },
    language,
    "count" to omitted,
)

/** The added and removed line counts of the unified diff [text], null when it changes nothing. */
private fun lineCounts(text: String): Pair<Int, Int>? {
    var added = 0
    var removed = 0
    for (line in text.lineSequence()) {
        when {
            line.startsWith("+++") || line.startsWith("---") -> Unit
            line.startsWith("+") -> added++
            line.startsWith("-") -> removed++
        }
    }
    return (added to removed).takeIf { added > 0 || removed > 0 }
}

/** The kind of [activity] the channel has words for; a kind this version does not know is work with tools. */
private fun kind(activity: ToolActivity): String = when (activity) {
    ToolActivity.READ -> "read"
    ToolActivity.LIST -> "list"
    ToolActivity.SEARCH -> "search"
    ToolActivity.EDIT -> "edit"
    ToolActivity.DELETE -> "delete"
    ToolActivity.MOVE -> "move"
    ToolActivity.EXECUTE -> "execute"
    ToolActivity.FETCH -> "fetch"
    ToolActivity.SEND -> "send"
    ToolActivity.THINK -> "think"
    else -> "other"
}

/** The characters of a call's title a message shows. */
private const val MAX_TITLE_CHARS: Int = 80

/** The exit code the tail of a call's output names. */
private val EXIT_CODE = Regex("""Exit code: (-?\d+)""")
