package org.unitedsoftware.alexandrite.discovery

import java.nio.file.Files
import java.nio.file.Path

private const val CLAUDE_CODE_SKILLS_DIR = "skills"
private const val PROJECT_MCP_FILE = ".mcp.json"

/**
 * Claude Code: `~/.claude/skills`, the top-level `mcpServers` of `~/.claude.json`, the `mcpServers` of
 * `~/.claude/settings.json` (when the user sets one there directly instead), and, when [projectDir] is given and
 * holds one, that project's own `.mcp.json`. A server named in more than one of these keeps the first reading
 * (`.claude.json`, then `settings.json`, then the project file). Enabled plugins' own bundled skills and MCP
 * servers are not resolved — only what these files name directly.
 *
 * Null when `~/.claude` does not exist at all.
 */
public fun discoverClaudeCode(home: Path, projectDir: Path? = null): DiscoveredSource? {
    val root = home.resolve(".claude")
    if (!Files.isDirectory(root)) return null
    val skills = findSkillDirectories(root.resolve(CLAUDE_CODE_SKILLS_DIR)).mapNotNull(::readSkillFrontMatter)
    val servers = linkedMapOf<String, DiscoveredMcpServer>()
    fun add(file: Path, field: String) {
        readJsonObject(file)?.let { json -> mcpServersOf(json[field]).forEach { servers.putIfAbsent(it.name, it) } }
    }
    add(home.resolve(".claude.json"), "mcpServers")
    add(root.resolve("settings.json"), "mcpServers")
    projectDir?.let { add(it.resolve(PROJECT_MCP_FILE), "mcpServers") }
    return DiscoveredSource("claude-code", "Claude Code", skills, servers.values.toList())
}
