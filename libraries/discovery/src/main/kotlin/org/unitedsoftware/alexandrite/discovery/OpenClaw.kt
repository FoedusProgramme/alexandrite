package org.unitedsoftware.alexandrite.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

private const val OPENCLAW_CONFIG_FILE = "openclaw.json"
private const val OPENCLAW_SKILLS_DIR = "skills"

/**
 * OpenClaw: `~/.openclaw/openclaw.json`'s MCP servers (its documentation names both a nested `mcp.servers.<name>`
 * table and a top-level `mcpServers.<name>` one across different versions; both are read, the nested one first),
 * and the skills under `skills/` plus any directory `skills.load.extraDirs` names. A header value written
 * `${VAR}` (OpenClaw's own substitution syntax, unlike Alexandrite's `${env:VAR}`) is resolved against this
 * process's own environment where that variable is set, left as is otherwise, so a caller sees the same value
 * OpenClaw itself would use.
 *
 * Unlike the other three sources, this reading was not checked against a real OpenClaw installation (none was
 * reachable while writing it) — only against its own published documentation. Treat it as best effort.
 *
 * Null when `~/.openclaw` does not exist at all; present with empty lists when its config could not be read.
 */
public fun discoverOpenClaw(home: Path): DiscoveredSource? {
    val root = home.resolve(".openclaw")
    if (!Files.isDirectory(root)) return null
    val json = readJsonObject(root.resolve(OPENCLAW_CONFIG_FILE))
    val nested = (json?.get("mcp") as? JsonObject)?.get("servers")
    val servers = (mcpServersOf(nested) + mcpServersOf(json?.get("mcpServers")))
        .distinctBy { it.name }
        .map(::resolveShellStyleReferences)
    val extraDirs = json?.obj("skills")?.obj("load")?.array("extraDirs")
        ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    val roots = listOf(root.resolve(OPENCLAW_SKILLS_DIR)) + extraDirs.map(Path::of)
    val skills = roots.flatMap(::findSkillDirectories).distinctBy { it.toString() }.mapNotNull(::readSkillFrontMatter)
    return DiscoveredSource("openclaw", "OpenClaw", skills, servers)
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private val SHELL_STYLE_VARIABLE = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

private fun resolveShellStyleReferences(server: DiscoveredMcpServer): DiscoveredMcpServer = DiscoveredMcpServer(
    name = server.name,
    command = server.command,
    args = server.args,
    env = server.env.mapValues(::resolveShellStyleValue),
    cwd = server.cwd,
    url = server.url,
    headers = server.headers.mapValues(::resolveShellStyleValue),
)

private fun resolveShellStyleValue(entry: Map.Entry<String, String>): String {
    val match = SHELL_STYLE_VARIABLE.matchEntire(entry.value) ?: return entry.value
    return System.getenv(match.groupValues[1]) ?: entry.value
}
