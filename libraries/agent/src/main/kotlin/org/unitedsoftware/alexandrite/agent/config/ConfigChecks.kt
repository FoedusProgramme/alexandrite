package org.unitedsoftware.alexandrite.agent.config

import org.unitedsoftware.alexandrite.agent.permission.RuleEntry
import org.unitedsoftware.alexandrite.agent.tool.ToolGlob
import org.unitedsoftware.alexandrite.internal.path.resolveConfigured
import org.unitedsoftware.alexandrite.internal.sandbox.HostPattern
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** The ids that an instruction file's `when` may name. */
internal val CONDITION_IDS: List<String> = ChatKind.entries.map { it.id } + TurnKind.entries.map { it.id }

/** The permission modes a config entry or a created profile may set: every mode but [PermissionMode.BYPASS]. */
internal val CONFIGURABLE_MODES = PermissionMode.entries - PermissionMode.BYPASS

/** Throws when [agents] break a rule that the config alone decides. */
internal fun checkAgents(agents: Map<String, AgentConfig>) {
    for ((id, agent) in agents) checkAgent("agents.$id", id, agent)
    checkDefaults(agents)
    checkHomes(agents)
}

/** The home chat [home] on the instance [key] names, null when it names none. */
internal fun homeChat(key: String, home: String): ChatAddress? = ChatAddress.parseOrNull("$key:$home")

/**
 * [entry] made absolute against the directory of the config file in [paths]; a [ConfigException] at [key] names it
 * [what] when it is no path, or relative where the host read no config file.
 */
internal fun configuredPath(paths: HostPaths, key: String, what: String, entry: String): Path {
    val path = try {
        resolveConfigured(entry, paths.configFile?.parent)
    } catch (e: InvalidPathException) {
        throw ConfigException(key, "$what '$entry' is no valid path", e)
    }
    return path ?: throw ConfigException(
        key,
        "$what '$entry' is relative, but the host read no config file to resolve it against: give an absolute path",
    )
}

internal fun isAgentId(id: String): Boolean = PluginIds.PATTERN.matches(id)

internal fun isOneLine(text: String): Boolean = text.isNotBlank() && text.none(Char::isISOControl)

internal fun reasoningOf(text: String): ReasoningEffort? = ReasoningEffort.entries.firstOrNull { it.id == text }

internal fun configurableModeOf(text: String): PermissionMode? = CONFIGURABLE_MODES.firstOrNull { it.id == text }

/** The first of [globs] that is no tool glob, null when all are. */
internal fun invalidGlob(globs: List<String>): String? = globs.firstOrNull { !ToolGlob.isValid(it) }

internal fun isPath(text: String): Boolean = text.isNotBlank() &&
    try {
        Paths.get(text)
        true
    } catch (e: InvalidPathException) {
        false
    }

private fun checkAgent(path: String, id: String, agent: AgentConfig) {
    require(isAgentId(id)) {
        "$path is no agent id: an id is lowercase words of letters and digits, each starting with a letter, " +
            "joined by single hyphens, such as \"coder\""
    }
    require(agent.name == null || isOneLine(agent.name)) {
        "$path.name must be one line of text"
    }
    require(agent.instructions.none(String::isBlank)) { "$path.instructions holds a blank entry" }
    for (entry in agent.instructionFiles) {
        require(isPath(entry.file)) { "$path.instructionFiles holds a blank or malformed path" }
        val unknown = entry.`when`.filterNot { it in CONDITION_IDS }
        require(unknown.isEmpty()) {
            "$path.instructionFiles names ${unknown.joinToString { "\"$it\"" }} in \"when\", which is no chat kind " +
                "or turn kind: use ${CONDITION_IDS.joinToString()}"
        }
    }
    require(agent.model == null || ModelRef.parseOrNull(agent.model) != null) {
        "$path.model is no model reference: write <endpoint>/<model>, such as \"anthropic/claude-sonnet-5-5\""
    }
    require(agent.model == null || agent.models.isEmpty()) {
        "$path sets both model and models: name one model, or list them all in models"
    }
    for ((index, model) in agent.models.withIndex()) {
        require(ModelRef.parseOrNull(model) != null) {
            "$path.models[$index] is no model reference: write <endpoint>/<model>, such as " +
                "\"anthropic/claude-sonnet-5-5\""
        }
    }
    require(agent.models.toSet().size == agent.models.size) { "$path.models names a model twice" }
    require(agent.reasoning == null || reasoningOf(agent.reasoning) != null) {
        "$path.reasoning must be one of ${ReasoningEffort.entries.joinToString()}"
    }
    require(agent.language == null || isLanguageTag(agent.language)) {
        "$path.language is no BCP 47 language tag, such as \"zh-CN\""
    }
    require(agent.zone == null || zoneOrNull(agent.zone) != null) {
        "$path.zone is no time zone: write a region id such as \"Asia/Shanghai\" or an offset such as \"+08:00\""
    }
    val mode = agent.permission.mode
    require(mode == null || configurableModeOf(mode) != null) {
        "$path.permission.mode must be one of ${CONFIGURABLE_MODES.joinToString()}: a bypass is set per chat, " +
            "for a limited time"
    }
    checkRules("$path.permission.allow", agent.permission.allow, allow = true)
    checkRules("$path.permission.deny", agent.permission.deny, allow = false)
    for ((index, entry) in agent.permission.trust.withIndex()) {
        val problem = RuleEntry.problem(entry, trust = true)
        require(problem == null) { "$path.permission.trust[$index] $problem" }
    }
    require(agent.permission.judgeTimeoutSeconds > 0) { "$path.permission.judgeTimeoutSeconds must be positive" }
    require(agent.permission.judgeMaxChars > 0) { "$path.permission.judgeMaxChars must be positive" }
    require(agent.permission.taintedNetworkReads in NETWORK_READS) {
        "$path.permission.taintedNetworkReads must be one of ${NETWORK_READS.joinToString()}"
    }
    require(agent.maxOutputTokens == null || agent.maxOutputTokens > 0) { "$path.maxOutputTokens must be positive" }
    require(agent.temperature == null || (agent.temperature.isFinite() && agent.temperature >= 0)) {
        "$path.temperature must be a number of at least 0"
    }
    require(agent.maxRounds == null || agent.maxRounds > 0) { "$path.maxRounds must be positive" }
    require(agent.maxTurnTokens == null || agent.maxTurnTokens > 0) { "$path.maxTurnTokens must be positive" }
    require(agent.toolTimeoutSeconds > 0) { "$path.toolTimeoutSeconds must be positive" }
    require(agent.foregroundSeconds > 0) { "$path.foregroundSeconds must be positive" }
    checkCompaction("$path.compaction", agent.compaction)
    for ((role, model) in agent.roles.set()) {
        require(ModelRef.parseOrNull(model) != null) {
            "$path.roles.$role is no model reference: write <endpoint>/<model>, such as \"anthropic/claude-haiku-5-5\""
        }
    }
    for ((kind, value) in agent.media.entries()) {
        val handling = MediaHandling.of(value)
        require(handling != null) {
            "$path.media.$kind must be one of ${MediaHandling.entries.joinToString { it.id }}"
        }
        require(handling != MediaHandling.DELEGATE || agent.roles.set().containsKey(kind)) {
            "$path.media.$kind is delegate, which needs a reader: set $path.roles.$kind"
        }
    }
    checkBudget("$path.budget.daily", agent.budget.daily)
    checkBudget("$path.budget.monthly", agent.budget.monthly)
    checkGlobs("$path.tools.allow", agent.tools.allow)
    checkGlobs("$path.tools.deny", agent.tools.deny)
    checkGlobs("$path.tools.forGuests", agent.tools.forGuests)
    require(agent.workspace == null || isPath(agent.workspace)) { "$path.workspace is a blank or malformed path" }
    for ((key, attachment) in agent.channels) {
        require(ChannelInstanceId.parseOrNull(key) != null) {
            "$path.channels.$key is no channel instance: write <type>:<name>, such as \"telegram:work\""
        }
        require(attachment.home == null || homeChat(key, attachment.home) != null) {
            "$path.channels.$key.home is no chat of the instance: write its chat id, and #<thread> for a thread"
        }
    }
    require(agent.channels.isEmpty() || agent.model != null || agent.models.isNotEmpty()) {
        "$path.model is missing: an agent that serves channels needs a model"
    }
    require(agent.channels.isNotEmpty() || !agent.description.isNullOrBlank()) {
        "$path.description is missing: an agent that serves no channels is a profile, which needs one"
    }
    checkLinks(path, agent)
    checkSandbox("$path.sandbox", agent.sandbox)
}

private fun checkSandbox(path: String, sandbox: SandboxConfig) {
    require(
        sandbox.type == SandboxConfig.HOST || sandbox.type == SandboxConfig.AUTO ||
            ExecutionEnvironment.NAME.matches(sandbox.type),
    ) {
        "$path.type must be \"host\", \"auto\" or the name of a sandbox, such as \"seatbelt\""
    }
    require(sandbox.allowWrite.all(::isPath)) { "$path.allowWrite holds a blank or malformed path" }
    require(sandbox.denyRead.all(::isPath)) { "$path.denyRead holds a blank or malformed path" }
    for ((index, entry) in sandbox.protect.orEmpty().withIndex()) {
        require(isProtectEntry(entry)) {
            "$path.protect[$index] is neither \"alexandrite\", a path, nor a relative path without \".\" or \"..\""
        }
    }
    for ((index, entry) in sandbox.network.allowedDomains.withIndex()) {
        require(HostPattern.parse(entry) != null) {
            "$path.network.allowedDomains[$index] is no host: write a name such as \"example.com\" or " +
                "\"*.example.com\", or an address such as \"127.0.0.1\", each with an optional \":port\""
        }
    }
}

/** Whether [entry] is something `sandbox.protect` takes. */
internal fun isProtectEntry(entry: String): Boolean = entry == SandboxConfig.ALEXANDRITE ||
    (
        isPath(entry) && (
            entry.startsWith("/") || entry == "~" || entry.startsWith("~/") ||
                entry.split('/').none { it.isEmpty() || it == "." || it == ".." }
            )
        )

private fun checkLinks(path: String, agent: AgentConfig) {
    val attached = agent.channels.keys.mapNotNull(ChannelInstanceId::parseOrNull).toSet()
    val linked = mutableMapOf<ChatAddress, String>()
    for ((index, group) in agent.linkedChats.withIndex()) {
        require(group.size >= 2) { "$path.linkedChats[$index] holds fewer than two chats" }
        for ((position, text) in group.withIndex()) {
            val at = "linkedChats[$index][$position]"
            val chat = requireNotNull(ChatAddress.parseOrNull(text)) {
                "$path.$at is no chat address: write <type>:<name>:<chat>, such as \"telegram:work:123456\""
            }
            require(chat.instance in attached) {
                "$path.$at is a chat of ${chat.instance}, which the agent is not attached to: add it to $path.channels"
            }
            val first = linked.putIfAbsent(chat, at)
            require(first == null) { "$path.$at is the chat of $first again: a chat is in one group of an agent" }
        }
    }
}

private fun checkCompaction(path: String, compaction: CompactionConfig) {
    val thresholds = listOf(
        "trigger" to compaction.trigger,
        "target" to compaction.target,
        "clearAt" to compaction.clearAt,
        "clearAtLeast" to compaction.clearAtLeast,
    )
    for ((key, threshold) in thresholds) {
        val off = key == "clearAt"
        require(threshold !is Threshold.Malformed && (off || threshold != Threshold.Off)) {
            "$path.$key must be a fraction of the context window above 0 and at most 1, such as 0.6, or a whole " +
                "number of tokens" + if (off) ", or \"off\"" else ""
        }
    }
    val trigger = compaction.trigger
    val target = compaction.target
    val below = when {
        trigger is Threshold.Fraction && target is Threshold.Fraction -> target.value < trigger.value
        trigger is Threshold.Tokens && target is Threshold.Tokens -> target.count < trigger.count
        else -> true
    }
    require(below) { "$path.target must be below $path.trigger" }
    require(compaction.keepToolRounds >= 0) { "$path.keepToolRounds may not be negative" }
    require(compaction.keepTurns >= 0) { "$path.keepTurns may not be negative" }
    require(compaction.maxSummaryTokens > 0) { "$path.maxSummaryTokens must be positive" }
}

private fun checkBudget(path: String, limit: BudgetLimit?) {
    limit ?: return
    require(limit.tokens != null || limit.money != null) { "$path sets neither tokens nor money" }
    require(limit.tokens == null || limit.tokens > 0) { "$path.tokens must be positive" }
    require(limit.money == null || (limit.money.isFinite() && limit.money > 0)) {
        "$path.money must be a positive number of US dollars"
    }
}

private fun checkGlobs(path: String, globs: List<String>) {
    require(invalidGlob(globs) == null) {
        "$path holds a malformed tool glob: write dotted tool names, with * for any characters, such as \"fs.*\""
    }
}

private fun checkRules(path: String, entries: List<String>, allow: Boolean) {
    for ((index, entry) in entries.withIndex()) {
        val problem = RuleEntry.problem(entry, allow)
        require(problem == null) { "$path[$index] $problem" }
    }
}

private fun isLanguageTag(text: String): Boolean = try {
    LanguageTag.of(text)
    true
} catch (e: IllegalArgumentException) {
    false
}

private fun checkDefaults(agents: Map<String, AgentConfig>) {
    val attached = agents.flatMap { (id, agent) ->
        agent.channels.map { (key, attachment) -> key to (id to attachment) }
    }
    for ((key, attachments) in attached.groupBy({ it.first }, { it.second })) {
        if (attachments.size == 1) continue
        val defaults = attachments.filter { it.second.default }.map { it.first }
        require(defaults.size == 1) {
            val agentsOf = attachments.joinToString { it.first }
            if (defaults.isEmpty()) {
                "agents attached to $key name no default agent: set \"default\": true in channels.$key of exactly " +
                    "one of $agentsOf"
            } else {
                "agents ${defaults.joinToString()} are each the default agent of $key: keep \"default\": true in " +
                    "channels.$key of one of them"
            }
        }
    }
}

private fun checkHomes(agents: Map<String, AgentConfig>) {
    val homes = mutableMapOf<ChatAddress, String>()
    for ((id, agent) in agents) {
        for ((key, attachment) in agent.channels) {
            val home = attachment.home?.let { homeChat(key, it) } ?: continue
            val other = homes.putIfAbsent(home, id)
            require(other == null) {
                "agents.$id.channels.$key.home names the home chat of agent $other: a chat is the home of one agent"
            }
        }
    }
}

private val NETWORK_READS = listOf(PermissionConfig.NETWORK_READS_RUN, PermissionConfig.NETWORK_READS_GATE)
