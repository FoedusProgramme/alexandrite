package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Which pulled changes a sync run stages for the owner to review instead of applying at once (the owner's
 * decision: a pull must never be able to harm the local machine on its own; harmless changes keep syncing).
 */

/**
 * The top-level fields of an agent's config (`AgentConfig`, `PermissionConfig`, `SandboxConfig`, `ToolSelection` in
 * `libraries/agent`) that decide what the agent is allowed to do or run, so a pulled change to any of them is
 * staged rather than applied: `permission` (its mode, allow/deny/trust lists), `sandbox` (its type, `allowWrite`,
 * `denyRead`, `network.allowedDomains` and the rest), `tools` (which tools the agent or a guest may call),
 * `channels` (which chat it serves and whether it is the default agent there) and `workspace` (the directory it
 * reads and writes). `instructions` (the agent's own inline persona text, distinct from the separately-tracked
 * `AGENTS.md` / `PERSONA.md` files) is included too: it is as capable of steering the agent as those files are.
 */
public val DANGEROUS_AGENT_FIELDS: Set<String> =
    setOf("permission", "sandbox", "tools", "channels", "workspace", "instructions")

/** The [DANGEROUS_AGENT_FIELDS] that differ between [before] and [after] (an agent's local and pulled documents),
 * including a field the other side does not have at all (a new agent, or one pull would delete). */
public fun dangerousAgentFields(before: JsonElement?, after: JsonElement?): List<String> {
    val beforeObject = before as? JsonObject
    val afterObject = after as? JsonObject
    if (beforeObject == null && afterObject == null) return emptyList()
    return DANGEROUS_AGENT_FIELDS.filter { field -> beforeObject?.get(field) != afterObject?.get(field) }
}

/**
 * The `app.*` fields this version knows are a harmless, display-only preference: the language texts are shown in,
 * and the time zone times are shown in (`AppConfig` in `app/AppConfig.kt`). Nothing else there is allowed through
 * on its own: `plugins` names which extra plugins the app loads (code), and `updates.install` lets it install a
 * new release of itself with nobody confirming it; `shutdownGraceSeconds` and `startTimeoutSeconds` are process
 * lifecycle timing, not a preference, so they are held back too in the absence of a clear reason to trust them.
 * A plugin's own settings (every `settings` id but `"app"`) have no fixed field set at all (`PluginIds` names the
 * config root, but each plugin's own `@ConfigSection` class defines whatever it holds), so none of them are ever
 * assumed harmless; the empty set is used for those.
 */
public val ALLOWED_APP_SETTINGS_FIELDS: Set<String> = setOf("language", "zone")

/**
 * The top-level fields [before] and [after] (two `settings` documents) disagree on, including a field only one
 * side names; null when either side holds something other than a plain object, so the caller treats every field
 * as unknown rather than trusting a document shaped unlike any `settings` document this version writes.
 */
public fun changedSettingsFields(before: JsonElement?, after: JsonElement?): Set<String>? {
    if (before != null && before !is JsonObject) return null
    if (after != null && after !is JsonObject) return null
    val keys = before?.keys.orEmpty() + after?.keys.orEmpty()
    return keys.filterTo(sortedSetOf()) { before?.get(it) != after?.get(it) }
}

/**
 * Why a local-writing outcome for [area]/[kind]/[id] (a pull, a tombstone's local delete, or a merge) is staged
 * for the owner to review rather than applied, empty when it is safe to apply at once. [before] is the item's
 * current local content (null when there is none), [after] the content the decision would write (null for a
 * delete); [syncedSecrets] names the account's own synced secrets, for [suspiciousSecretReference].
 */
public fun dangerReasons(
    area: String,
    kind: String,
    id: String,
    before: JsonElement?,
    after: JsonElement?,
    syncedSecrets: Set<String>,
): List<String> {
    val reasons = mutableListOf<String>()
    when (area) {
        AREA_SECRET -> if (before != null) reasons += "secret_overwrite"

        // A skill's files are executable content (an agent runs what a SKILL.md or a script beside it says),
        // so any pulled create, change or delete of one is staged; a local-only push stays automatic (pushing
        // is never staged at all, see SyncRunner.writesLocally).
        AREA_SKILL -> reasons += "skill"

        AREA_CONFIG -> {
            when (kind) {
                KIND_MCP -> reasons += "mcp_server"

                KIND_PROVIDER -> reasons += "provider_endpoint"

                KIND_INSTRUCTIONS -> reasons += "instructions_file"

                KIND_AGENT -> dangerousAgentFields(before, after).forEach { reasons += "agent_field:$it" }

                KIND_SETTINGS -> {
                    val allowed = if (id == "app") ALLOWED_APP_SETTINGS_FIELDS else emptySet()
                    val changed = changedSettingsFields(before, after)
                    if (changed == null) {
                        reasons += "settings_field:*"
                    } else {
                        (changed - allowed).forEach { reasons += "settings_field:$it" }
                    }
                }
            }
            after?.let { data ->
                suspiciousSecretReference(data, syncedSecrets)?.let { reasons += "suspicious_secret_reference" }
            }
        }
    }
    return reasons
}
