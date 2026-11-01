package org.unitedsoftware.alexandrite.agent.skill

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.trust.FolderTrust
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.skill.SkillSource
import org.unitedsoftware.alexandrite.sdk.skill.SkillSpec
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.stream.Collectors

/** Where a skill comes from. */
internal enum class SkillScope {
    /** A plugin's, as trusted as the plugin. */
    PLUGIN,

    /** One of the host's skill directories, which the operator fills. */
    GLOBAL,

    /** A project folder's, trusted only when the folder is. */
    PROJECT,
}

/**
 * A skill an agent may load: [name] and [description] as its file or its plugin gives them, from [scope]; [file] is
 * its `SKILL.md` and [folder] the project folder that holds it, null for a plugin's and for the host's.
 */
internal class Skill(
    val name: String,
    val description: String,
    val scope: SkillScope,
    val file: Path?,
    val folder: Path? = null,
    val spec: SkillSpec? = null,
) {
    /** The directory of [file], which holds the skill's other files. */
    val directory: Path? get() = file?.parent
}

/** The skills of the project folders each recent conversation reached, by name, the latest folder's winning. */
@Singleton
internal class ProjectSkills {
    private val conversations = object : LinkedHashMap<ConversationId, MutableMap<String, Skill>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConversationId, MutableMap<String, Skill>>) =
            size > CAPACITY
    }

    fun note(conversation: ConversationId, skills: List<Skill>) {
        synchronized(conversations) {
            val known = conversations.getOrPut(conversation, ::LinkedHashMap)
            skills.forEach { known[it.name] = it }
        }
    }

    fun of(conversation: ConversationId): Map<String, Skill> =
        synchronized(conversations) { conversations[conversation]?.toMap().orEmpty() }

    private companion object {
        const val CAPACITY = 256
    }
}

/**
 * The skills each agent may load: the plugins', then the host's from its skill directories, each later one taking
 * the place of an earlier one's of its name, then the projects' that a conversation reached, which win over all.
 */
@Singleton
internal class Skills(
    private val places: AgentPlaces,
    private val sources: List<SkillSource>,
    private val projects: ProjectSkills,
    private val trust: FolderTrust,
    private val floor: HardFloor,
) {
    private val read = HashMap<Path, Cached>()
    private val warned = HashSet<Path>()

    /** The plugins' and the host's skills of [agent], by name. */
    fun global(agent: AgentId): Map<String, Skill> {
        val skills = LinkedHashMap<String, Skill>()
        for (source in sources) {
            val listed = try {
                source.skills()
            } catch (e: Exception) {
                logger.warn("The skills of {} cannot be listed: {}", source.javaClass.name, e.toString())
                emptyList()
            }
            listed.forEach { skills[it.name] = Skill(it.name, it.description, SkillScope.PLUGIN, null, spec = it) }
        }
        for (directory in places.skillDirectories(agent)) {
            skillFiles(directory).forEach { file -> skill(file, SkillScope.GLOBAL)?.let { skills[it.name] = it } }
        }
        return skills.toSortedMap()
    }

    /** The skill [name] that [turn] may load: its conversation's project skill, else its agent's global one. */
    fun find(turn: TurnInfo, name: String): Skill? = projects.of(turn.conversation)[name] ?: global(turn.agent)[name]

    /** The names of the skills that [turn] may load. */
    fun names(turn: TurnInfo): List<String> = (global(turn.agent).keys + projects.of(turn.conversation).keys).sorted()

    /** Whether [agent] trusts [skill] now: a plugin's and the host's always, a project's while it trusts its folder. */
    fun trusted(agent: AgentId, skill: Skill): Boolean =
        skill.scope != SkillScope.PROJECT || skill.folder?.let { trust.covering(agent, it) } != null

    /** The skill whose file is [file] in [scope], null, with one warning, when the file does not make a skill. */
    fun skill(file: Path, scope: SkillScope, folder: Path? = null): Skill? {
        val text = cached(file) ?: return warn(file, "it cannot be read as a UTF-8 file of at most 64 KiB")
        val directoryName = file.parent?.fileName?.toString()
        val name = text.name ?: directoryName
        return when {
            name == null || !SkillSpec.NAME.matches(name) -> warn(file, "its name '$name' is no skill name")

            name != directoryName -> warn(file, "its name '$name' is not the name of its directory")

            text.description.isNullOrBlank() || text.description.length > SkillSpec.MAX_DESCRIPTION ->
                warn(file, "it gives no description of 1 to ${SkillSpec.MAX_DESCRIPTION} characters")

            text.body.isBlank() -> warn(file, "it gives no instructions")

            else -> Skill(name, text.description, scope, file, folder)
        }
    }

    /** The text of [file] as it reads now, null when it cannot be read. */
    fun text(file: Path): SkillText? = cached(file)

    private fun cached(file: Path): SkillText? {
        val modified = try {
            Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return null
        }
        synchronized(read) { read[file]?.takeIf { it.modified == modified }?.let { return it.text } }
        val text = readSkill(file, floor.protectsAnything)
        synchronized(read) { read[file] = Cached(modified, text) }
        return text
    }

    private fun warn(file: Path, why: String): Skill? {
        if (synchronized(warned) { warned.add(file) }) logger.warn("{} makes no skill: {}", file, why)
        return null
    }

    private class Cached(val modified: FileTime, val text: SkillText?)
}

/** The `SKILL.md` files of the skill directories in [directory], by directory name. */
internal fun skillFiles(directory: Path): List<Path> = try {
    if (!Files.isDirectory(directory)) {
        emptyList()
    } else {
        Files.list(directory).use { entries ->
            entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .map { it.resolve(SKILL_FILE) }
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted()
                .collect(Collectors.toList())
        }
    }
} catch (e: IOException) {
    logger.warn("The skill directory {} cannot be listed: {}", directory, e.toString())
    emptyList()
}

private val logger = LoggerFactory.getLogger(Skills::class.java)
