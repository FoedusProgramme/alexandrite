package org.unitedsoftware.alexandrite.channel.onebot.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The JSON of the OneBot wire.
 *
 * The standard says nothing about repeated keys or unknown ones, and an implementation adds fields of its own, so
 * reading ignores what this version does not know and writing keeps the defaults out of the request.
 */
internal val OneBotWire: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}

/** The `echo` a call is answered under, which an implementation returns as it is. */
internal fun echoOf(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

/** The text of [key] in this object, null when it holds none. */
internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

/** The long of [key] in this object, null when it holds no number. */
internal fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()
