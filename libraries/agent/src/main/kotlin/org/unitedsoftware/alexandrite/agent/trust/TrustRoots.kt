package org.unitedsoftware.alexandrite.agent.trust

import org.unitedsoftware.alexandrite.agent.project.hasProjectMarker
import org.unitedsoftware.alexandrite.agent.project.isRepository
import org.unitedsoftware.alexandrite.internal.path.homeDirectory
import org.unitedsoftware.alexandrite.internal.text.ReviewText
import org.unitedsoftware.alexandrite.sdk.channel.TrustRoot
import org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** What a person may trust so that a source no longer taints. */
internal sealed interface Root {
    /** How an approver is shown it. */
    val shown: TrustRoot

    /** A trusted folder. */
    class Folder(val path: Path) : Root {
        override val shown: TrustRoot get() = TrustRoot(TrustRootKind.FOLDER, ReviewText.escapeName("$path"))
    }

    /** A trust rule [rule] of the session, shown as [name] of [kind]. */
    class Rule(val kind: TrustRootKind, val name: String, val rule: String) : Root {
        override val shown: TrustRoot get() = TrustRoot(kind, ReviewText.escapeName(name))
    }

    /** A trusted person. */
    class Person(val person: ChatUser) : Root {
        override val shown: TrustRoot
            get() = TrustRoot.person(person, ReviewText.escapeName(named(person)))
    }
}

/** A person's name as approvers are shown it, with their handle where the channel knows one. */
internal fun named(person: ChatUser): String =
    person.displayName.ifBlank { "${person.address}" } + person.username?.let { " (@$it)" }.orEmpty()

/** Why a call would bring the first untrusted content into a conversation, and what trusting would keep it clean. */
internal class Foreseen(
    /** In lower case without a closing full stop, in Alexandrite's own words. */
    val reason: String,
    /** One per source, the folders still to choose among their candidates, empty when no root would keep it clean. */
    val sources: List<Source>,
)

/** A source of a [Foreseen] taint. */
internal sealed interface Source {
    /** A root to trust that needs no choice. */
    class Fixed(val root: Root) : Source

    /** A path whose folder root is one of [candidates], innermost first, and most likely [likely]. */
    class Folders(val path: Path, val candidates: List<Path>, val likely: Path) : Source
}

/** Finds the folders that a person may trust to keep a file from tainting. */
internal class FolderRoots(private val floor: HardFloor, private val home: Path? = homeDirectory()?.let(::pathOf)) {
    /**
     * The candidates for [folder] (canonical), innermost first: the folder itself and its ancestors that carry a project
     * marker, up to the nearest repository, never the home directory, an ancestor of it, `/` or a system directory.
     */
    fun candidates(folder: Path, agent: AgentId): List<Path> {
        if (excluded(folder) || !allowed(folder, agent)) return emptyList()
        val found = mutableListOf(folder)
        if (isRepository(folder)) return found
        var current = folder.parent
        while (current != null && !excluded(current) && allowed(current, agent)) {
            if (hasProjectMarker(current)) found.add(current)
            if (isRepository(current)) break
            current = current.parent
        }
        return found
    }

    /** The most likely of [candidates]: the innermost that carries a project marker, else the first. */
    fun likely(candidates: List<Path>): Path = candidates.firstOrNull(::hasProjectMarker) ?: candidates.first()

    private fun excluded(folder: Path): Boolean {
        if (folder.parent == null || "$folder" in TOP_LEVEL) return true
        if (home != null && home.startsWith(folder)) return true
        return SYSTEM.any { folder.startsWith(it) }
    }

    private fun allowed(folder: Path, agent: AgentId): Boolean =
        floor.checkRead(folder, agent).let { it is FloorCheck.Allowed || it is FloorCheck.Ask }
}

private fun pathOf(text: String): Path? = try {
    Paths.get(text).toAbsolutePath().normalize()
} catch (e: InvalidPathException) {
    null
}

/** Folders that are never offered themselves, though folders below them may be. */
private val TOP_LEVEL = setOf(
    "/home", "/Users", "/tmp", "/private", "/private/tmp", "/private/var", "/var", "/opt", "/srv", "/mnt", "/media",
    "/Volumes", "/root", "/var/folders", "/private/var/folders",
)

/** Folders that are never offered, nor any folder below them. */
private val SYSTEM = listOf(
    "/bin", "/sbin", "/usr", "/etc", "/lib", "/lib32", "/lib64", "/boot", "/dev", "/proc", "/sys", "/run", "/System",
    "/Library", "/Applications", "/private/etc", "/var/lib", "/var/log", "/var/run", "/private/var/log", "/cores",
).map { Paths.get(it) }
