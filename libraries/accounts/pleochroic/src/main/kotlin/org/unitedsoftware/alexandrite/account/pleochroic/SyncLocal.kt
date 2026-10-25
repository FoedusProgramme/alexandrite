package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

public const val KIND_PROVIDER: String = "provider"
public const val KIND_AGENT: String = "agent"
public const val KIND_MCP: String = "mcp"
public const val KIND_SETTINGS: String = "settings"
public const val KIND_INSTRUCTIONS: String = "instructions"

/** `scope` for a skill shared by every agent (`docs/API.md` "Skill"). */
public const val SKILL_SCOPE_GLOBAL: String = "global"

/** `scope` for the skills of one agent. */
public fun agentSkillScope(agentId: String): String = "agent:$agentId"

/** [data] unwrapped to its secret value when it is the `{"value": ...}` shape the service reads and writes. */
public fun unwrapSecretValue(data: JsonElement): JsonElement = (data as? JsonObject)?.get("value") ?: data

/** One local item the engine compares, keyed by [key], with no [data] for a known-absent item. */
public class LocalItem(
    public val area: String,
    public val kind: String,
    public val id: String,
    public val data: JsonElement?,
) {
    public val key: String get() = syncKey(area, kind, id)
}

/** One write a sync run decided for [area]/[kind]/[id]; [data] null removes it. */
public class LocalChange(
    public val area: String,
    public val kind: String,
    public val id: String,
    public val data: JsonElement?,
)

/**
 * What a sync run reads and writes on the local side: config documents, secrets, instructions files and skills.
 * [HostConfigSyncLocal] is the implementation a host builds from its own [HostConfig], config file and secrets file.
 */
public interface SyncLocal {
    /** Every local item this sync tracks, read fresh for one run. */
    public suspend fun collect(): List<LocalItem>

    /** Writes every decided [changes] in as few local operations as the kind allows. */
    public suspend fun apply(changes: List<LocalChange>)
}

/** The config path `kind`/`id` maps to (`docs/API.md` "同步" § "配置文档"), null for `instructions` and `profile`. */
public fun configPath(kind: String, id: String): String? = when (kind) {
    KIND_PROVIDER -> id.split("/", limit = 2).takeIf { it.size == 2 }?.let { (plugin, name) ->
        path("providers", plugin, "endpoints", name)
    }

    KIND_AGENT -> path("agent", "agents", id)

    KIND_MCP -> path("mcp", "servers", id)

    KIND_SETTINGS -> if (id == "app") "app" else path("plugins", id)

    else -> null
}

private fun path(vararg keys: String): String = keys.fold("", ConfigPath::join)

/**
 * The real [SyncLocal]: Alexandrite's own config/secret handling ([hostConfig], never a value written in place of
 * a secret) and the files beside [configFile] (instructions, skills).
 */
public class HostConfigSyncLocal(
    private val hostConfig: HostConfig,
    private val configFile: Path,
    private val secretsFile: Path,
) : SyncLocal {
    override suspend fun collect(): List<LocalItem> {
        val items = mutableListOf<LocalItem>()
        val providers = hostConfig.get("providers") as? JsonObject
        for ((plugin, section) in providers.orEmpty()) {
            val endpoints = (section as? JsonObject)?.get("endpoints") as? JsonObject ?: continue
            for ((name, data) in endpoints) items += LocalItem(AREA_CONFIG, KIND_PROVIDER, "$plugin/$name", data)
        }
        val agents = (hostConfig.get("agent.agents") as? JsonObject).orEmpty()
        for ((id, data) in agents) items += LocalItem(AREA_CONFIG, KIND_AGENT, id, data)
        val mcpServers = (hostConfig.get("mcp.servers") as? JsonObject).orEmpty()
        for ((name, data) in mcpServers) items += LocalItem(AREA_CONFIG, KIND_MCP, name, data)
        hostConfig.get("app")?.let { items += LocalItem(AREA_CONFIG, KIND_SETTINGS, "app", it) }
        val plugins = (hostConfig.get("plugins") as? JsonObject).orEmpty()
        for ((id, data) in plugins) items += LocalItem(AREA_CONFIG, KIND_SETTINGS, id, data)

        instructionsFile("AGENTS.md")?.let { file ->
            readInstructionsFile(file)?.let { items += LocalItem(AREA_CONFIG, KIND_INSTRUCTIONS, "AGENTS.md", it) }
        }
        for (id in agents.keys) {
            val name = "agents/$id/PERSONA.md"
            instructionsFile(name)?.let { file ->
                readInstructionsFile(file)?.let { items += LocalItem(AREA_CONFIG, KIND_INSTRUCTIONS, name, it) }
            }
        }

        try {
            for ((name, value) in readSecrets(secretsFile)) {
                items +=
                    LocalItem(AREA_SECRET, "", name, JsonPrimitive(value))
            }
        } catch (e: IOException) {
            // The secrets file is unreadable; secrets are left out of this run rather than failing it outright.
        }

        items += collectSkills(SKILL_SCOPE_GLOBAL, globalSkillsRoot())
        for (id in agents.keys) items += collectSkills(agentSkillScope(id), agentSkillsRoot(id))
        return items
    }

    override suspend fun apply(changes: List<LocalChange>) {
        val configChanges = mutableListOf<LocalChange>()
        for (change in changes) {
            when {
                change.area == AREA_SECRET -> applySecret(change)
                change.area == AREA_SKILL -> applySkill(change)
                change.kind == KIND_INSTRUCTIONS -> applyInstructions(change)
                else -> configChanges += change
            }
        }
        if (configChanges.isNotEmpty()) {
            hostConfig.edit {
                for (change in configChanges) {
                    val path = configPath(change.kind, change.id) ?: continue
                    if (change.data == null) remove(path) else set(path, change.data)
                }
            }
        }
    }

    private fun applySecret(change: LocalChange) {
        val value = change.data?.let { (unwrapSecretValue(it) as? JsonPrimitive)?.content }
        val secrets = try {
            readSecrets(secretsFile).toMutableMap()
        } catch (e: IOException) {
            return
        }
        if (value == null) secrets.remove(change.id) else secrets[change.id] = value
        writeSecretsFile(secretsFile, secrets)
    }

    private fun applyInstructions(change: LocalChange) {
        val file = instructionsFile(change.id) ?: return
        val text = (change.data as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.content }
        writeTextFile(file, text)
    }

    private fun applySkill(change: LocalChange) {
        val root = skillsRootOf(change.kind) ?: return
        val directory = root.resolve(change.id)
        if (change.data == null) {
            deleteRecursively(directory)
            return
        }
        val files = (change.data as? JsonObject)?.get("files") as? JsonArray ?: return
        deleteRecursively(directory)
        for (entry in files) {
            val file = entry as? JsonObject ?: continue
            val relative = (file["path"] as? JsonPrimitive)?.content ?: continue
            if (relative.contains("..") || relative.startsWith("/")) continue
            val encoding = (file["encoding"] as? JsonPrimitive)?.content ?: "utf8"
            val content = (file["content"] as? JsonPrimitive)?.content ?: continue
            val bytes = if (encoding ==
                "base64"
            ) {
                Base64.getDecoder().decode(content)
            } else {
                content.toByteArray(Charsets.UTF_8)
            }
            val target = directory.resolve(relative)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
    }

    private fun skillsRootOf(scope: String): Path? = when {
        scope == SKILL_SCOPE_GLOBAL -> globalSkillsRoot()
        scope.startsWith("agent:") -> agentSkillsRoot(scope.removePrefix("agent:"))
        else -> null
    }

    private fun globalSkillsRoot(): Path = configDirectory().resolve("skills")

    private fun agentSkillsRoot(agentId: String): Path = configDirectory().resolve("agents/$agentId/skills")

    private fun configDirectory(): Path = configFile.toAbsolutePath().parent

    private fun instructionsFile(name: String): Path? {
        if (name.contains("..") || name.startsWith("/")) return null
        return if (name == "AGENTS.md" || (name.startsWith("agents/") && name.endsWith("/PERSONA.md"))) {
            configDirectory().resolve(name)
        } else {
            null
        }
    }
}

/** This file's text as the `instructions` kind's one-field document, null when the file does not exist. */
private fun readInstructionsFile(path: Path): JsonObject? = try {
    buildJsonObject { put("text", Files.readString(path)) }
} catch (e: IOException) {
    null
}

private fun writeTextFile(path: Path, text: String?) {
    try {
        if (text == null) {
            Files.deleteIfExists(path)
        } else {
            Files.createDirectories(path.parent)
            Files.write(path, text.toByteArray(Charsets.UTF_8))
        }
    } catch (e: IOException) {
        // Best effort: a sync run reports what it pushed and pulled, not every file-system failure.
    }
}

/** The skills under [root] as [LocalItem]s of [scope], each `{"files": [...]}}` with every file in its directory. */
private fun collectSkills(scope: String, root: Path): List<LocalItem> {
    if (!Files.isDirectory(root)) return emptyList()
    val items = mutableListOf<LocalItem>()
    Files.list(root).use { entries ->
        for (directory in entries) {
            if (!Files.isDirectory(directory)) continue
            val files = buildJsonArray {
                Files.walk(directory).use { walked ->
                    for (file in walked) {
                        if (!Files.isRegularFile(file)) continue
                        add(fileEntry(directory, file))
                    }
                }
            }
            items +=
                LocalItem(AREA_SKILL, scope, directory.fileName.toString(), buildJsonObject { put("files", files) })
        }
    }
    return items
}

private fun fileEntry(root: Path, file: Path): JsonObject {
    val relative = root.relativize(file).toString().replace('\\', '/')
    val bytes = Files.readAllBytes(file)
    val text =
        runCatching {
            String(bytes, Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
        }
    return buildJsonObject {
        put("path", relative)
        if (text.isSuccess) {
            put("encoding", "utf8")
            put("content", text.getOrThrow())
        } else {
            put("encoding", "base64")
            put("content", Base64.getEncoder().encodeToString(bytes))
        }
    }
}

private fun deleteRecursively(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { walked ->
        walked.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
