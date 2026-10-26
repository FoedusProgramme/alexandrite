package org.unitedsoftware.alexandrite.discovery

import java.nio.file.Path

/**
 * One MCP server [name] another tool has configured, however it configured it: a [command] to run (with [args]
 * and [env]), a [url] to reach (with [headers]), or both at once, as the tool itself allows. [cwd] is the working
 * directory a stdio server runs in, null for the tool's own default.
 *
 * Every value of [env] and [headers] is a secret: the tool that holds it never writes one to a config file as a
 * plain value either, and neither does importing it into Alexandrite ([org.unitedsoftware.alexandrite.discovery]
 * leaves the choice of where each ends up, as an environment variable name, to its caller).
 */
public class DiscoveredMcpServer(
    public val name: String,
    public val command: String? = null,
    public val args: List<String> = emptyList(),
    public val env: Map<String, String> = emptyMap(),
    public val cwd: String? = null,
    public val url: String? = null,
    public val headers: Map<String, String> = emptyMap(),
)

/**
 * One skill [name] another tool holds at [directory] (its `SKILL.md` and whatever files sit beside it, such as
 * `scripts/` or `references/`), with [description] when the file's front matter gives one.
 */
public class DiscoveredSkill(public val name: String, public val description: String?, public val directory: Path)

/**
 * What one of [discoverHermes], [discoverClaudeCode], [discoverCodex] or [discoverOpenClaw] found on this machine:
 * the tool's [id] (the same one its import choice is picked by) and display [label], its [skills] and its
 * [mcpServers] — both empty, never null, when the tool is set up but configures neither.
 */
public class DiscoveredSource(
    public val id: String,
    public val label: String,
    public val skills: List<DiscoveredSkill>,
    public val mcpServers: List<DiscoveredMcpServer>,
)

/**
 * Every source below [home] that is actually set up on this machine (each of [discoverHermes], [discoverClaudeCode]
 * with [projectDir], [discoverCodex] and [discoverOpenClaw], skipping the ones that answer null), in that order.
 * Pleochroic is not among them: its skills and MCP servers come from the signed-in account's own sync, not a local
 * file a caller would otherwise read here (see `pleochroicSignIn` and the account's own pull, in the app and the
 * `accounts/pleochroic` library respectively).
 */
public fun discoverAll(home: Path, projectDir: Path? = null): List<DiscoveredSource> = listOfNotNull(
    discoverHermes(home),
    discoverClaudeCode(home, projectDir),
    discoverCodex(home),
    discoverOpenClaw(home),
)
