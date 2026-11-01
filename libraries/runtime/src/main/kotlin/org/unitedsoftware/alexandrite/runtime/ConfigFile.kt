package org.unitedsoftware.alexandrite.runtime

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.internal.io.readUtf8
import org.unitedsoftware.alexandrite.runtime.config.BYTE_ORDER_MARK
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import org.unitedsoftware.alexandrite.runtime.config.jsonStringEnd
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Reads JSON config files whose string values may insert environment variables as `${env:NAME}`. */
public object ConfigFile {

    /**
     * The config in [file], with each `${env:NAME}` replaced from [environment] and each `$${` by `${`, which a
     * [RuntimeSpec] writes changes back to.
     */
    public fun read(file: Path, environment: Map<String, String> = System.getenv()): ConfigSource {
        val text = try {
            readUtf8(file)
        } catch (e: NoSuchFileException) {
            throw ConfigFileException.Missing(file)
        } catch (e: IOException) {
            throw ConfigFileException.Invalid(file, "cannot be read: $e", e)
        }
        val environment = environment.toMap()
        return FileConfigSource(file, environment, JsonConfigSource(resolved(file, parsed(file, text), environment)))
    }

    /** The object that [text], the content of [file], holds, with its `${env:NAME}` references as written. */
    internal fun parsed(file: Path, text: String): JsonObject {
        val json = text.removePrefix(BYTE_ORDER_MARK)
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            val detail = e.message?.lineSequence()?.first() ?: e.toString()
            throw ConfigFileException.Invalid(file, "is not valid JSON: $detail", e)
        }
        if (root !is JsonObject) throw ConfigFileException.Invalid(file, "holds no JSON object.", null)
        repeatedKey(json)?.let { throw ConfigFileException.DuplicateKey(file, it) }
        return root
    }

    /** [root] with each `${env:NAME}` replaced from [environment] and each `$${` by `${`. */
    internal fun resolved(file: Path, root: JsonObject, environment: Map<String, String>): JsonObject =
        resolved(root, environment) { path, variable ->
            if (variable == null) {
                ConfigFileException.MalformedReference(file, path)
            } else {
                ConfigFileException.UnsetVariable(file, path, variable)
            }
        }

    /** [root] resolved as [read] resolves a file's, with a [ConfigException] naming [origin] for a value it cannot. */
    @InternalAlexandriteApi
    public fun resolve(root: JsonObject, environment: Map<String, String>, origin: String): JsonObject =
        resolved(root, environment.toMap()) { path, variable ->
            val problem = if (variable == null) {
                "holds a malformed reference. ${EnvReferences.HOW_TO_WRITE}"
            } else {
                "refers to the environment variable $variable, which is not set."
            }
            ConfigException(path, "the value from $origin $problem")
        }

    /** The JSON path of the first key that the valid JSON [text] repeats within one object, null when none. */
    private fun repeatedKey(text: String): String? {
        class Level(val path: String, val isObject: Boolean) {
            val keys = HashSet<String>()
            var expectsKey = isObject
            var key = ""
            var item = 0

            fun child(): String = if (isObject) ConfigPath.join(path, key) else "$path[$item]"
        }
        val levels = ArrayDeque<Level>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> levels.addLast(Level(levels.lastOrNull()?.child().orEmpty(), text[index] == '{'))

                '}', ']' -> levels.removeLast()

                ',' -> {
                    val level = levels.last()
                    if (level.isObject) level.expectsKey = true else level.item++
                }

                '"' -> {
                    val end = jsonStringEnd(text, index)
                    val level = levels.lastOrNull()
                    if (level != null && level.expectsKey) {
                        val key = Json.decodeFromString<String>(text.substring(index, end))
                        if (!level.keys.add(key)) return ConfigPath.join(level.path, key)
                        level.key = key
                        level.expectsKey = false
                    }
                    index = end
                    continue
                }
            }
            index++
        }
        return null
    }

    /**
     * [root] with its references replaced, where [problem] makes the error for the unset variable at a path, or for a
     * malformed reference there when the variable is null.
     */
    private fun resolved(
        root: JsonObject,
        environment: Map<String, String>,
        problem: (path: String, variable: String?) -> RuntimeException,
    ): JsonObject =
        JsonObject(root.mapValues { (key, value) -> value.resolved(ConfigPath.join("", key), environment, problem) })

    private fun JsonElement.resolved(
        path: String,
        environment: Map<String, String>,
        problem: (String, String?) -> RuntimeException,
    ): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            mapValues { (key, value) -> value.resolved(ConfigPath.join(path, key), environment, problem) },
        )

        is JsonArray -> JsonArray(mapIndexed { index, value -> value.resolved("$path[$index]", environment, problem) })

        is JsonPrimitive -> if (isString) JsonPrimitive(content.resolved(path, environment, problem)) else this
    }

    private fun String.resolved(
        path: String,
        environment: Map<String, String>,
        problem: (String, String?) -> RuntimeException,
    ): String {
        if ('$' !in this) return this
        val resolved = StringBuilder()
        var index = 0
        while (index < length) {
            when {
                startsWith("$\${", index) -> {
                    resolved.append("\${")
                    index += 3
                }

                startsWith("\${", index) -> {
                    val end = indexOf('}', index)
                    val reference = if (end < 0) "" else substring(index + 2, end)
                    val name = reference.removePrefix("env:")
                    if (name == reference || !EnvReferences.NAME.matches(name)) throw problem(path, null)
                    resolved.append(environment[name] ?: throw problem(path, name))
                    index = end + 1
                }

                else -> resolved.append(this[index++])
            }
        }
        return resolved.toString()
    }
}

/** The config that [ConfigFile.read] read from [file] with [environment]. */
internal class FileConfigSource(val file: Path, val environment: Map<String, String>, source: ConfigSource) :
    ConfigSource by source
