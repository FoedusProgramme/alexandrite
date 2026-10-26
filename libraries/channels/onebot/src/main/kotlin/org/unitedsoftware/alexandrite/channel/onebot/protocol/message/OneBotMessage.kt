package org.unitedsoftware.alexandrite.channel.onebot.protocol.message

/**
 * A message as the API takes it and as events report it.
 *
 * OneBot accepts the same message as a string, as an array of segments, or as a single segment object, so all three
 * shapes decode into this type and the codec writes the one a request needs.
 */
public sealed interface OneBotMessage {
    /** The segments of the message, one text segment for [StringValue]. */
    public val segments: List<OneBotSegment>

    /** A message written as a string, in the message's own [format] such as a CQ code string. */
    public data class StringValue(
        public val text: String,
        /** How [text] spells its segments, by default the string format with CQ codes. */
        public val format: StringFormat = StringFormat.STRING,
    ) : OneBotMessage {
        override val segments: List<OneBotSegment> get() = format.parse(text)
    }

    /** A message written as an array of segments. */
    public data class ArrayValue(override val segments: List<OneBotSegment>) : OneBotMessage

    /** A message written as one segment object, the shape the API also accepts. */
    public data class SingleSegment(public val segment: OneBotSegment) : OneBotMessage {
        override val segments: List<OneBotSegment> get() = listOf(segment)
    }

    public companion object {
        /** A message of one plain text segment. */
        public fun text(text: String): OneBotMessage = ArrayValue(listOf(OneBotSegment.Text(text)))

        /** A message of [segments]. */
        public fun of(vararg segments: OneBotSegment): OneBotMessage = ArrayValue(segments.toList())
    }
}

/** How a string message spells its segments. */
public enum class StringFormat {
    /** The string format, where segments are written as CQ codes. */
    STRING {
        override fun parse(text: String): List<OneBotSegment> = CqCode.decode(text)
    },
    ;

    /** The segments of [text]. */
    public abstract fun parse(text: String): List<OneBotSegment>
}
