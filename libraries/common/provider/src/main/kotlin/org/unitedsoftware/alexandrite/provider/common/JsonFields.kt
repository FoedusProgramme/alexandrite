package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** This element's string, null when it is absent or no string. */
public fun JsonElement?.stringContent(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The string field [name], null when it is absent or no string. */
public fun JsonObject.string(name: String): String? = this[name].stringContent()

/** The number field [name], null when it is absent or no positive integer. */
public fun JsonObject.positiveInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()?.takeIf { it > 0 }

/** The number field [name], null when it is absent or no integer. */
public fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

/** The boolean field [name], null when it is absent or no boolean. */
public fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()

/** The object field [name], null when it is absent or no object. */
public fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

/** The strings of the array field [name], null when it is absent or no array. */
public fun JsonObject.strings(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { it.stringContent() }
