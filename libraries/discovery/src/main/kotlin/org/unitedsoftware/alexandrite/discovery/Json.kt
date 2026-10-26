package org.unitedsoftware.alexandrite.discovery

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** [file] parsed as a JSON object, null when it does not exist, is no regular file, is no valid JSON or is not
 * an object at its root. */
internal fun readJsonObject(file: Path): JsonObject? = try {
    if (!Files.isRegularFile(file)) null else Json.parseToJsonElement(Files.readString(file)) as? JsonObject
} catch (e: IOException) {
    null
} catch (e: SerializationException) {
    null
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

internal fun JsonObject.stringMap(name: String): Map<String, String> =
    (this[name] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.content?.let { key to it }
    }?.toMap().orEmpty()

/** The MCP servers of a `{"<name>": {...}}` map [element] (a `mcpServers` field, or a `.mcp.json`'s `mcpServers`),
 * one [DiscoveredMcpServer] per entry whose value is itself an object. */
internal fun mcpServersOf(element: JsonElement?): List<DiscoveredMcpServer> =
    (element as? JsonObject)?.entries.orEmpty().mapNotNull { (name, value) ->
        val server = value as? JsonObject ?: return@mapNotNull null
        DiscoveredMcpServer(
            name = name,
            command = server.string("command"),
            args = server.stringList("args"),
            env = server.stringMap("env"),
            cwd = server.string("cwd"),
            url = server.string("url"),
            headers = server.stringMap("headers"),
        )
    }
