package org.unitedsoftware.alexandrite.discovery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val CODEX_SKILLS_DIR = "skills"
private const val CODEX_CONFIG_FILE = "config.toml"

/**
 * Codex: `config.toml`'s `mcp_servers.<name>` tables, and the skills under `skills/` (Codex's own bundled ones,
 * under `skills/.system/`, are skipped — [findSkillDirectories] never descends into a `.`-named directory).
 * Null when `~/.codex` does not exist at all.
 */
public fun discoverCodex(home: Path): DiscoveredSource? {
    val root = home.resolve(".codex")
    if (!Files.isDirectory(root)) return null
    val servers = try {
        codexMcpServers(Files.readString(root.resolve(CODEX_CONFIG_FILE)))
    } catch (e: IOException) {
        emptyList()
    }
    val skills = findSkillDirectories(root.resolve(CODEX_SKILLS_DIR)).mapNotNull(::readSkillFrontMatter)
    return DiscoveredSource("codex", "Codex", skills, servers)
}

/**
 * The `mcp_servers.<name>` and `mcp_servers.<name>.env` tables of a Codex `config.toml`, read as a minimal
 * subset of TOML: table headers, and inside them `key = "string"`, `key = ["a", "b"]` and `key = true/false` lines.
 * Every other table, such as `desktop`, `plugins` or `projects`, is ignored, and so are a server's other settings
 * (`enabled`, `env_vars`, `startup_timeout_sec`, …), none of them an Alexandrite setting.
 */
internal fun codexMcpServers(text: String): List<DiscoveredMcpServer> {
    val tables = linkedMapOf<String, MutableList<String>>()
    var current: String? = null
    for (raw in text.lines()) {
        val line = stripTomlComment(raw).trim()
        if (line.isEmpty()) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            current = line.removeSurrounding("[", "]").trim()
            tables.getOrPut(current) { mutableListOf() }
        } else {
            current?.let { tables.getValue(it) += line }
        }
    }
    val servers = linkedMapOf<String, MutableMap<String, Any>>()
    for ((header, lines) in tables) {
        if (!header.startsWith("mcp_servers.")) continue
        val rest = header.removePrefix("mcp_servers.")
        val isEnv = rest.endsWith(".env")
        val name = tomlUnquote(if (isEnv) rest.removeSuffix(".env") else rest)
        if (name.isEmpty()) continue
        val fields = servers.getOrPut(name) { linkedMapOf() }
        if (isEnv) {
            val env = linkedMapOf<String, String>()
            lines.forEach { line -> tomlKeyValue(line)?.let { (k, v) -> tomlString(v)?.let { env[k] = it } } }
            fields["env"] = env
        } else {
            lines.forEach { line -> tomlKeyValue(line)?.let { (k, v) -> fields[k] = tomlValue(v) } }
        }
    }
    return servers.map { (name, fields) ->
        DiscoveredMcpServer(
            name = name,
            command = fields["command"] as? String,
            args = (fields["args"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            env = (fields["env"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
                .orEmpty(),
            cwd = fields["cwd"] as? String,
            url = fields["url"] as? String,
        )
    }
}

private fun stripTomlComment(line: String): String {
    var quote = false
    for (i in line.indices) {
        val c = line[i]
        if (c == '"') quote = !quote
        if (c == '#' && !quote) return line.substring(0, i)
    }
    return line
}

private fun tomlKeyValue(line: String): Pair<String, String>? {
    val equals = line.indexOf('=')
    if (equals < 0) return null
    return tomlUnquote(line.substring(0, equals).trim()) to line.substring(equals + 1).trim()
}

private fun tomlUnquote(key: String): String = key.removeSurrounding("\"").removeSurrounding("'")

private fun tomlValue(raw: String): Any = if (raw.startsWith("[") && raw.endsWith("]")) {
    raw.removeSurrounding("[", "]").split(",").map(String::trim).filter(String::isNotEmpty).mapNotNull(::tomlString)
} else {
    tomlString(raw) ?: raw.toBooleanStrictOrNull() ?: raw
}

private fun tomlString(raw: String): String? =
    if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') raw.substring(1, raw.length - 1) else null
