package org.unitedsoftware.alexandrite.channel.onebot.protocol.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId

/**
 * Reads and writes the three shapes of a message and the `type` plus `data` shape of a segment.
 *
 * Reading keeps every field of a segment the standard does not name in [OneBotSegment.Unknown], and reading a
 * segment whose parameter the standard names but this version does not keep still returns the named type.
 */
public object OneBotSegmentCodec {
    /** The message of [element], which may be a string, an array of segments or a single segment object. */
    public fun decodeMessage(element: JsonElement): OneBotMessage = when (element) {
        is JsonArray -> OneBotMessage.ArrayValue(element.map(::decodeSegment))

        is JsonObject ->
            if (element.containsKey("type")) {
                OneBotMessage.SingleSegment(decodeSegment(element))
            } else {
                OneBotMessage.ArrayValue(emptyList())
            }

        is JsonPrimitive -> OneBotMessage.StringValue(element.content)
    }

    /** The segments of [element], which may be an array of segments or a single segment object. */
    public fun decodeMessageSegments(element: JsonElement): List<OneBotSegment> = decodeMessage(element).segments

    /** The segment of [element], an object with a `type` and a `data` object. */
    public fun decodeSegment(element: JsonElement): OneBotSegment {
        val segment = element as? JsonObject
            ?: return OneBotSegment.Unknown(OneBotSegment.TEXT, JsonObject(emptyMap()))
        val type = segment["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = segment["data"] as? JsonObject ?: JsonObject(emptyMap())
        return segment(type, data)
    }

    /** The JSON of [message] in the array shape, or as the string it already is. */
    public fun encodeMessage(message: OneBotMessage): JsonElement = when (message) {
        is OneBotMessage.StringValue -> JsonPrimitive(message.text)
        is OneBotMessage.ArrayValue -> buildJsonArray { message.segments.forEach { add(encodeSegment(it)) } }
        is OneBotMessage.SingleSegment -> encodeSegment(message.segment)
    }

    /** The JSON of [message] in the array shape. */
    public fun encodeMessageArray(message: OneBotMessage): JsonArray =
        buildJsonArray { message.segments.forEach { add(encodeSegment(it)) } }

    /** The JSON of [segment], an object with a `type` and a `data` object. */
    public fun encodeSegment(segment: OneBotSegment): JsonObject = buildJsonObject {
        put("type", segment.type)
        when (segment) {
            is OneBotSegment.Text -> putJsonObject("data") { put("text", segment.text) }

            is OneBotSegment.Face -> putJsonObject("data") { put("id", segment.id) }

            is OneBotSegment.Image -> putJsonObject("data") {
                put("file", segment.file)
                optional("type", segment.subtype)
                optional("url", segment.url)
                optional("cache", segment.cache?.let(::flag))
                optional("proxy", segment.proxy?.let(::flag))
                optional("timeout", segment.timeout)
            }

            is OneBotSegment.Record -> putJsonObject("data") {
                put("file", segment.file)
                optional("magic", segment.magic?.let(::flag))
                optional("url", segment.url)
                optional("cache", segment.cache?.let(::flag))
                optional("proxy", segment.proxy?.let(::flag))
                optional("timeout", segment.timeout)
            }

            is OneBotSegment.Video -> putJsonObject("data") {
                put("file", segment.file)
                optional("url", segment.url)
                optional("cache", segment.cache?.let(::flag))
                optional("proxy", segment.proxy?.let(::flag))
                optional("timeout", segment.timeout)
            }

            is OneBotSegment.File -> putJsonObject("data") {
                put("file", segment.file)
                optional("name", segment.name)
                optional("url", segment.url)
            }

            is OneBotSegment.At -> putJsonObject("data") { put("qq", user(segment.userId)) }

            is OneBotSegment.Rps, is OneBotSegment.Dice, is OneBotSegment.Shake -> putJsonObject("data") {}

            is OneBotSegment.Poke -> putJsonObject("data") {
                put("type", segment.pokeType)
                put("id", segment.id)
                optional("name", segment.name)
            }

            is OneBotSegment.Anonymous -> putJsonObject("data") { optional("ignore", segment.ignore?.let(::flag)) }

            is OneBotSegment.Share -> putJsonObject("data") {
                optional("url", segment.url)
                optional("title", segment.title)
                optional("content", segment.content)
                optional("image", segment.image)
            }

            is OneBotSegment.Contact -> putJsonObject("data") {
                put("type", segment.contactType)
                put("id", segment.id)
            }

            is OneBotSegment.Location -> putJsonObject("data") {
                put("lat", segment.lat)
                put("lon", segment.lon)
                optional("title", segment.title)
                optional("content", segment.content)
            }

            is OneBotSegment.Music -> putJsonObject("data") {
                put("type", segment.musicType)
                put("id", segment.id)
                optional("url", segment.url)
                optional("audio", segment.audio)
                optional("title", segment.title)
                optional("content", segment.content)
                optional("image", segment.image)
            }

            is OneBotSegment.Reply -> putJsonObject("data") { put("id", number(segment.id.value)) }

            is OneBotSegment.Forward -> putJsonObject("data") { put("id", segment.id) }

            is OneBotSegment.Node -> putJsonObject("data") {
                segment.id?.let { put("id", number(it.value)) }
                segment.userId?.let { put("user_id", user(it.value)) }
                put("nickname", segment.nickname)
                segment.content?.let { content -> put("content", encodeMessage(content)) }
            }

            is OneBotSegment.Xml -> putJsonObject("data") { put("data", segment.data) }

            is OneBotSegment.Json -> putJsonObject("data") { put("data", segment.data) }

            is OneBotSegment.Unknown -> put("data", segment.data)
        }
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    /** [value] written under [key], and left out when it is null, as an optional parameter that is absent. */
    private fun JsonObjectBuilder.optional(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    /** [value] written under [key], and left out when it is null. */
    private fun JsonObjectBuilder.optional(key: String, value: Int?) {
        if (value != null) put(key, value)
    }

    /** [value] as a JSON number when it is one, as a string when it is too large for a [Long]. */
    internal fun number(id: String): JsonElement = id.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(id)

    /** [qq] as a JSON number, or the string `all`. */
    private fun user(qq: String): JsonElement = qq.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(qq)

    private fun segment(type: String, data: JsonObject): OneBotSegment = when (type) {
        OneBotSegment.TEXT -> OneBotSegment.Text(data.string("text").orEmpty())

        OneBotSegment.FACE -> OneBotSegment.Face(data.string("id").orEmpty())

        OneBotSegment.IMAGE -> OneBotSegment.Image(
            file = data.string("file"),
            subtype = data.string("type"),
            url = data.string("url"),
            cache = data.flag("cache"),
            proxy = data.flag("proxy"),
            timeout = data.number("timeout"),
        )

        OneBotSegment.RECORD -> OneBotSegment.Record(
            file = data.string("file"),
            magic = data.flag("magic"),
            url = data.string("url"),
            cache = data.flag("cache"),
            proxy = data.flag("proxy"),
            timeout = data.number("timeout"),
        )

        OneBotSegment.VIDEO -> OneBotSegment.Video(
            file = data.string("file"),
            url = data.string("url"),
            cache = data.flag("cache"),
            proxy = data.flag("proxy"),
            timeout = data.number("timeout"),
        )

        OneBotSegment.FILE -> OneBotSegment.File(
            file = data.string("file").orEmpty(),
            name = data.string("name"),
            url = data.string("url"),
        )

        OneBotSegment.AT -> OneBotSegment.At(data.string("qq").orEmpty())

        OneBotSegment.RPS -> OneBotSegment.Rps

        OneBotSegment.DICE -> OneBotSegment.Dice

        OneBotSegment.SHAKE -> OneBotSegment.Shake

        OneBotSegment.POKE -> OneBotSegment.Poke(data.string("type"), data.string("id"), data.string("name"))

        OneBotSegment.ANONYMOUS -> OneBotSegment.Anonymous(data.flag("ignore"))

        OneBotSegment.SHARE -> OneBotSegment.Share(
            url = data.string("url").orEmpty(),
            title = data.string("title").orEmpty(),
            content = data.string("content"),
            image = data.string("image"),
        )

        OneBotSegment.CONTACT -> OneBotSegment.Contact(data.string("type").orEmpty(), data.string("id").orEmpty())

        OneBotSegment.LOCATION -> OneBotSegment.Location(
            lat = data.string("lat").orEmpty(),
            lon = data.string("lon").orEmpty(),
            title = data.string("title"),
            content = data.string("content"),
        )

        OneBotSegment.MUSIC -> OneBotSegment.Music(
            musicType = data.string("type").orEmpty(),
            id = data.string("id"),
            url = data.string("url"),
            audio = data.string("audio"),
            title = data.string("title"),
            content = data.string("content"),
            image = data.string("image"),
        )

        OneBotSegment.REPLY -> OneBotSegment.Reply(MessageId(data.numberText("id") ?: "0"))

        OneBotSegment.FORWARD -> OneBotSegment.Forward(data.string("id").orEmpty())

        OneBotSegment.NODE -> OneBotSegment.Node(
            id = data.numberText("id")?.let(::MessageId),
            userId = data.numberText("user_id")?.let(::UserId),
            nickname = data.string("nickname"),
            content = data["content"]?.let(::decodeMessage),
        )

        OneBotSegment.XML -> OneBotSegment.Xml(data.string("data").orEmpty())

        OneBotSegment.JSON -> OneBotSegment.Json(data.string("data").orEmpty())

        else -> OneBotSegment.Unknown(type, data)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.numberText(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { value ->
        when (value.contentOrNull) {
            "1", "yes", "true" -> true
            "0", "no", "false" -> false
            else -> value.booleanOrNull
        }
    }
}
