package org.unitedsoftware.alexandrite.agent.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.config.configuredPath
import org.unitedsoftware.alexandrite.agent.control.Granter
import org.unitedsoftware.alexandrite.agent.i18n.Refused
import org.unitedsoftware.alexandrite.agent.i18n.TextKeys
import org.unitedsoftware.alexandrite.agent.permission.canonical
import org.unitedsoftware.alexandrite.agent.permission.denial
import org.unitedsoftware.alexandrite.agent.project.isRepository
import org.unitedsoftware.alexandrite.agent.who
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.sdk.turn.TrustedFolder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * The trusted folders that the config declares for every agent and those granted at runtime to one agent, read without
 * suspending.
 *
 * A change that breaks a rule throws [Refused].
 */
@Singleton
internal class FolderTrust(settings: AgentSettings, paths: HostPaths, private val floor: HardFloor, records: Records) :
    Lifecycle {
    private val grants = records.collection<FolderGrant>(COLLECTION)

    private val agents = settings.agents.keys.map(::AgentId)

    /** Held from checking a change until it is applied. */
    private val writing = Mutex()

    private val declared: List<Path> = configured(settings.trustedFolders, paths, floor).distinct()

    @Volatile
    private var granted: Map<AgentId, List<TrustedFolder>> = emptyMap()

    override suspend fun onStart() {
        writing.withLock {
            migrate()
            granted = grants.list().mapNotNull { record ->
                record.value.agent?.let { agent -> granted(record)?.let { agent to it } }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, folders) -> folders.distinctBy { it.path } }
        }
    }

    /** The folders that [agent] trusts, the declared ones first. */
    fun folders(agent: AgentId): List<TrustedFolder> {
        val own = granted[agent].orEmpty()
        return declared.map { path ->
            own.firstOrNull { it.path == path }?.let { TrustedFolder(path, true, it.trustedAt, it.trustedBy) }
                ?: TrustedFolder(path, true, null, null)
        } + own.filter { it.path !in declared }
    }

    /** The folder nearest to [folder] (canonical) that [agent] trusts and that is it or holds it, else null. */
    fun covering(agent: AgentId, folder: Path): TrustedFolder? =
        folders(agent).filter { covers(it.path, folder) }.maxByOrNull { it.path.nameCount }

    /** The folder nearest to [folder] (canonical) that any agent trusts and that is it or holds it, else null. */
    fun coveringAny(folder: Path): Path? = (declared + granted.values.flatten().map { it.path })
        .filter { covers(it, folder) }
        .maxByOrNull { it.nameCount }

    /** Whether [folder] (canonical) is a folder that [agent] trusts itself. */
    fun isTrusted(agent: AgentId, folder: Path): Boolean =
        folder in declared || granted[agent].orEmpty().any { it.path == folder }

    /** Trusts [folder] for [agent] as [by] asked, and returns the trusted folder that covers it. */
    suspend fun trust(agent: AgentId, folder: Path, by: ChatUser?): Path {
        val canonical = when (val check = check(folder)) {
            is FloorCheck.Allowed -> check.canonical

            is FloorCheck.Ask -> check.canonical

            else ->
                throw Refused(TextKeys.FOLDER_PROTECTED, "folder" to folder, "reason" to check.denial)
        }
        if (!Files.isDirectory(canonical)) throw Refused(TextKeys.FOLDER_MISSING, "folder" to folder)
        writing.withLock {
            covering(agent, canonical)?.let { return it.path }
            val grant = grants.put(key(agent, "$canonical"), FolderGrant(agent, by?.let(::Granter)), by?.address)
            val trusted = TrustedFolder(canonical, false, grant.createdAt, by)
            granted = granted + (agent to granted[agent].orEmpty() + trusted)
        }
        logger.info("Agent '{}' trusts folder {}, as {} asked", agent, canonical, who(by))
        return canonical
    }

    /**
     * Stops trusting [folder] at runtime for [agent] as [by] asked, removing each grant of it as named or as it is
     * canonical today, and tells whether the config still trusts it.
     */
    suspend fun untrust(agent: AgentId, folder: Path, by: ChatUser?): Revocation {
        val canonical = check(folder).canonical
        val revocation = writing.withLock {
            val removed = grants.list().filter { record ->
                record.value.agent == agent && isGrantOf(grantedPath(record), folder, canonical)
            }.count { grants.remove(it.key) }
            val own = granted[agent].orEmpty()
            val left = own.filterNot { it.path == canonical }
            granted = granted + (agent to left)
            Revocation(removed > 0 || left.size < own.size, canonical in declared)
        }
        when {
            revocation.declared && revocation.removed -> logger.info(
                "Agent '{}' no longer trusts folder {} at runtime, as {} asked: the config still trusts it",
                agent,
                folder,
                who(by),
            )

            revocation.removed ->
                logger.info("Agent '{}' no longer trusts folder {}, as {} asked", agent, folder, who(by))
        }
        return revocation
    }

    /**
     * Rewrites each grant from when folders were trusted for every agent into one grant per configured agent, kept from
     * when and by whom it was made.
     */
    private suspend fun migrate() {
        if (agents.isEmpty()) return
        for (record in grants.list().filter { it.value.agent == null }) {
            for (agent in agents) {
                val key = key(agent, record.key)
                if (grants.get(key) != null) continue
                val trustedAt = record.value.trustedAt ?: record.createdAt.toEpochMilli()
                grants.put(key, FolderGrant(agent, record.value.by, trustedAt), record.createdBy)
            }
            grants.remove(record.key)
            logger.info("The grant of folder {} now holds for each of the agents {}", record.key, agents)
        }
    }

    private fun check(folder: Path): FloorCheck {
        if (!folder.isAbsolute) throw Refused(TextKeys.FOLDER_NOT_ABSOLUTE, "folder" to folder)
        return floor.checkRead(folder)
    }

    /** Whether the grant of [path] is of [folder], as named or as the folder is canonical today, at [canonical]. */
    private fun isGrantOf(path: Path?, folder: Path, canonical: Path?): Boolean {
        if (path == null) return false
        if (path.normalize() == folder.normalize()) return true
        if (canonical == null) return false
        return path == canonical || floor.checkRead(path).canonical == canonical
    }

    /** The folder that [grant] trusts, null when the floor now refuses it. */
    private fun granted(grant: StoredRecord<FolderGrant>): TrustedFolder? {
        val path = grantedPath(grant)
        val check = path?.let(floor::checkRead)
        val canonical = check?.canonical
        if (canonical == null) {
            val reason = (check as? FloorCheck.Denied)?.reason ?: "no absolute path"
            logger.warn("The grant of folder {} is ignored: {}", grant.key, reason)
            return null
        }
        val trustedAt = grant.value.trustedAt?.let(Instant::ofEpochMilli) ?: grant.createdAt
        return TrustedFolder(canonical, false, trustedAt, grant.value.by?.user())
    }

    private fun key(agent: AgentId, folder: String): String = "$agent/$folder"

    /** The absolute path that [grant] trusts, null when it names none. */
    private fun grantedPath(grant: StoredRecord<FolderGrant>): Path? =
        absolute(grant.value.agent?.let { grant.key.removePrefix("$it/") } ?: grant.key)
}

/**
 * A folder trusted at runtime for [agent], null for a grant from when folders were trusted for every agent, since
 * [trustedAt] (epoch milliseconds) when it was carried over from such a grant.
 */
@Serializable
private class FolderGrant(val agent: AgentId? = null, val by: Granter? = null, val trustedAt: Long? = null)

private fun absolute(text: String): Path? = try {
    Paths.get(text).takeIf { it.isAbsolute }
} catch (e: InvalidPathException) {
    null
}

/**
 * The folders of [entries], relative ones resolved against the directory of the config file in [paths].
 *
 * Throws a [ConfigException] for a folder that [floor] refuses.
 */
private fun configured(entries: List<String>, paths: HostPaths, floor: HardFloor): List<Path> = entries.map { entry ->
    val path = configuredPath(paths, "agent.$KEY", "the trusted folder", entry)
    when (val check = floor.checkRead(path)) {
        is FloorCheck.Allowed -> check.canonical

        is FloorCheck.Ask -> check.canonical

        else -> throw ConfigException(
            "agent.$KEY",
            "the trusted folder $path is a protected place (${check.denial}), which no file tool reaches",
        )
    }
}

/** Whether [folder] is [trusted] or lies in it outside every repository nested in it. */
private fun covers(trusted: Path, folder: Path): Boolean = folder.startsWith(trusted) &&
    generateSequence(folder) { it.parent }.takeWhile { it != trusted }.none(::isRepository)

private const val KEY = "trustedFolders"

private const val COLLECTION = "trusted_folders"

private val logger = LoggerFactory.getLogger(FolderTrust::class.java)
