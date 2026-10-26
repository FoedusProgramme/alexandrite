package org.unitedsoftware.alexandrite.discovery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The file every Agent Skills format directory holds, shared verbatim by Alexandrite, Claude Code, Codex and
 * Hermes alike: a YAML front matter of `name`/`description` (among other fields this reads past) and a Markdown
 * body. */
private const val SKILL_FILE: String = "SKILL.md"

/** The bytes of a `SKILL.md` read at most. */
private const val MAX_SKILL_BYTES: Int = 64 * 1024

/** How deep [findSkillDirectories] walks below its root: enough for a flat `skills/<name>/SKILL.md` layout
 * (Claude Code's, Codex's) and a categorised `skills/<category>/<name>/SKILL.md` one (Hermes's) alike. */
private const val MAX_SCAN_DEPTH: Int = 3

/** Regular files a skill may hold at once, and their bytes together, matching Alexandrite's own sync caps
 * (`docs/API.md` "Skill" § "校验"); a skill over either is not imported, left for the person to trim by hand. */
public const val MAX_SKILL_FILES: Int = 200
public const val MAX_SKILL_TOTAL_BYTES: Long = 4L * 1024 * 1024

/**
 * Every directory at or below [root], down to [maxDepth], that directly holds a `SKILL.md`. A directory whose
 * name starts with `.` is skipped entirely (bundled or internal state such as a tool's own cache or lock file,
 * never a skill a person added), and once a `SKILL.md` is found inside a directory, that directory's own children
 * are not walked further (a skill's `scripts/`, `references/` or `assets/` hold no nested skill of their own).
 * Empty when [root] does not exist or holds no skill.
 */
public fun findSkillDirectories(root: Path, maxDepth: Int = MAX_SCAN_DEPTH): List<Path> {
    if (!Files.isDirectory(root)) return emptyList()
    val found = mutableListOf<Path>()
    fun walk(directory: Path, depth: Int) {
        if (Files.isRegularFile(directory.resolve(SKILL_FILE))) {
            found.add(directory)
            return
        }
        if (depth >= maxDepth) return
        val children = try {
            Files.newDirectoryStream(directory).use { stream -> stream.toList() }
        } catch (e: IOException) {
            emptyList()
        }
        children.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
            .sortedBy { it.fileName.toString() }
            .forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return found
}

/**
 * The `name` and `description` of the `SKILL.md` front matter at [directory], [DiscoveredSkill.name] falling
 * back to the directory's own name when the front matter gives none. Null when [directory] holds no readable
 * `SKILL.md`, or one over [MAX_SKILL_BYTES].
 */
public fun readSkillFrontMatter(directory: Path): DiscoveredSkill? {
    val file = directory.resolve(SKILL_FILE)
    val text = try {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_SKILL_BYTES) return null
        Files.readString(file)
    } catch (e: IOException) {
        return null
    }
    val fields = frontMatterFields(text)
    val name = fields["name"]?.takeIf(String::isNotBlank) ?: directory.fileName.toString()
    return DiscoveredSkill(name, fields["description"], directory)
}

/** The single-line `key: value` fields of a `SKILL.md`'s YAML front matter, a value quoted or not. */
private fun frontMatterFields(text: String): Map<String, String> {
    val lines = text.lines()
    if (lines.firstOrNull()?.trimEnd() != FENCE) return emptyMap()
    val end = lines.drop(1).indexOfFirst { it.trimEnd() == FENCE }.takeIf { it >= 0 }?.plus(1) ?: return emptyMap()
    val fields = linkedMapOf<String, String>()
    for (line in lines.subList(1, end)) {
        val match = FIELD.matchEntire(line) ?: continue
        val (key, raw) = match.destructured
        val value = unquoted(raw.trim())
        if (value.isNotEmpty()) fields.putIfAbsent(key, value)
    }
    return fields
}

private fun unquoted(value: String): String =
    if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") {
        value.substring(1, value.length - 1)
    } else {
        value
    }

private const val FENCE = "---"
private val FIELD = Regex("([A-Za-z_][A-Za-z0-9_-]*):(.*)")

/** A skill name as Alexandrite's own skills take it: lowercase letters, digits and `-`, at most 64 characters. */
private val SKILL_NAME = Regex("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?")

/**
 * Copies [skill]'s directory into a new directory of [skillsRoot], named after [skill] (lowercased and every run
 * of characters [SKILL_NAME] disallows turned into one `-`), or, when that name is already in [taken], a numbered
 * variant of it. Symlinks inside [skill]'s directory are not followed (its own files only); at most [maxFiles] of
 * them and [maxTotalBytes] bytes together, Alexandrite's own sync caps by default. Returns the name used, or null
 * after copying nothing: the source held no file, held more than the caps allow, or could not be read or written.
 */
public fun importSkill(
    skill: DiscoveredSkill,
    skillsRoot: Path,
    taken: Set<String>,
    maxFiles: Int = MAX_SKILL_FILES,
    maxTotalBytes: Long = MAX_SKILL_TOTAL_BYTES,
): String? {
    val files = try {
        Files.walk(skill.directory).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
    } catch (e: IOException) {
        return null
    }
    if (files.isEmpty() || files.size > maxFiles) return null
    val total = try {
        files.sumOf { Files.size(it) }
    } catch (e: IOException) {
        return null
    }
    if (total > maxTotalBytes) return null
    val name = freeSkillName(skill.name, taken)
    val target = skillsRoot.resolve(name)
    return try {
        Files.createDirectories(target)
        files.forEach { file ->
            val destination = target.resolve(skill.directory.relativize(file).toString())
            Files.createDirectories(destination.parent)
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        name
    } catch (e: IOException) {
        null
    }
}

/** [base], sanitized to a valid Alexandrite skill name, with a numbered suffix added until it is free of [taken]. */
private fun freeSkillName(base: String, taken: Set<String>): String {
    val sanitized = base.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "skill" }
    val valid = if (SKILL_NAME.matches(sanitized)) sanitized else sanitized.take(64).trim('-').ifEmpty { "skill" }
    if (valid !in taken) return valid
    var suffix = 2
    while ("$valid-$suffix" in taken) suffix++
    return "$valid-$suffix"
}
