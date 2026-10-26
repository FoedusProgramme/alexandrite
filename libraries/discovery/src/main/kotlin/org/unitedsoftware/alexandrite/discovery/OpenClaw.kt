package org.unitedsoftware.alexandrite.discovery

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

private const val OPENCLAW_CONFIG_FILE = "openclaw.json"
private const val OPENCLAW_SKILLS_DIR = "skills"

/**
 * OpenClaw: `~/.openclaw/openclaw.json`'s MCP servers, at `mcp.servers.<name>` (confirmed against the project's own
 * `docs/gateway/config-extensions.md` at `github.com/openclaw/openclaw`; an earlier reading of this function also
 * checked a top-level `mcpServers.<name>` a secondary source had named, which that canonical doc does not — this
 * version reads only the nested table). A server has no `env` field there (only `headers`, for a remote one), so
 * [DiscoveredMcpServer.env] is always empty for this source; a header value written `${VAR}` (OpenClaw's own
 * substitution syntax, confirmed by the same doc, unlike Alexandrite's `${env:VAR}`) is resolved against this
 * process's own environment where that variable is set, left as is otherwise, so a caller sees the same value
 * OpenClaw itself would use.
 *
 * The skills side was not confirmed the same way: the doc names `skills.load.extraDirs` (read below) but no
 * default skills root beside it, so `skills/` here is this function's own guess, not something OpenClaw documents.
 * No real OpenClaw installation was reachable to check either side against, only its published documentation —
 * treat this source as best effort.
 *
 * Null when `~/.openclaw` does not exist at all; present with empty lists when its config could not be read.
 */
public fun discoverOpenClaw(home: Path): DiscoveredSource? {
    val root = home.resolve(".openclaw")
    if (!Files.isDirectory(root)) return null
    val json = readJsonObject(root.resolve(OPENCLAW_CONFIG_FILE))
    val nested = json?.obj("mcp")?.get("servers")
    val servers = mcpServersOf(nested).map(::resolveShellStyleReferences)
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
