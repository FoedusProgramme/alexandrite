package org.unitedsoftware.alexandrite.agent.skill

import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.io.readAtMost
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.internal.path.openRegularFile
import org.unitedsoftware.alexandrite.internal.text.Utf8Text
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** The file that holds a skill in the skill's directory, as the Agent Skills format names it. */
internal const val SKILL_FILE: String = "SKILL.md"

/** The bytes of a skill file that are read at most. */
internal const val MAX_SKILL_BYTES: Int = 64 * 1024

/** A skill file as read: the name and description of its front matter, its body and the SHA-256 of its bytes. */
internal class SkillText(val name: String?, val description: String?, val body: String, val sha256: String)

/**
 * The skill file [file], null when it is no regular file, has a second hard link and [oneLink], is over
 * [MAX_SKILL_BYTES] or is no UTF-8.
 */
internal fun readSkill(file: Path, oneLink: Boolean): SkillText? = try {
    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isRegularFile || (oneLink && hardLinks(file) > 1) || attributes.size() > MAX_SKILL_BYTES) {
        null
    } else {
        parseSkill(openRegularFile(file, oneLink).use { it.readAtMost(MAX_SKILL_BYTES + 1) })
    }
} catch (e: IOException) {
    null
}

/** The skill file of [bytes], null when they are more than [MAX_SKILL_BYTES] or no UTF-8 text. */
internal fun parseSkill(bytes: ByteArray): SkillText? {
    if (bytes.size > MAX_SKILL_BYTES) return null
    val text = Utf8Text.decode(bytes)?.text?.removePrefix("") ?: return null
    val sha256 = sha256Hex(bytes)
    val lines = text.lines()
    if (lines.firstOrNull()?.trimEnd() != FENCE) return SkillText(null, null, text.trim(), sha256)
    val end = lines.drop(1).indexOfFirst { it.trimEnd() == FENCE }.takeIf { it >= 0 }?.plus(1)
        ?: return SkillText(null, null, text.trim(), sha256)
    val fields = frontMatter(lines.subList(1, end))
    val body = lines.drop(end + 1).joinToString("\n").trim()
    return SkillText(fields["name"], fields["description"], body, sha256)
}

/**
 * The single-line `key: value` fields of a YAML front matter, a value quoted or not, and a `>` or `|` block made of the
 * indented lines below its key, joined by spaces.
 */
private fun frontMatter(lines: List<String>): Map<String, String> {
    val fields = LinkedHashMap<String, String>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index++]
        val match = FIELD.matchEntire(line) ?: continue
        val (key, raw) = match.destructured
        val value = if (raw.trim() in BLOCKS) {
            val block = mutableListOf<String>()
            while (index < lines.size && (lines[index].isBlank() || lines[index].first().isWhitespace())) {
                block += lines[index++].trim()
            }
            block.filter { it.isNotEmpty() }.joinToString(" ")
        } else {
            unquoted(raw.trim())
        }
        if (value.isNotEmpty()) fields.putIfAbsent(key, value)
    }
    return fields
}

private fun unquoted(value: String): String = if (value.length >= 2 && value.first() == value.last() &&
    value.first() in "\"'"
) {
    value.substring(1, value.length - 1)
} else {
    value
}

private const val FENCE = "---"

private val FIELD = Regex("([A-Za-z_][A-Za-z0-9_-]*):(.*)")

private val BLOCKS = setOf(">", "|", ">-", "|-")
