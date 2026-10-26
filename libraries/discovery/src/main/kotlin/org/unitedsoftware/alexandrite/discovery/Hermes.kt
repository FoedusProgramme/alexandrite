package org.unitedsoftware.alexandrite.discovery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val HERMES_SKILLS_DIR = "skills"
private const val HERMES_CONFIG_FILE = "config.yaml"

/**
 * Hermes (`~/.hermes`): `config.yaml`'s `mcp_servers:` block, and the skills under `skills/` (its own
 * `external_dirs` setting is not followed here — only what `~/.hermes` itself directly holds, read-only).
 * Null when `~/.hermes` does not exist at all; present with empty lists when it does but configures neither.
 */
public fun discoverHermes(home: Path): DiscoveredSource? {
    val root = home.resolve(".hermes")
    if (!Files.isDirectory(root)) return null
    val servers = try {
        hermesMcpServers(Files.readString(root.resolve(HERMES_CONFIG_FILE)))
    } catch (e: IOException) {
        emptyList()
    }
    val skills = findSkillDirectories(root.resolve(HERMES_SKILLS_DIR)).mapNotNull(::readSkillFrontMatter)
    return DiscoveredSource("hermes", "Hermes", skills, servers)
}

/**
 * The `mcp_servers:` block of a Hermes `config.yaml`, read as a minimal, indentation-based subset of YAML (a
 * mapping of server name to `command`/`args`/`env`/`url`/`headers`; Hermes's own per-server settings such as
 * `timeout`, `lazy` or `sampling` are not read, since none of them are an Alexandrite setting). A commented-out
 * line (Hermes ships its own `mcp_servers:` documentation this way) is blank once its comment is stripped, so it
 * never matches and is never read as a real server — only an operator's own, uncommented block is.
 */
internal fun hermesMcpServers(text: String): List<DiscoveredMcpServer> {
    val lines = text.lines().map(::stripYamlComment)
    val start = lines.indexOfFirst { it == "mcp_servers:" }
    if (start < 0) return emptyList()
    val block = mutableListOf<String>()
    for (line in lines.drop(start + 1)) {
        if (line.isBlank()) continue
        if (indentOf(line) == 0) break
        block += line
    }
    val tree = parseIndentedMap(block)
    return tree.mapNotNull { (name, value) ->
        val fields = value as? Map<*, *> ?: return@mapNotNull null
        DiscoveredMcpServer(
            name = name,
            command = fields["command"] as? String,
            args = (fields["args"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            env = stringMapOf(fields["env"]),
            cwd = fields["cwd"] as? String,
            url = fields["url"] as? String,
            headers = stringMapOf(fields["headers"]),
        )
    }
}

private fun stringMapOf(value: Any?): Map<String, String> =
    (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }.orEmpty()

/** A `#` outside any quotes starts a comment, cut away along with the whitespace before it. */
private fun stripYamlComment(line: String): String {
    var quote = false
    for (i in line.indices) {
        val c = line[i]
        if (c == '"' || c == '\'') quote = !quote
        if (c == '#' && !quote) return line.substring(0, i).trimEnd()
    }
    return line
}

private fun indentOf(line: String): Int = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }

/**
 * A YAML-ish indented mapping: a value is a nested `Map<String, Any>` (its own further-indented lines), a
 * `List<String>` (`key: [a, b]`), or a plain `String` scalar — just enough of YAML to read [hermesMcpServers]'s
 * block, nothing it does not shape like that (no flow mappings, anchors, multi-line scalars, …).
 */
internal fun parseIndentedMap(lines: List<String>): Map<String, Any> {
    val result = linkedMapOf<String, Any>()
    if (lines.isEmpty()) return result
    val baseIndent = indentOf(lines.first())
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (indentOf(line) != baseIndent) {
            index++
            continue
        }
        val match = FIELD.matchEntire(line.substring(baseIndent))
        if (match == null) {
            index++
            continue
        }
        val (key, raw) = match.destructured
        index++
        val value = raw.trim()
        if (value.isNotEmpty()) {
            result[key] = scalarOrList(value)
        } else {
            val children = mutableListOf<String>()
            while (index < lines.size && indentOf(lines[index]) > baseIndent) {
                children += lines[index]
                index++
            }
            if (children.isNotEmpty()) result[key] = parseIndentedMap(children)
        }
    }
    return result
}

private fun scalarOrList(value: String): Any = if (value.startsWith("[") && value.endsWith("]")) {
    value.removeSurrounding("[", "]").split(",").map { unquotedYaml(it.trim()) }.filter(String::isNotEmpty)
} else {
    unquotedYaml(value)
}

private fun unquotedYaml(value: String): String =
    if (value.length >= 2 && value.first() == value.last() && value.first() in "\"'") {
        value.substring(1, value.length - 1)
    } else {
        value
    }

private val FIELD = Regex("([A-Za-z0-9_.-]+):\\s?(.*)")
