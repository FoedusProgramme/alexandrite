package org.unitedsoftware.alexandrite.channel.onebot.protocol.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId

/**
 * The CQ code string format of a message, `[CQ:type,key=value]` with plain text between the codes.
 *
 * The characters that end a code are escaped, so a message always round-trips: `&` as `&amp;`, `[` as `&#91;`,
 * `]` as `&#93;`, `,` as `&#44;`. Reading accepts these entities and leaves every other character as it is.
 */
public object CqCode {
    private const val PREFIX = "[CQ:"
    private const val SUFFIX = ']'

    /** The entity a parameter uses for a comma, which is the one entity plain text leaves as it is. */
    private const val COMMA_ENTITY = "&#44;"
    private const val SEPARATOR = ','
    private const val ASSIGNMENT = '='

    /**
     * The segments of [text], with the text between the codes as [OneBotSegment.Text].
     *
     * The entities of the format stand for the characters that would otherwise end a code, so reading turns them
     * back into those characters everywhere, inside a code and in the text between the codes alike.
     */
    public fun decode(text: String): List<OneBotSegment> {
        if (PREFIX !in text) return if (text.isEmpty()) emptyList() else listOf(OneBotSegment.Text(unescape(text)))
        val segments = mutableListOf<OneBotSegment>()
        val plain = StringBuilder()
        var at = 0
        while (at < text.length) {
            val start = text.indexOf(PREFIX, at)
            if (start < 0) {
                plain.append(text, at, text.length)
                break
            }
            plain.append(text, at, start)
            val end = text.indexOf(SUFFIX, start + PREFIX.length)
            if (end < 0) {
                plain.append(text, start, text.length)
                break
            }
            val segment = parse(text.substring(start + PREFIX.length, end))
            if (segment == null) {
                plain.append(text, start, end + 1)
            } else {
                if (plain.isNotEmpty()) {
                    segments += OneBotSegment.Text(unescape(plain.toString()))
                    plain.setLength(0)
                }
                segments += segment
            }
            at = end + 1
        }
        if (plain.isNotEmpty()) segments += OneBotSegment.Text(unescape(plain.toString()))
        return segments
    }

    /** The string that spells [segments] as CQ codes. */
    public fun encode(segments: List<OneBotSegment>): String = buildString {
        for (segment in segments) append(encode(segment))
    }

    /** The string that spells [segment] as a CQ code, or as plain text for a [OneBotSegment.Text]. */
    public fun encode(segment: OneBotSegment): String = when (segment) {
        is OneBotSegment.Text -> escape(segment.text)

        is OneBotSegment.Unknown -> encodeUnknown(segment)

        is OneBotSegment.Rps -> "[CQ:rps]"

        is OneBotSegment.Dice -> "[CQ:dice]"

        is OneBotSegment.Shake -> "[CQ:shake]"

        is OneBotSegment.At -> parameters("at", "qq" to segment.userId)

        is OneBotSegment.Face -> parameters("face", "id" to segment.id)

        is OneBotSegment.Image -> parameters(
            "image",
            "file" to segment.file,
            "type" to segment.subtype,
            "url" to segment.url,
            "cache" to segment.cache?.let(::flag),
            "proxy" to segment.proxy?.let(::flag),
            "timeout" to segment.timeout?.toString(),
        )

        is OneBotSegment.Record -> parameters(
            "record",
            "file" to segment.file,
            "magic" to segment.magic?.let(::flag),
            "url" to segment.url,
            "cache" to segment.cache?.let(::flag),
            "proxy" to segment.proxy?.let(::flag),
            "timeout" to segment.timeout?.toString(),
        )

        is OneBotSegment.File -> parameters(
            "file",
            "file" to segment.file,
            "name" to segment.name,
            "url" to segment.url,
        )

        is OneBotSegment.Video -> parameters(
            "video",
            "file" to segment.file,
            "url" to segment.url,
            "cache" to segment.cache?.let(::flag),
            "proxy" to segment.proxy?.let(::flag),
            "timeout" to segment.timeout?.toString(),
        )

        is OneBotSegment.Poke -> parameters(
            "poke",
            "type" to segment.pokeType,
            "id" to segment.id,
            "name" to segment.name,
        )

        is OneBotSegment.Anonymous -> parameters("anonymous", "ignore" to segment.ignore?.let(::flag))

        is OneBotSegment.Share -> parameters(
            "share",
            "url" to segment.url,
            "title" to segment.title,
            "content" to segment.content,
            "image" to segment.image,
        )

        is OneBotSegment.Contact ->
            parameters("contact", "type" to segment.contactType, "id" to segment.id)

        is OneBotSegment.Location -> parameters(
            "location",
            "lat" to segment.lat,
            "lon" to segment.lon,
            "title" to segment.title,
            "content" to segment.content,
        )

        is OneBotSegment.Music -> parameters(
            "music",
            "type" to segment.musicType,
            "id" to segment.id,
            "url" to segment.url,
            "audio" to segment.audio,
            "title" to segment.title,
            "content" to segment.content,
            "image" to segment.image,
        )

        is OneBotSegment.Reply -> parameters("reply", "id" to segment.id.value)

        is OneBotSegment.Forward -> parameters("forward", "id" to segment.id)

        is OneBotSegment.Node -> parameters(
            "node",
            "id" to segment.id?.value,
            "user_id" to segment.userId?.value,
            "nickname" to segment.nickname,
            "content" to segment.content?.let { encode(it.segments) },
        )

        is OneBotSegment.Xml -> parameters("xml", "data" to segment.data)

        is OneBotSegment.Json -> parameters("json", "data" to segment.data)
    }

    /**
     * The value of [text] with the characters that end a code escaped.
     *
     * A comma separates the parameters of a code and means nothing in the text outside one, so it is escaped only by
     * [escapeValue]: escaping it in ordinary text would turn every comma a person types into an entity the standard
     * does not ask for.
     */
    public fun escape(text: String): String = escape(text, comma = false)

    /** The value of a parameter, where a comma also ends it and is therefore escaped. */
    public fun escapeValue(value: String): String = escape(value, comma = true)

    private fun escape(text: String, comma: Boolean): String = buildString(text.length) {
        for (char in text) {
            when {
                char == '&' -> append("&amp;")
                char == '[' -> append("&#91;")
                char == ']' -> append("&#93;")
                char == ',' && comma -> append("&#44;")
                else -> append(char)
            }
        }
    }

    /** The value of [text] with the entities the format uses turned back into their characters. */
    public fun unescape(text: String): String = unescape(text, comma = false)

    /** The value of a parameter, where `&#44;` names the comma that would otherwise end it. */
    public fun unescapeValue(value: String): String = unescape(value, comma = true)

    private fun unescape(text: String, comma: Boolean): String = buildString(text.length) {
        var at = 0
        while (at < text.length) {
            val char = text[at]
            if (char != '&') {
                append(char)
                at++
                continue
            }
            val entity = ENTITIES.firstOrNull { text.startsWith(it.first, at) && (comma || it.first != COMMA_ENTITY) }
            if (entity == null) {
                append(char)
                at++
            } else {
                append(entity.second)
                at += entity.first.length
            }
        }
    }

    private val ENTITIES: List<Pair<String, Char>> =
        listOf("&amp;" to '&', "&#91;" to '[', "&#93;" to ']', "&#44;" to ',')
            .sortedByDescending { it.first.length }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun parameters(type: String, vararg pairs: Pair<String, String?>): String = buildString {
        append(PREFIX).append(type)
        for ((key, value) in pairs) {
            if (value == null) continue
            append(SEPARATOR).append(key).append(ASSIGNMENT).append(escapeValue(value))
        }
        append(SUFFIX)
    }

    private fun encodeUnknown(segment: OneBotSegment.Unknown): String {
        val pairs = segment.data.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }
        return parameters(segment.type, *pairs.toTypedArray())
    }

    /** The segment of the text between `[CQ:` and `]`, null when it names no segment type. */
    private fun parse(body: String): OneBotSegment? {
        if (body.isEmpty()) return null
        val parts = body.split(SEPARATOR)
        val type = parts.first().trim()
        if (type.isEmpty()) return null
        val parameters = LinkedHashMap<String, String>()
        for (part in parts.drop(1)) {
            val assignment = part.indexOf(ASSIGNMENT)
            if (assignment <= 0) continue
            parameters[part.substring(0, assignment).trim()] = unescapeValue(part.substring(assignment + 1))
        }
        return segment(type, parameters)
    }

    private fun segment(type: String, parameters: Map<String, String>): OneBotSegment {
        val yes = { key: String -> parameters[key]?.let { it == "1" || it == "yes" || it == "true" } }
        val int = { key: String -> parameters[key]?.toIntOrNull() }
        return when (type) {
            OneBotSegment.TEXT -> OneBotSegment.Text(parameters["text"].orEmpty())

            OneBotSegment.FACE -> OneBotSegment.Face(parameters["id"].orEmpty())

            OneBotSegment.IMAGE -> OneBotSegment.Image(
                file = parameters["file"],
                subtype = parameters["type"],
                url = parameters["url"],
                cache = yes("cache"),
                proxy = yes("proxy"),
                timeout = int("timeout"),
            )

            OneBotSegment.RECORD -> OneBotSegment.Record(
                file = parameters["file"],
                magic = yes("magic"),
                url = parameters["url"],
                cache = yes("cache"),
                proxy = yes("proxy"),
                timeout = int("timeout"),
            )

            OneBotSegment.VIDEO -> OneBotSegment.Video(
                file = parameters["file"],
                url = parameters["url"],
                cache = yes("cache"),
                proxy = yes("proxy"),
                timeout = int("timeout"),
            )

            OneBotSegment.AT -> OneBotSegment.At(parameters["qq"].orEmpty())

            OneBotSegment.RPS -> OneBotSegment.Rps

            OneBotSegment.DICE -> OneBotSegment.Dice

            OneBotSegment.SHAKE -> OneBotSegment.Shake

            OneBotSegment.POKE ->
                OneBotSegment.Poke(parameters["type"], parameters["id"], parameters["name"])

            OneBotSegment.ANONYMOUS -> OneBotSegment.Anonymous(yes("ignore"))

            OneBotSegment.SHARE -> OneBotSegment.Share(
                url = parameters["url"].orEmpty(),
                title = parameters["title"].orEmpty(),
                content = parameters["content"],
                image = parameters["image"],
            )

            OneBotSegment.CONTACT ->
                OneBotSegment.Contact(parameters["type"].orEmpty(), parameters["id"].orEmpty())

            OneBotSegment.LOCATION -> OneBotSegment.Location(
                lat = parameters["lat"].orEmpty(),
                lon = parameters["lon"].orEmpty(),
                title = parameters["title"],
                content = parameters["content"],
            )

            OneBotSegment.MUSIC -> OneBotSegment.Music(
                musicType = parameters["type"].orEmpty(),
                id = parameters["id"],
                url = parameters["url"],
                audio = parameters["audio"],
                title = parameters["title"],
                content = parameters["content"],
                image = parameters["image"],
            )

            OneBotSegment.REPLY -> {
                val id = parameters["id"]
                OneBotSegment.Reply(MessageId(if (id != null && isNumber(id)) id else "0"))
            }

            OneBotSegment.FORWARD -> OneBotSegment.Forward(parameters["id"].orEmpty())

            OneBotSegment.NODE -> OneBotSegment.Node(
                id = parameters["id"]?.takeIf(::isNumber)?.let { MessageId(it) },
                userId = parameters["user_id"]?.takeIf(::isNumber)?.let { UserId(it) },
                nickname = parameters["nickname"],
                content = parameters["content"]?.let { OneBotMessage.StringValue(it) },
            )

            OneBotSegment.XML -> OneBotSegment.Xml(parameters["data"].orEmpty())

            OneBotSegment.JSON -> OneBotSegment.Json(parameters["data"].orEmpty())

            else -> OneBotSegment.Unknown(type, unknownData(parameters))
        }
    }

    private fun unknownData(parameters: Map<String, String>): JsonObject =
        JsonObject(parameters.mapValues { (_, value) -> JsonPrimitive(value) })

    private fun isNumber(text: String): Boolean = text.isNotEmpty() && text.all(Char::isDigit)
}
