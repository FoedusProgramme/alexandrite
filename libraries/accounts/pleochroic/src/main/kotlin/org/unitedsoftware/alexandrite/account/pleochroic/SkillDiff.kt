package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/*
 * Skills are executable content: an agent runs what a `SKILL.md` or a script beside it tells it to, the same way
 * it would anything else it reads, so a pulled create, change or delete of one is always staged (dangerReasons),
 * never applied on its own. This is what a review shows for one: every file added, changed or removed, its size,
 * and a small line diff for a text file short enough to be worth showing in full.
 */

/** How one of a skill's files differs between its local and the account's pulled content. */
public enum class SkillFileStatus { ADDED, CHANGED, REMOVED }

/** One file of a pulled skill change, [path] relative to the skill's own directory. [textDiff] is a small
 * unified-style line diff, given only for a text file short enough ([diffOf]) to be worth showing in full. */
public class SkillFileChange(
    public val path: String,
    public val status: SkillFileStatus,
    public val sizeBefore: Int?,
    public val sizeAfter: Int?,
    public val textDiff: String?,
)

/** Every file [before] and [after] (two skills' `{"files": [...]}` documents, either null) disagree on. */
public fun skillFileChanges(before: JsonElement?, after: JsonElement?): List<SkillFileChange> {
    val beforeFiles = decodedSkillFiles(before)
    val afterFiles = decodedSkillFiles(after)
    val paths = (beforeFiles.keys + afterFiles.keys).sorted()
    return paths.mapNotNull { path ->
        val was = beforeFiles[path]
        val now = afterFiles[path]
        when {
            was == null && now != null ->
                SkillFileChange(path, SkillFileStatus.ADDED, null, now.bytes.size, diffOf(null, now))

            was != null && now == null ->
                SkillFileChange(path, SkillFileStatus.REMOVED, was.bytes.size, null, diffOf(was, null))

            was != null && now != null && !was.bytes.contentEquals(now.bytes) ->
                SkillFileChange(path, SkillFileStatus.CHANGED, was.bytes.size, now.bytes.size, diffOf(was, now))

            else -> null
        }
    }
}

private class DecodedFile(val bytes: ByteArray, val text: String?)

private fun decodedSkillFiles(data: JsonElement?): Map<String, DecodedFile> {
    val files = (data as? JsonObject)?.get("files") as? JsonArray ?: return emptyMap()
    val result = LinkedHashMap<String, DecodedFile>()
    for (entry in files) {
        val file = entry as? JsonObject ?: continue
        val path = (file["path"] as? JsonPrimitive)?.content ?: continue
        val encoding = (file["encoding"] as? JsonPrimitive)?.content ?: "utf8"
        val content = (file["content"] as? JsonPrimitive)?.content ?: continue
        val bytes = try {
            if (encoding == "base64") Base64.getDecoder().decode(content) else content.toByteArray(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            continue
        }
        result[path] = DecodedFile(bytes, if (encoding == "utf8") content else null)
    }
    return result
}

private const val MAX_DIFF_BYTES = 64 * 1024
private const val MAX_DIFF_LINES = 400

/** A small unified-style line diff of [before] and [after], null when either side is not text, or together they
 * are too large to be worth rendering in full (a pulled skill's own file-count/size caps apply well before this). */
private fun diffOf(before: DecodedFile?, after: DecodedFile?): String? {
    val oldText = before?.text
    val newText = after?.text
    if (before != null && oldText == null) return null
    if (after != null && newText == null) return null
    if ((before?.bytes?.size ?: 0) + (after?.bytes?.size ?: 0) > MAX_DIFF_BYTES) return null
    val oldLines = oldText?.split("\n") ?: emptyList()
    val newLines = newText?.split("\n") ?: emptyList()
    if (oldLines.size + newLines.size > MAX_DIFF_LINES) return null
    return unifiedDiff(oldLines, newLines)
}

/**
 * A minimal line diff of [oldLines] and [newLines] by their longest common subsequence: every line is prefixed
 * `- ` (only in [oldLines]), `+ ` (only in [newLines]) or two spaces (common to both), in order, with no
 * surrounding context trimmed (the files this is used for are already small).
 */
private fun unifiedDiff(oldLines: List<String>, newLines: List<String>): String {
    val n = oldLines.size
    val m = newLines.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (oldLines[i] == newLines[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val out = StringBuilder()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            oldLines[i] == newLines[j] -> {
                out.append("  ").append(oldLines[i]).append('\n')
                i++
                j++
            }

            lcs[i + 1][j] >= lcs[i][j + 1] -> {
                out.append("- ").append(oldLines[i]).append('\n')
                i++
            }

            else -> {
                out.append("+ ").append(newLines[j]).append('\n')
                j++
            }
        }
    }
    while (i < n) {
        out.append("- ").append(oldLines[i]).append('\n')
        i++
    }
    while (j < m) {
        out.append("+ ").append(newLines[j]).append('\n')
        j++
    }
    return out.toString().trimEnd('\n')
}
