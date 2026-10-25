package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

private val URL_LIKE = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/** Whether [text] is a URL, or a secret reference in either of [SecretRefs]'s spellings. */
private fun looksSensitive(text: String): Boolean =
    URL_LIKE.containsMatchIn(text) || text.startsWith("\${env:") || text.startsWith("\${secret:")

/**
 * Whether [after] (a `settings` document, `app.*` or a plugin's own section) introduces a URL or secret reference
 * that [before] did not already hold anywhere in it: `tools.web.pleochroicApiBase` / `pleochroicApiKey` are two
 * examples among the settings a plugin may define, so this reads every string leaf rather than naming fields.
 */
public fun hasNewSensitiveSettingsValue(before: JsonElement?, after: JsonElement?): Boolean {
    val added = stringLeafValues(after) - stringLeafValues(before)
    return added.any(::looksSensitive)
}

private fun stringLeafValues(element: JsonElement?): Set<String> {
    val out = mutableSetOf<String>()
    fun visit(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach(::visit)
            is JsonArray -> value.forEach(::visit)
            is JsonNull -> Unit
            is JsonPrimitive -> if (value.isString) out += value.content
        }
    }
    element?.let(::visit)
    return out
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
                KIND_SETTINGS -> if (hasNewSensitiveSettingsValue(before, after)) reasons += "settings_sensitive_value"
            }
            after?.let { data ->
                suspiciousSecretReference(data, syncedSecrets)?.let { reasons += "suspicious_secret_reference" }
            }
        }
    }
    return reasons
}
