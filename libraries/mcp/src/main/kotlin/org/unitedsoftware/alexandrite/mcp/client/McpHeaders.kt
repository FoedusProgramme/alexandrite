package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.unitedsoftware.alexandrite.sdk.net.HttpHeaders
import java.util.Base64

/** A tool parameter mirrored into the header `Mcp-Param-<name>`. */
internal data class ParamHeader(val name: String, val path: List<String>)

/** The HTTP headers the modern revision derives from a request's body. */
internal object McpHeaders {
    const val PROTOCOL_VERSION: String = "MCP-Protocol-Version"
    const val SESSION_ID: String = "Mcp-Session-Id"
    const val METHOD: String = "Mcp-Method"
    const val NAME: String = "Mcp-Name"
    const val PARAM_PREFIX: String = "Mcp-Param-"

    /** The methods whose `name` or `uri` the header [NAME] carries. */
    val NAMED_METHODS: Set<String> = setOf("tools/call", "resources/read", "prompts/get")

    private const val SENTINEL_START = "=?base64?"
    private const val SENTINEL_END = "?="
    private const val TCHAR_SPECIALS = "!#$%&'*+-.^_`|~"
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val PRIMITIVE_TYPES = setOf("string", "integer", "boolean")

    /** The keywords whose value is a schema, a list of schemas or a map of them, none statically reachable. */
    private val SUBSCHEMA_KEYWORDS = setOf(
        "items", "prefixItems", "additionalItems", "additionalProperties", "patternProperties", "anyOf", "oneOf",
        "allOf", "not", "if", "then", "else", "\$defs", "definitions", "contains", "propertyNames",
        "dependentSchemas", "unevaluatedItems", "unevaluatedProperties",
    )

    /** [value] as a header value: plain when it is safe, in the Base64 sentinel form otherwise. */
    fun encode(value: String): String {
        val plain = HttpHeaders.isValue(value) &&
            value.trim() == value &&
            !(value.startsWith(SENTINEL_START) && value.endsWith(SENTINEL_END))
        if (plain) return value
        return SENTINEL_START + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + SENTINEL_END
    }

    /** The parameters [inputSchema] mirrors into headers, or why its `x-mcp-header` annotations are invalid. */
    fun paramHeaders(inputSchema: JsonObject): Result<List<ParamHeader>> {
        val found = mutableListOf<ParamHeader>()
        val problem = walk(inputSchema, reachable = true, path = emptyList(), found)
        if (problem != null) return Result.failure(IllegalArgumentException(problem))
        val names = found.map { it.name.lowercase() }
        names.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            return Result.failure(IllegalArgumentException("x-mcp-header '$it' names more than one parameter."))
        }
        return Result.success(found)
    }

    /** The headers that mirror [arguments] by [params]. */
    fun paramValues(params: List<ParamHeader>, arguments: JsonObject): Map<String, String> = buildMap {
        for (param in params) {
            var value: JsonElement? = arguments
            for (key in param.path) value = (value as? JsonObject)?.get(key)
            val text = headerText(value) ?: continue
            put(PARAM_PREFIX + param.name, encode(text))
        }
    }

    private fun headerText(value: JsonElement?): String? {
        if (value == null || value is JsonNull || value !is JsonPrimitive) return null
        if (value.isString) return value.content
        value.booleanOrNull?.let { return it.toString() }
        value.longOrNull?.let { return it.toString() }
        val number = value.doubleOrNull ?: return null
        if (number % 1.0 == 0.0 && kotlin.math.abs(number) <= MAX_SAFE_INTEGER) return number.toLong().toString()
        return null
    }

    /** Null when the annotations under [schema] are valid. */
    private fun walk(
        schema: JsonElement,
        reachable: Boolean,
        path: List<String>,
        found: MutableList<ParamHeader>,
    ): String? {
        if (schema !is JsonObject) return null
        schema["x-mcp-header"]?.let { annotation ->
            val where = path.joinToString(".").ifEmpty { "the schema root" }
            if (!reachable || path.isEmpty()) return "x-mcp-header at $where is not statically reachable."
            val name = (annotation as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name.isNullOrEmpty() || !name.all(::isTchar)) return "x-mcp-header at $where is no header name."
            val type = (schema["type"] as? JsonPrimitive)?.content
            if (type !in PRIMITIVE_TYPES) return "x-mcp-header at $where is on a parameter of type $type."
            found += ParamHeader(name, path)
        }
        for ((keyword, value) in schema) {
            val problem = when (keyword) {
                "properties" -> (value as? JsonObject)?.entries?.firstNotNullOfOrNull { (name, sub) ->
                    walk(sub, reachable, path + name, found)
                }

                in SUBSCHEMA_KEYWORDS -> subschemas(keyword, value).firstNotNullOfOrNull {
                    walk(it, reachable = false, path = path + keyword, found)
                }

                else -> null
            }
            if (problem != null) return problem
        }
        return null
    }

    private fun subschemas(keyword: String, value: JsonElement): List<JsonElement> = when {
        value is JsonArray -> value

        value is JsonObject && keyword in setOf("patternProperties", "\$defs", "definitions", "dependentSchemas") ->
            value.values.toList()

        else -> listOf(value)
    }

    private fun isTchar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in TCHAR_SPECIALS
}
