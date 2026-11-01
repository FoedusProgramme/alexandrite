package org.unitedsoftware.alexandrite.http.common

/** One event of a server-sent event stream. */
public class SseEvent(
    /** The event's type, `message` unless the stream names one. */
    public val type: String,
    public val data: String,
    /** The last event id the stream set, null when it set none. */
    public val id: String?,
) {
    override fun toString(): String = "SseEvent(type=$type, id=$id, data=<${data.length} chars>)"
}

/** Parses a server-sent event stream from pieces of its bytes, as the HTML standard does. */
public class SseParser(
    /** The most bytes of a line and characters of an event's data. */
    private val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit > 0) { "The limit is positive, was $limit." }
    }

    private var line = ByteArray(256)
    private var lineLength = 0
    private var afterCarriageReturn = false
    private var firstLine = true
    private val data = StringBuilder()
    private var eventType = ""
    private var lastEventId = ""

    /** The reconnection time in milliseconds that the stream last set, null when it set none. */
    public var retry: Long? = null
        private set

    /** The events that [length] bytes of [bytes] from [offset] complete. */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<SseEvent> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "No range $offset+$length." }
        val events = mutableListOf<SseEvent>()
        for (index in offset until offset + length) {
            val byte = bytes[index]
            if (afterCarriageReturn) {
                afterCarriageReturn = false
                if (byte == LF) continue
            }
            when (byte) {
                CR -> {
                    endLine(events)
                    afterCarriageReturn = true
                }

                LF -> endLine(events)

                else -> append(byte)
            }
        }
        return events
    }

    /** The event the stream's end leaves without the blank line that would dispatch it, null when there is none. */
    public fun finish(): SseEvent? {
        val events = mutableListOf<SseEvent>()
        if (lineLength > 0) endLine(events)
        dispatch(events)
        return events.singleOrNull()
    }

    private fun append(byte: Byte) {
        if (lineLength == limit) throw tooLong()
        if (lineLength == line.size) line = line.copyOf(minOf(line.size * 2, limit))
        line[lineLength++] = byte
    }

    private fun endLine(events: MutableList<SseEvent>) {
        var text = String(line, 0, lineLength, Charsets.UTF_8)
        lineLength = 0
        if (firstLine) {
            firstLine = false
            text = text.removePrefix(BOM)
        }
        process(text, events)
    }

    private fun process(text: String, events: MutableList<SseEvent>) {
        if (text.isEmpty()) return dispatch(events)
        if (text[0] == ':') return
        val colon = text.indexOf(':')
        val field = if (colon < 0) text else text.substring(0, colon)
        val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> eventType = value

            "data" -> {
                if (value.length > limit - data.length) throw tooLong()
                data.append(value).append('\n')
            }

            "id" -> if ('\u0000' !in value) lastEventId = value

            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) value.toLongOrNull()?.let { retry = it }
        }
    }

    private fun dispatch(events: MutableList<SseEvent>) {
        if (data.isEmpty()) {
            eventType = ""
            return
        }
        events +=
            SseEvent(eventType.ifEmpty { "message" }, data.substring(0, data.length - 1), lastEventId.ifEmpty { null })
        data.setLength(0)
        eventType = ""
    }

    private fun tooLong(): HttpException =
        HttpException(HttpFailureKind.PROTOCOL, "an event of the stream is longer than $limit bytes.")

    public companion object {
        public const val DEFAULT_LIMIT: Int = 16 * 1024 * 1024

        private const val BOM = "\uFEFF"
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
    }
}
