package org.unitedsoftware.alexandrite.app.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.Cli
import org.unitedsoftware.alexandrite.app.agents
import org.unitedsoftware.alexandrite.app.at
import org.unitedsoftware.alexandrite.app.objectAt
import org.unitedsoftware.alexandrite.app.readSecrets
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal const val AREA_CONFIG = "config"
internal const val AREA_SECRET = "secret"

internal const val KIND_PROVIDER = "provider"
internal const val KIND_AGENT = "agent"
internal const val KIND_MCP = "mcp"
internal const val KIND_SETTINGS = "settings"
internal const val KIND_INSTRUCTIONS = "instructions"

/** The kinds the doc's table maps that this sync understands; `profile` is store-backed and has no local file. */
internal val SYNCED_KINDS: List<String> = listOf(KIND_PROVIDER, KIND_AGENT, KIND_MCP, KIND_SETTINGS, KIND_INSTRUCTIONS)

/** [data] unwrapped to its secret value when it is the `{"value": ...}` shape the service reads and writes. */
internal fun unwrapSecretValue(data: JsonElement): JsonElement = (data as? JsonObject)?.get("value") ?: data

/** One local item the engine compares, keyed by [key], with no [data] for a known-absent item. */
internal class LocalItem(val area: String, val kind: String, val id: String, val data: JsonElement?) {
    val key: String get() = syncKey(area, kind, id)
}

/** The config path `kind`/`id` maps to (`docs/API.md` "同步" § "配置文档"), null for `instructions` and `profile`. */
internal fun configPath(kind: String, id: String): String? = when (kind) {
    KIND_PROVIDER -> id.split("/", limit = 2).takeIf { it.size == 2 }?.let { (plugin, name) ->
        "providers.$plugin.endpoints.$name"
    }

    KIND_AGENT -> "agent.agents.$id"

    KIND_MCP -> "mcp.servers.$id"

    KIND_SETTINGS -> if (id == "app") "app" else "plugins.$id"

    else -> null
}

/** The file beside the config file that an `instructions` [id] names (`AGENTS.md`, `agents/<id>/PERSONA.md`). */
internal fun instructionsFile(cli: Cli, id: String): Path? {
    if (id.contains("..") || id.startsWith("/")) return null
    val directory = cli.locations.configFile.toAbsolutePath().parent
    return if (id == "AGENTS.md" || (id.startsWith("agents/") && id.endsWith("/PERSONA.md"))) {
        directory.resolve(id)
    } else {
        null
    }
}

/** This file's text as the `instructions` kind's one-field document, null when the file does not exist. */
internal fun readInstructionsFile(path: Path): JsonObject? = try {
    buildJsonObject { put("text", Files.readString(path)) }
} catch (e: IOException) {
    null
}

/** Every local item this sync tracks: configured providers, agents, mcp servers, the `app` settings, secrets and
 * the instructions files that exist. `profile` and skills are not covered yet. */
internal fun collectLocal(cli: Cli): List<LocalItem> {
    val config = cli.rawConfig() ?: return emptyList()
    val items = mutableListOf<LocalItem>()
    for ((plugin, endpoints) in config.childObject("providers")) {
        val map = (endpoints as? JsonObject)?.childObject("endpoints") ?: continue
        for ((name, data) in map) items += LocalItem(AREA_CONFIG, KIND_PROVIDER, "$plugin/$name", data)
    }
    for ((id, data) in config.childObject("agent").childObject("agents")) {
        items += LocalItem(AREA_CONFIG, KIND_AGENT, id, data)
    }
    for ((name, data) in config.childObject("mcp").childObject("servers")) {
        items += LocalItem(AREA_CONFIG, KIND_MCP, name, data)
    }
    config.at("app")?.let { items += LocalItem(AREA_CONFIG, KIND_SETTINGS, "app", it) }
    for ((pluginId, data) in config.childObject("plugins")) {
        items += LocalItem(AREA_CONFIG, KIND_SETTINGS, pluginId, data)
    }
    instructionsFile(cli, "AGENTS.md")?.let { file ->
        readInstructionsFile(file)?.let { items += LocalItem(AREA_CONFIG, KIND_INSTRUCTIONS, "AGENTS.md", it) }
    }
    for (agent in agents(config)) {
        val id = "agents/${agent.id}/PERSONA.md"
        instructionsFile(cli, id)?.let { file ->
            readInstructionsFile(file)?.let { items += LocalItem(AREA_CONFIG, KIND_INSTRUCTIONS, id, it) }
        }
    }
    try {
        for ((name, value) in readSecrets(cli.secrets)) items += LocalItem(AREA_SECRET, "", name, JsonPrimitive(value))
    } catch (e: IOException) {
        // The secrets file is unreadable; secrets are left out of this run rather than failing it outright.
    }
    return items
}

private fun JsonObject?.childObject(key: String): JsonObject = (this?.get(key) as? JsonObject) ?: JsonObject(emptyMap())
