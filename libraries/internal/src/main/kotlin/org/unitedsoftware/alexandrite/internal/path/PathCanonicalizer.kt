package org.unitedsoftware.alexandrite.internal.path

import org.unitedsoftware.alexandrite.internal.portable.ProcessIds
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** An absolute path in the two forms that a check compares. */
public class CanonicalPath internal constructor(
    /** With `.` and `..` folded and `/proc/self` pinned to the process where its id is known. */
    public val lexical: Path,
    /** The real path of the longest existing prefix with the rest appended, dangling links followed. */
    public val real: Path,
    /** How the directories of the existing prefix compare names. */
    public val nameCase: NameCase,
    private val firmlinks: Boolean,
) {
    /** The comparison keys of [forms], case-folded when [ignoreCase]. */
    internal fun keys(ignoreCase: Boolean, forms: Collection<PathForm> = PathForm.entries): List<String> =
        forms.map { unalias(nameKey(path(it).toString(), ignoreCase)) }.distinct()

    /** [form] as it names an entry, case-folded when [ignoreCase]. */
    internal fun entryKey(form: PathForm, ignoreCase: Boolean): String =
        unalias(nameKey(path(form).toString(), ignoreCase))

    private fun path(form: PathForm): Path = when (form) {
        PathForm.LEXICAL -> lexical
        PathForm.REAL -> real
    }

    private fun unalias(key: String): String = if (firmlinks) foldDataVolume(key) else key
}

/** One of the two forms of a [CanonicalPath]. */
internal enum class PathForm {
    LEXICAL,
    REAL,
}

/** Makes absolute paths canonical, lexically and through the filesystem. */
public class PathCanonicalizer(
    /** How the directories of an existing real path compare names. */
    private val nameCase: (Path) -> NameCase = NameCaseProbe()::nameCase,
    /** Whether `/System/Volumes/Data/…` names `/…` too, as macOS firmlinks make it. */
    private val firmlinks: Boolean = OsFamily.HOST == OsFamily.MAC,
    /** This process's id, which `/proc/self` is pinned to, null to leave it as written. */
    private val pid: Long? = ProcessIds.current,
) {
    public fun canonicalize(path: Path): CanonicalPath {
        require(path.isAbsolute && path.fileSystem == FileSystems.getDefault()) {
            "A path to canonicalize is absolute and of the default file system, was '$path'."
        }
        val resolved = resolve(path, 0)
        val nameCase = resolved.existing?.let(nameCase) ?: NameCase.UNKNOWN
        return CanonicalPath(pinned(path.normalize()), pinned(resolved.real), nameCase, firmlinks)
    }

    private class Resolved(val real: Path, val existing: Path?)

    /** [absolute] through the real path of its longest existing prefix, a dangling link followed by hand. */
    private fun resolve(absolute: Path, depth: Int): Resolved {
        var current: Path? = absolute
        val rest = ArrayDeque<String>()
        while (current != null) {
            val existing = realPath(current)
            if (existing != null) return Resolved(appended(existing, rest).normalize(), existing)
            if (depth < MAX_LINK_DEPTH && Files.isSymbolicLink(current)) {
                val target = try {
                    (current.parent ?: current).resolve(Files.readSymbolicLink(current))
                } catch (e: IOException) {
                    null
                }
                if (target != null) return resolve(appended(target, rest), depth + 1)
            }
            current.fileName?.let { rest.addFirst(it.toString()) }
            current = current.parent
        }
        return Resolved(absolute.normalize(), null)
    }

    private fun realPath(path: Path): Path? = try {
        path.toRealPath()
    } catch (e: IOException) {
        null
    }

    private fun appended(path: Path, parts: Iterable<String>): Path = parts.fold(path, Path::resolve)

    private fun pinned(path: Path): Path {
        if (pid == null) return path
        val text = path.toString()
        val rest = when {
            text == "/proc/self" || text == "/proc/thread-self" -> ""
            text.startsWith("/proc/self/") -> text.removePrefix("/proc/self")
            text.startsWith("/proc/thread-self/") -> text.removePrefix("/proc/thread-self")
            else -> return path
        }
        return Paths.get("/proc/$pid$rest")
    }

    private companion object {
        const val MAX_LINK_DEPTH = 40
    }
}
