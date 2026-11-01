package org.unitedsoftware.alexandrite.agent.permission

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.config.FloorConfig
import org.unitedsoftware.alexandrite.agent.config.configuredPath
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.internal.floor.DenyRoot
import org.unitedsoftware.alexandrite.internal.floor.Floor
import org.unitedsoftware.alexandrite.internal.floor.FloorDecision
import org.unitedsoftware.alexandrite.internal.floor.Overlap
import org.unitedsoftware.alexandrite.internal.floor.Placement
import org.unitedsoftware.alexandrite.internal.floor.StandardPlaces
import org.unitedsoftware.alexandrite.internal.floor.credentialPlaces
import org.unitedsoftware.alexandrite.internal.floor.leveled
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.ConfigSnapshot
import org.unitedsoftware.alexandrite.sdk.config.LiveConfig
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** The hard floor of every tool, whose places follow `agent.floor` while the runtime runs. */
@Singleton
@Binds(HardFloor::class)
@Contribute(LiveConfig::class)
internal class AgentFloor(
    private val hostPaths: HostPaths,
    private val places: AgentPlaces,
    private val workspaces: Workspaces,
    settings: AgentSettings,
    private val directories: ConversationDirectories,
) : HardFloor,
    LiveConfig {
    @Volatile
    private var levels: Levels = levels(settings.floor, quiet = false)

    override val paths: List<String> = listOf(FLOOR_PATH)

    override fun prepare(config: ConfigSnapshot): () -> Unit {
        val floor = config.section(key<AgentSettings>())?.floor ?: FloorConfig()
        val next = levels(floor, quiet = true)
        return {
            levels = next
            logger.info("The hard floor now refuses {} and asks about {}", described(floor.deny), described(floor.ask))
        }
    }

    override fun checkRead(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
        check(path, agent, conversation, tree = false) { it.checkRead(path, agent) }

    /** [agent]'s own persona files are carved out of a refusal. */
    override fun checkWrite(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck {
        val check = check(path, agent, conversation, tree = false) { it.checkWrite(path) }
        if (check !is FloorCheck.Denied || agent == null) return check
        return checkPersonaWrite(path, agent).takeUnless { it is FloorCheck.Denied } ?: check
    }

    override fun checkTree(path: Path, agent: AgentId?, conversation: ConversationId?): FloorCheck =
        check(path, agent, conversation, tree = true) { it.checkTree(path) }

    override val protectsAnything: Boolean
        get() = levels.config.let { it.deny.isNotEmpty() || it.ask.isNotEmpty() }

    /** The groups and paths of the floor in force. */
    val entries: FloorConfig get() = levels.config

    /** The places that a sandbox keeps commands out of: those the floor refuses, then those it asks a person about. */
    fun sandboxPlaces(): Pair<List<Path>, List<Path>> = levels.let { it.denied to it.asked }

    /** [path] (absolute) made canonical and checked for a write of a persona file of [agent]. */
    private fun checkPersonaWrite(path: Path, agent: AgentId): FloorCheck {
        val levels = levels
        return leveled(levels.deny.checkPersonaWrite(path, agent)) { levels.ask.checkPersonaWrite(path, agent) }
            .toCheck()
    }

    /** The decision of [base] on the floor that refuses, then on the one that asks a person. */
    private fun check(
        path: Path,
        agent: AgentId?,
        conversation: ConversationId?,
        tree: Boolean,
        base: (HostFloor) -> FloorDecision,
    ): FloorCheck {
        val levels = levels
        return leveled(ownConversation(levels.deny, path, agent, conversation, tree, base)) {
            ownConversation(levels.ask, path, agent, conversation, tree, base)
        }.toCheck()
    }

    /**
     * [base]'s decision on [floor], or the directory of [agent]'s conversation [conversation] carved out of the data
     * root for [path] instead, when [base] refuses it and carving that one directory out would not.
     */
    private fun ownConversation(
        floor: HostFloor,
        path: Path,
        agent: AgentId?,
        conversation: ConversationId?,
        tree: Boolean,
        base: (HostFloor) -> FloorDecision,
    ): FloorDecision {
        val decision = base(floor)
        if (decision !is FloorDecision.Denied || agent == null || conversation == null) return decision
        val exemption = floor.checkConversation(path, directories.path(agent, conversation), tree)
        return if (exemption is FloorDecision.Allowed) exemption else decision
    }

    /** The floors of [config]; throws a [ConfigException] for places that cannot be protected. */
    private fun levels(config: FloorConfig, quiet: Boolean): Levels = Levels(
        config,
        level(config.deny, DENY_KEY, quiet),
        level(config.ask, ASK_KEY, quiet = true),
        places(config.deny, DENY_KEY),
        places(config.ask, ASK_KEY),
    )

    /** The paths of the groups and paths in [entries], written at [key], but for the `system` group's. */
    private fun places(entries: List<String>, key: String): List<Path> = buildList {
        if (FloorConfig.CREDENTIALS in entries) addAll(credentialPlaces(userHome(), System.getenv()))
        if (FloorConfig.ALEXANDRITE in entries) addAll(hostPaths.alexandritePlaces)
        addAll(operatorPlaces(hostPaths, entries.filterNot { it in FloorConfig.FLOOR_GROUPS }, userHome(), key))
    }

    /** The floor of the groups and paths in [entries], written at [key]. */
    private fun level(entries: List<String>, key: String, quiet: Boolean): HostFloor {
        val groups = entries.filter { it in FloorConfig.FLOOR_GROUPS }.toSet()
        return hostFloor(
            hostPaths,
            workspaces.paths,
            userHome(),
            operatorPlaces(hostPaths, entries.filterNot { it in FloorConfig.FLOOR_GROUPS }, userHome(), key),
            personas = workspaces.paths.keys.mapNotNull { id -> places.personaDirectory(id)?.let { id to it } }.toMap(),
            skills = workspaces.paths.keys.associateWith { places.skillDirectories(it).map(Path::normalize) },
            environment = System.getenv(),
            alexandrite = FloorConfig.ALEXANDRITE in groups,
            standard = buildSet {
                if (FloorConfig.CREDENTIALS in groups) add(StandardPlaces.CREDENTIALS)
                if (FloorConfig.SYSTEM in groups) add(StandardPlaces.SYSTEM)
            },
            quiet = quiet,
        )
    }

    private fun FloorDecision.toCheck(): FloorCheck = when (this) {
        is FloorDecision.Allowed -> FloorCheck.Allowed(canonical)
        is FloorDecision.Ask -> FloorCheck.Ask(canonical, reason)
        is FloorDecision.Denied -> FloorCheck.Denied(reason)
    }

    /** The floor that refuses and the one that asks a person, with the paths of each. */
    private class Levels(
        val config: FloorConfig,
        val deny: HostFloor,
        val ask: HostFloor,
        val denied: List<Path>,
        val asked: List<Path>,
    )
}

/** The path that [this] lets a call use, once a person allows it where the floor asks one; null when it refuses. */
internal val FloorCheck.canonical: Path?
    get() = when (this) {
        is FloorCheck.Allowed -> canonical
        is FloorCheck.Ask -> canonical
        else -> null
    }

/** Why only a person may allow a call that reaches [path], which the floor [check] asks a person about. */
internal fun askedPlace(path: Path, check: FloorCheck.Ask): String =
    "$path is in a place the floor asks a person about (${check.reason})"

/** The floor of each agent, which lets that agent alone read its persona directory. */
internal class HostFloor(
    /** Null to refuse every path. */
    private val shared: Floor?,
    private val own: Map<AgentId, Floor>,
    private val personas: Map<AgentId, Path> = emptyMap(),
    /** [checkConversation], null to refuse every path. */
    private val withConversation: ((path: Path, directory: Path, tree: Boolean) -> FloorDecision)? = null,
) {
    fun checkRead(path: Path, agent: AgentId? = null): FloorDecision {
        val floor = agent?.let(own::get) ?: shared ?: return FloorDecision.Denied(UNKNOWN_HOME)
        return floor.check(path)
    }

    fun checkWrite(path: Path): FloorDecision = shared?.check(path) ?: FloorDecision.Denied(UNKNOWN_HOME)

    fun checkTree(path: Path): FloorDecision = shared?.checkTree(path) ?: FloorDecision.Denied(UNKNOWN_HOME)

    /** [path] checked as by [checkWrite] (or [checkTree] when [tree]) with [directory] carved out of the data root. */
    fun checkConversation(path: Path, directory: Path, tree: Boolean): FloorDecision =
        withConversation?.invoke(path, directory, tree) ?: FloorDecision.Denied(UNKNOWN_HOME)

    /** [path] checked for a write that only the Markdown files directly in [agent]'s persona directory may take. */
    fun checkPersonaWrite(path: Path, agent: AgentId): FloorDecision {
        if (shared == null) return FloorDecision.Denied(UNKNOWN_HOME)
        val floor = own[agent] ?: return FloorDecision.Denied(NO_PERSONA)
        val persona = personas[agent] ?: return FloorDecision.Denied(NO_PERSONA)
        val decision = floor.check(path)
        if (decision !is FloorDecision.Allowed) return decision
        val file = decision.canonical
        val directory = floor.check(persona)
        return when {
            directory !is FloorDecision.Allowed || file.parent != directory.canonical ->
                FloorDecision.Denied("a place outside the persona directory of agent '$agent'")

            !file.fileName.toString().endsWith(MARKDOWN, ignoreCase = true) -> FloorDecision.Denied(NOT_MARKDOWN)

            else -> decision
        }
    }
}

/**
 * The floor over the [operator]'s places, the [standard] groups of [home] and [environment] and, when [alexandrite],
 * the host's places in [paths], with each of [workspaces] carved out of the data root or the config directory that
 * holds it, and each agent's persona directory carved out for that agent alone.
 *
 * Throws a [ConfigException] for a workspace that is a protected place, holds one, or lies in one that keeps no
 * workspace.
 */
internal fun hostFloor(
    paths: HostPaths,
    workspaces: Map<AgentId, Path>,
    home: Path?,
    operator: List<Path> = emptyList(),
    /** Each agent's persona directory, carved out for that agent alone. */
    personas: Map<AgentId, Path> = emptyMap(),
    /** The skill directories each agent may read and no file tool may write. */
    skills: Map<AgentId, List<Path>> = emptyMap(),
    canonicalizer: PathCanonicalizer = PathCanonicalizer(),
    environment: Map<String, String> = emptyMap(),
    /** Whether Alexandrite's own places and the agents' persona directories are protected. */
    alexandrite: Boolean = true,
    standard: Set<StandardPlaces> = StandardPlaces.entries.toSet(),
    /** Whether to keep the warnings about the places to itself. */
    quiet: Boolean = false,
): HostFloor {
    val directory = paths.configFile?.parent
    val configuration = directory?.takeIf { alexandrite }?.let { DenyRoot(it, CONFIGURATION) }
    val data = DenyRoot(paths.dataRoot, "Alexandrite's data")
    val ownPlaces = if (!alexandrite) {
        emptyList()
    } else {
        listOfNotNull(configuration) +
            listOfNotNull(paths.configTarget, paths.configBackup).map { DenyRoot(it, CONFIGURATION) } +
            DenyRoot(paths.pluginDataRoot, "plugin data") +
            data +
            DenyRoot(paths.cacheRoot, "Alexandrite's cache") +
            paths.protected.map { DenyRoot(it, "a place the host protects") }
    }
    val places = ownPlaces + operator.map { DenyRoot(it, "a place the operator protects") }
    val keepsCarveOuts = setOfNotNull(configuration, data.takeIf { alexandrite })
    val plain = Floor(places, home, canonicalizer, environment, standard)
    val personaPlaces = Floor(
        personas.takeIf { alexandrite }.orEmpty()
            .map { (id, persona) -> DenyRoot(persona, "the persona directory of agent '$id'") },
        null,
        canonicalizer,
        standard = emptySet(),
    )
    val carveOuts = mutableMapOf<DenyRoot, MutableList<Path>>()
    for ((id, workspace) in workspaces) {
        val overlaps = plain.overlaps(workspace)
        val collision = overlaps.filter { it.placement != Placement.INSIDE || it.root !in keepsCarveOuts }
            .minByOrNull { it.placement }
            ?: personaPlaces.overlaps(workspace).filter { it.root != null }.minByOrNull { it.placement }
        collision?.let { throw ConfigException("agent.agents.$id.workspace", collision(workspace, it)) }
        overlaps.forEach { carveOuts.getOrPut(checkNotNull(it.root)) { mutableListOf() }.add(workspace) }
    }
    if (!quiet) warnShared(workspaces, canonicalizer)
    if (home == null && StandardPlaces.CREDENTIALS in standard) {
        if (!quiet) logger.error("The home directory is unknown: the hard floor refuses every path")
        return HostFloor(null, emptyMap())
    }
    val overlap = if (home == null || configuration == null) {
        null
    } else {
        plain.overlaps(home).firstOrNull { it.root == configuration && it.placement != Placement.HOLDS }
    }
    overlap?.takeUnless { quiet }?.let { overlap ->
        logger.warn(
            "The config file {} lies in {}, which is the home directory or holds it: the hard floor keeps the file " +
                "tools out of all of it. Keep the config file in a directory of its own",
            paths.configFile,
            checkNotNull(overlap.root).path,
        )
    }
    fun floor(extra: Map<DenyRoot, List<Path>>) = Floor(
        places.map { DenyRoot(it.path, it.kind, carveOuts[it].orEmpty() + extra[it].orEmpty()) },
        home,
        canonicalizer,
        environment,
        standard,
    )
    val own = (personas.keys + skills.keys).associateWith { id ->
        val readable = listOfNotNull(personas[id]) + skills[id].orEmpty()
        floor(
            readable.flatMap { place ->
                plain.overlaps(place)
                    .filter { it.placement == Placement.INSIDE && it.root in keepsCarveOuts }
                    .map { checkNotNull(it.root) to place }
            }.groupBy({ it.first }, { it.second }),
        )
    }
    val withConversation = { path: Path, conversationDirectory: Path, tree: Boolean ->
        val extended = floor(mapOf(data to listOf(conversationDirectory)))
        if (tree) extended.checkTree(path) else extended.check(path)
    }
    return HostFloor(floor(emptyMap()), own, personas, withConversation)
}

/** The paths written at [key], each absolute, under `~/` (the [home]), or relative to the config's directory. */
internal fun operatorPlaces(
    paths: HostPaths,
    configured: List<String>,
    home: Path? = null,
    key: String = DENY_KEY,
): List<Path> = configured.map { entry ->
    if (entry == "~" || entry.startsWith("~/")) {
        val base = home ?: throw ConfigException(
            key,
            "the protected path '$entry' starts with ~, but the home directory is unknown: give an absolute path",
        )
        return@map base.resolve(entry.removePrefix("~").removePrefix("/")).normalize()
    }
    configuredPath(paths, key, "the protected path", entry)
}

private fun described(entries: List<String>): String = entries.joinToString().ifEmpty { "nothing" }

private fun collision(workspace: Path, overlap: Overlap): String {
    val relation = when (overlap.placement) {
        Placement.AT -> "is"
        Placement.INSIDE -> "lies in"
        Placement.HOLDS -> "holds"
    }
    return "the workspace $workspace $relation a protected place (${overlap.kind}): give the agent another directory"
}

private fun warnShared(workspaces: Map<AgentId, Path>, canonicalizer: PathCanonicalizer) {
    val agents = workspaces.entries.groupBy({ canonicalizer.canonicalize(it.value).real }, { it.key })
    for ((workspace, ids) in agents) {
        if (ids.size < 2) continue
        logger.warn(
            "Agents {} share the workspace {}: they see each other's files",
            ids.joinToString { "'$it'" },
            workspace,
        )
    }
}

private fun userHome(): Path? = try {
    System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
} catch (e: InvalidPathException) {
    null
}

private const val CONFIGURATION = "Alexandrite's configuration"

/** Where the floor's places lie below the agent plugin's config root. */
private const val FLOOR_PATH = "floor"

private const val DENY_KEY = "agent.floor.deny"

private const val ASK_KEY = "agent.floor.ask"

private const val UNKNOWN_HOME = "every path, as the home directory is unknown"

private const val NO_PERSONA = "every path, as the agent has no persona directory"

private const val NOT_MARKDOWN = "a persona file that is no Markdown file"

private const val MARKDOWN = ".md"

private val logger = LoggerFactory.getLogger(AgentFloor::class.java)

/** Why the floor refused, as [FloorCheck.Denied] says, or that it decided as this version does not know. */
internal val FloorCheck.denial: String
    get() = (this as? FloorCheck.Denied)?.reason ?: "a decision of the floor that this version does not know"
