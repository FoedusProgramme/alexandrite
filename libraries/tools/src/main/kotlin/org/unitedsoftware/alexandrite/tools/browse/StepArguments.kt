package org.unitedsoftware.alexandrite.tools.browse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A call's arguments read by type, refusing one of another type. */
internal class StepArguments(private val arguments: JsonObject) {
    private fun primitive(name: String): JsonPrimitive? = when (val value = arguments[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value
        else -> throw IllegalArgumentException("$name takes a single value.")
    }

    fun string(name: String): String? = primitive(name)?.let {
        require(it.isString) { "$name is a string." }
        it.content
    }

    fun int(name: String): Int? = primitive(name)?.let { requireNotNull(it.intOrNull) { "$name is a whole number." } }

    fun number(name: String): Double? = primitive(name)?.let {
        requireNotNull(it.doubleOrNull) { "$name is a number." }
    }

    fun boolean(name: String): Boolean? =
        primitive(name)?.let { requireNotNull(it.booleanOrNull) { "$name is true or false." } }

    fun strings(name: String): List<String>? = when (val value = arguments[name]) {
        null, JsonNull -> null

        is JsonArray -> value.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: throw IllegalArgumentException("$name holds strings.")
        }

        else -> throw IllegalArgumentException("$name is a list of strings.")
    }

    fun <T> enum(name: String, values: Map<String, T>): T? = string(name)?.let {
        requireNotNull(values[it]) { "$name is one of ${values.keys.joinToString()}." }
    }

    fun <T : Any> required(name: String, value: T?): T = requireNotNull(value) { "$name is missing." }
}
