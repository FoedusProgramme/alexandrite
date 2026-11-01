package org.unitedsoftware.alexandrite.agent.prompt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.Agent
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.config.InstructionFile
import org.unitedsoftware.alexandrite.agent.config.PersonaConfig
import org.unitedsoftware.alexandrite.internal.io.readUtf8
import org.unitedsoftware.alexandrite.internal.text.fittingLength
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** What decides which instruction files a turn loads. */
internal class PersonaConditions(
    /** Null when the turn comes from no message. */
    val chatKind: ChatKind?,
    val turnKind: TurnKind,
)

/** The agents' instructions: inline text and files, which are read at the start and again when they change. */
@Singleton
internal class Persona(directory: AgentDirectory, settings: AgentSettings, paths: HostPaths, places: AgentPlaces) :
    Lifecycle {
    private val personas = directory.agents.associate { it.id to AgentPersona(it, settings.persona, paths, places) }

    override suspend fun onStart() {
        withContext(Dispatchers.IO) { personas.values.forEach { it.load() } }
    }

    /** The instruction sections of [agent] that a turn under [conditions] gets, from its files as they are now. */
    suspend fun sections(agent: AgentId, conditions: PersonaConditions): List<PromptSection> {
        val persona = requireNotNull(personas[agent]) { "No agent '$agent' is configured." }
        return withContext(Dispatchers.IO) { persona.sections(conditions) }
    }

    /** The paths of the instruction files that [agent] reads. */
    fun files(agent: AgentId): List<Path> = personas[agent]?.sources.orEmpty()
}

private class AgentPersona(agent: Agent, private val limits: PersonaConfig, paths: HostPaths, places: AgentPlaces) {
    private val id = agent.id
    private val inline = agent.config.instructions
    private val files: List<PersonaFile>
    private val lock = Mutex()

    val sources: List<Path> get() = files.mapNotNull { it.path }

    init {
        val directory = paths.configFile?.parent
        val path = "agent.agents.$id.instructionFiles"
        val entries = agent.config.instructionFiles
        files = if (entries.isEmpty()) {
            val file = places.personaDirectory(id)?.resolve(PERSONA_FILE)
            listOfNotNull(file?.let { PersonaFile(id, path, "$it", it, false, emptyList()) })
        } else {
            entries.map { entry -> PersonaFile(id, path, entry.file, resolve(entry, directory), true, entry.`when`) }
        }
    }

    /** The text each file shows in the prompt by the files that apply, null for one that is absent or does not apply. */
    private val shown = HashMap<List<Boolean>, List<String?>>()

    /** The cuts logged since the files last changed, by file index and the characters kept. */
    private val logged = HashSet<Pair<Int, Int>>()

    suspend fun load() {
        lock.withLock { files.forEach { it.load() } }
    }

    suspend fun sections(conditions: PersonaConditions): List<PromptSection> = lock.withLock {
        if (files.map { it.refresh() }.any { it }) {
            shown.clear()
            logged.clear()
        }
        val applying = files.map { it.applies(conditions) }
        val texts = inline + shown.getOrPut(applying) { cap(applying) }
        texts.mapIndexedNotNull { index, text ->
            text?.takeIf { it.isNotBlank() }?.let { PromptSection(instructionsSection(index + 1), it, stable = true) }
        }
    }

    /** The text each of the files [applying] shows, which share the total limit in config order. */
    private fun cap(applying: List<Boolean>): List<String?> {
        var left = limits.maxTotalChars
        return files.mapIndexed { index, file ->
            val text = file.text?.takeIf { applying[index] } ?: return@mapIndexed null
            val kept = fittingLength(text, minOf(limits.maxFileChars, left))
            left -= kept
            if (kept == text.length) return@mapIndexed text
            if (logged.add(index to kept)) {
                logger.warn(
                    "Instruction file {} of agent '{}' has {} characters: the prompt takes the first {} " +
                        "(agent.persona.maxFileChars {}, maxTotalChars {})",
                    file.path,
                    id,
                    text.length,
                    kept,
                    limits.maxFileChars,
                    limits.maxTotalChars,
                )
            }
            text.substring(0, kept) + cutMarker(kept, text.length)
        }
    }
}

/** The path of [entry], null when it is relative and there is no [directory] to resolve it against. */
private fun resolve(entry: InstructionFile, directory: Path?): Path? {
    val path = Paths.get(entry.file)
    return if (path.isAbsolute) path.normalize() else directory?.resolve(path)?.normalize()
}

/** One instruction file of [agent], [configured] as written in the config at [configPath]. */
private class PersonaFile(
    private val agent: AgentId,
    private val configPath: String,
    private val configured: String,
    val path: Path?,
    private val required: Boolean,
    conditions: List<String>,
) {
    private val chatKinds = conditions.filter { id -> ChatKind.entries.any { it.id == id } }.map(ChatKind::of)
    private val turnKinds = conditions.filter { id -> TurnKind.entries.any { it.id == id } }.map(TurnKind::of)
    private var stamp: Stamp? = null
    private var failing = false

    var text: String? = null
        private set

    fun applies(conditions: PersonaConditions): Boolean = (chatKinds.isEmpty() || conditions.chatKind in chatKinds) &&
        (turnKinds.isEmpty() || conditions.turnKind in turnKinds)

    /** Reads the file, throwing when the start must fail. */
    fun load() {
        val path = path ?: throw ConfigException(
            configPath,
            "the instruction file '$configured' is relative, but the host read no config file to resolve it " +
                "against: give an absolute path",
        )
        try {
            stamp = stampOf(path)
            text = readUtf8(path)
        } catch (e: NoSuchFileException) {
            if (required) throw ConfigException(configPath, "the instruction file $path does not exist")
        } catch (e: CharacterCodingException) {
            throw ConfigException(configPath, "the instruction file $path is no UTF-8 text")
        } catch (e: IOException) {
            throw ConfigException(configPath, "the instruction file $path cannot be read: $e")
        }
    }

    /** Reads the file again when it changed, and tells whether its text changed. */
    fun refresh(): Boolean {
        val path = path ?: return false
        val current = try {
            stampOf(path)
        } catch (e: NoSuchFileException) {
            return missing(path)
        } catch (e: IOException) {
            return failed(path, e)
        }
        if (current == stamp) {
            failing = false
            return false
        }
        val read = try {
            readUtf8(path)
        } catch (e: IOException) {
            return failed(path, e)
        }
        failing = false
        stamp = current
        text = read
        logger.info("Agent '{}' reads its instruction file {} again", agent, path)
        return true
    }

    private fun missing(path: Path): Boolean {
        if (required) return failed(path, null)
        if (text == null) return false
        stamp = null
        text = null
        logger.info("Agent '{}' no longer has the instruction file {}", agent, path)
        return true
    }

    private fun failed(path: Path, error: IOException?): Boolean {
        if (!failing) {
            failing = true
            val why = error?.toString() ?: "it does not exist"
            logger.warn(
                "Agent '{}' keeps the last text of its instruction file {}, which cannot be read: {}",
                agent,
                path,
                why,
            )
        }
        return false
    }
}

private data class Stamp(val modified: FileTime, val size: Long, val key: Any?)

private fun stampOf(path: Path): Stamp {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
    return Stamp(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey())
}

internal fun cutMarker(kept: Int, length: Int): String =
    "\n\n[Cut: the first $kept of $length characters of this file.]"

/** The persona file of an agent whose config lists no instruction files. */
private const val PERSONA_FILE = "PERSONA.md"

private val logger = LoggerFactory.getLogger(Persona::class.java)
