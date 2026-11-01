package org.unitedsoftware.alexandrite.agent.permission

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.SandboxConfig
import org.unitedsoftware.alexandrite.agent.config.configuredPath
import org.unitedsoftware.alexandrite.agent.prompt.Persona
import org.unitedsoftware.alexandrite.agent.tool.writtenFile
import org.unitedsoftware.alexandrite.agent.trust.FolderTrust
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.sandbox.Protect
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/** The names of a project's instruction files, in the order they load. */
internal val INSTRUCTION_FILES: List<String> = listOf("AGENTS.md", "CLAUDE.md")

/** Where a project keeps its skills, one directory per skill. */
internal val SKILL_DIRECTORIES: List<String> = listOf(".agents/skills", ".claude/skills")

/** The relative paths at any depth that hold a project's instructions or skills. */
internal val INSTRUCTION_NAMES: List<String> = INSTRUCTION_FILES + SKILL_DIRECTORIES

/**
 * The places whose files steer an agent once written: the config and secrets files, the persona and skill directories,
 * the agents' instruction files, a trusted folder's instructions and skills, and what the sandbox of an agent that has
 * one protects.
 */
internal interface InstructionPlaces {
    /**
     * The kind of instruction-bearing place that the file [review] shows a change of is, null when it is none.
     *
     * What a command writes cannot be foreseen: the sandbox's protect list and the judge's rule for tainted
     * conversations cover it.
     */
    fun written(agent: AgentId, review: CallReview): String? = review.preview.writtenFile?.let { kind(it, agent) }

    /** The kind of instruction-bearing place that [path] (absolute) is or lies in for a write by [agent], else null. */
    fun kind(path: Path, agent: AgentId): String?

    /** The instruction-bearing places that a sandbox keeps commands from writing unless a person allowed the call. */
    fun sandboxPaths(): List<Path>
}

@Singleton
@Binds(InstructionPlaces::class)
internal class HostInstructionPlaces(
    private val paths: HostPaths,
    private val places: AgentPlaces,
    private val directory: AgentDirectory,
    private val workspaces: Workspaces,
    private val trust: FolderTrust,
    private val persona: Persona,
) : InstructionPlaces {
    private val canonicalizer = PathCanonicalizer()

    private val protects: Map<AgentId, Protected> = directory.agents
        .filter { it.config.sandbox.type != SandboxConfig.HOST }
        .associate { agent -> agent.id to protectedBy(agent.id, agent.config.sandbox) }

    override fun kind(path: Path, agent: AgentId): String? {
        val target = real(path)
        val config = listOfNotNull(paths.configFile, paths.configTarget, paths.configBackup)
        if (config.any { same(target, real(it)) }) return CONFIG_FILE
        if (config.any { same(target, real(it.resolveSibling(SECRETS_FILE))) }) return SECRETS
        for (each in directory.agents) {
            val id = each.id
            if (persona.files(id).any { same(target, real(it)) }) return "an instruction file of agent '$id'"
            val personaDirectory = places.personaDirectory(id)
            if (personaDirectory != null && under(target, real(personaDirectory))) {
                return "the persona directory of agent '$id'"
            }
            if (places.skillDirectories(id).any { under(target, real(it)) }) return "a skill directory of agent '$id'"
        }
        trusted(target)?.let { return it }
        return protects[agent]?.let { protected(target, it) }
    }

    override fun sandboxPaths(): List<Path> {
        val config = listOfNotNull(paths.configFile, paths.configTarget, paths.configBackup)
        return (
            config + config.map { it.resolveSibling(SECRETS_FILE) } +
                directory.agents.flatMap { agent ->
                    persona.files(agent.id) + listOfNotNull(places.personaDirectory(agent.id)) +
                        places.skillDirectories(agent.id)
                }
            ).map(::real).distinct()
    }

    /** The kind of a trusted folder's instruction place that [target] is, null when it is none. */
    private fun trusted(target: Path): String? {
        val folder = trust.coveringAny(target.parent ?: return null) ?: return null
        val inside = folder.relativize(target).map { it.toString() }
        return when {
            INSTRUCTION_FILES.any { it.equals(target.fileName.toString(), ignoreCase = true) } ->
                "a trusted folder's instruction file"

            SKILL_NAMES.any { holds(inside, it) } -> "a trusted folder's skills"

            else -> null
        }
    }

    /** The kind of place in [protect] that [target] lies in, null when it lies in none. */
    private fun protected(target: Path, protect: Protected): String? {
        val parts = target.map { it.toString() }
        protect.names.firstOrNull {
            holds(parts, it.split('/'))
        }?.let { return "a place that the sandbox protects ($it)" }
        if (protect.paths.any { under(target, real(it)) }) return "a place that the sandbox protects"
        if (!protect.alexandrite) return null
        val own = workspaces.paths.values + directory.agents.map { places.conversations(it.id) }
        if (own.any { under(target, real(it)) }) return null
        return ALEXANDRITE.takeIf { paths.alexandritePlaces.any { under(target, real(it)) } }
    }

    private fun protectedBy(agent: AgentId, config: SandboxConfig): Protected {
        val protect = Protect.of(config.protect)
        val key = "agent.agents.$agent.sandbox.protect"
        val resolved = protect.paths.mapNotNull { entry ->
            try {
                configuredPath(paths, key, "the protected path", expandHome(entry))
            } catch (e: ConfigException) {
                null
            }
        }
        return Protected(protect.names, resolved, protect.alexandrite)
    }

    private fun real(path: Path): Path = canonicalizer.canonicalize(path.toAbsolutePath().normalize()).real

    /** What an agent's sandbox protects: [names] at any depth, [paths], and Alexandrite's places when [alexandrite]. */
    private class Protected(val names: List<String>, val paths: List<Path>, val alexandrite: Boolean)
}

/** Whether [parts] hold the path components [name] in a row, ignoring case. */
private fun holds(parts: List<String>, name: List<String>): Boolean =
    parts.windowed(name.size).any { window -> window.indices.all { window[it].equals(name[it], ignoreCase = true) } }

/** Whether [path] is [place] or lies in it, ignoring case. */
private fun under(path: Path, place: Path): Boolean = folded(path).startsWith(folded(place))

private fun same(path: Path, other: Path): Boolean = folded(path) == folded(other)

private fun folded(path: Path): Path = Paths.get(path.toString().lowercase(Locale.ROOT))

private val SKILL_NAMES = SKILL_DIRECTORIES.map { it.split('/') }

private const val SECRETS_FILE = "secrets.env"

private const val CONFIG_FILE = "Alexandrite's config file"

private const val SECRETS = "Alexandrite's secrets file"

private const val ALEXANDRITE = "Alexandrite's own places"
