package org.unitedsoftware.alexandrite.account.pleochroic

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.skill.SkillSpec
import java.nio.file.Files
import java.nio.file.Path

/*
 * Names and paths a sync run takes from the wire (the account's server, which the threat model treats as possibly
 * compromised or impersonated): every one is checked here before it is used to build a local path or, for a config
 * id, before it is folded into a ConfigPath. A pull must never be able to reach outside its own directory, however
 * the account answers.
 */

/** `agent:` the prefix [isValidSkillScope] and [agentSkillScope] share. */
public const val AGENT_SCOPE_PREFIX: String = "agent:"

/** An agent id, as [PluginIds.PATTERN] already requires `agent create` to use locally. */
@OptIn(InternalAlexandriteApi::class)
public fun isValidAgentId(id: String): Boolean = id.length <= MAX_NAME_LENGTH && PluginIds.PATTERN.matches(id)

/** A skill name, as the Agent Skills format and `docs/API.md` "Skill" § "校验" both require. */
public fun isValidSkillName(name: String): Boolean = SkillSpec.NAME.matches(name)

/** The files a skill may hold at once, and their decoded bytes together, `docs/API.md` "Skill" § "校验"'s caps. */
public const val MAX_SKILL_FILES: Int = 200
public const val MAX_SKILL_TOTAL_BYTES: Long = 4L * 1024 * 1024

/** A secret's name, `^[A-Z][A-Z0-9_]{0,63}$` (`docs/API.md` "Secret"). */
private val SECRET_NAME = Regex("[A-Z][A-Z0-9_]{0,63}")

public fun isValidSecretName(name: String): Boolean = SECRET_NAME.matches(name)

/**
 * Whether [value] is safe to write as one `NAME=value` line of the secrets file: the format has no escaping, so a
 * newline or NUL in a pulled value would either break the line or splice a second, attacker-chosen one in.
 */
public fun isSafeSecretValue(value: String): Boolean = value.none { it == '\n' || it == '\r' || it == '\u0000' }

/** A skill or memory scope: `global`, or `agent:<id>` of a valid [isValidAgentId]. Project scopes are never synced. */
public fun isValidSkillScope(scope: String): Boolean = when {
    scope == SKILL_SCOPE_GLOBAL -> true
    scope.startsWith(AGENT_SCOPE_PREFIX) -> isValidAgentId(scope.removePrefix(AGENT_SCOPE_PREFIX))
    else -> false
}

/** The `instructions` id this version knows: `AGENTS.md`, or `agents/<id>/PERSONA.md` of a valid [isValidAgentId]. */
public fun isValidInstructionsId(id: String): Boolean = when {
    id == "AGENTS.md" -> true

    id.startsWith("agents/") && id.endsWith("/PERSONA.md") ->
        isValidAgentId(id.removePrefix("agents/").removeSuffix("/PERSONA.md"))

    else -> false
}

/**
 * A config id this sync folds into a [org.unitedsoftware.alexandrite.sdk.config.ConfigPath] segment (a provider's
 * `<plugin>/<name>`, an mcp server's name, a settings id, or one half of either): never a structural risk, since
 * [org.unitedsoftware.alexandrite.sdk.config.ConfigPath.join] quotes any special character rather than acting on
 * it, but bounded and printable all the same, in depth, against a malformed or oversized id.
 */
public fun isSafeConfigId(id: String): Boolean =
    id.isNotEmpty() && id.length <= MAX_NAME_LENGTH && id.none { it.isISOControl() }

/**
 * Whether [relative] is safe to resolve under a directory: relative, forward-slash-separated, holds no empty, `.`
 * or `..` segment, no backslash (a Windows separator this host never treats as one, so a value holding one is
 * refused rather than read as a single odd file name), no NUL or other control character, and no drive letter or
 * leading slash.
 */
public fun isSafeRelativePath(relative: String): Boolean {
    if (relative.isEmpty() || relative.length > MAX_PATH_LENGTH) return false
    if (relative.any { it.isISOControl() }) return false
    if (relative.startsWith('/') || relative.startsWith('\\')) return false
    if (relative.contains('\\')) return false
    if (relative.length >= 2 && relative[1] == ':') return false
    return relative.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/**
 * [root] followed by the single, simple name [child] (a skill's or an instruction id's one segment, never a path
 * of its own), refused (null) unless [child] has no separator of its own and the result, once normalized, is
 * exactly one level under [root] and names no symlink: a pull decides what to write by name alone, never by
 * following a link this host did not create.
 */
public fun safeChild(root: Path, child: String): Path? {
    if (!isSafeRelativePath(child) || '/' in child) return null
    val normalizedRoot = root.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(child).normalize()
    if (resolved.parent != normalizedRoot) return null
    if (Files.isSymbolicLink(resolved)) return null
    return resolved
}

/**
 * [root] followed by the relative path [relative] (a skill file's own path, which may nest in sub-directories),
 * refused (null) unless [relative] is [isSafeRelativePath] and the normalized result stays under [root].
 */
public fun safeDescendant(root: Path, relative: String): Path? {
    if (!isSafeRelativePath(relative)) return null
    val normalizedRoot = root.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(relative).normalize()
    if (!resolved.startsWith(normalizedRoot) || resolved == normalizedRoot) return null
    return resolved
}

/** Whether every directory from [root] down to [path] (both included) is free of a symlink. */
public fun isFreeOfSymlinks(root: Path, path: Path): Boolean {
    val stop = root.toAbsolutePath().normalize().parent
    var current: Path? = path.toAbsolutePath().normalize()
    while (current != null && current != stop) {
        if (Files.isSymbolicLink(current)) return false
        current = current.parent
    }
    return true
}

private const val MAX_NAME_LENGTH = 200
private const val MAX_PATH_LENGTH = 4096
