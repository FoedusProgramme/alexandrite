package org.unitedsoftware.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko

/**
 * What a channel can do in one chat, where a new builder claims no streaming, no proactive messages, plain text and no
 * files.
 */
@Poko
public class ChannelCapabilities private constructor(
    /** How many platform messages one reply may take, null when there is no limit. */
    public val maxPartsPerReply: Int?,
    /** Whether the channel shows a reply while it is written. */
    public val streaming: Boolean,
    /** Whether the final message takes the place of the previews. */
    public val finalReplacesPreview: Boolean,
    /** Whether the bot may write to the chat without being addressed. */
    public val proactive: Boolean,
    public val markups: Set<Markup>,
    /** The kinds of [TurnEvent] the channel wants sent to [ReplySink.event]. */
    public val events: Set<TurnEventKind>,
    /** Whether a message to the chat may carry [OutboundMessage.files]. */
    public val files: Boolean,
    /** The most bytes one file may hold, null when there is no limit. */
    public val maxFileBytes: Long?,
    /**
     * Whether each sub-agent run of a turn gets a reply of its own, which shows the run's words, thoughts, tool calls
     * and usage and then its report, while the turn's reply shows only the call that delegated it.
     */
    public val runReplies: Boolean,
) {
    init {
        require(maxPartsPerReply == null || maxPartsPerReply > 0) {
            "A reply takes at least one part, was $maxPartsPerReply."
        }
        require(markups.isNotEmpty()) { "A channel sends at least one markup." }
        require(maxFileBytes == null || maxFileBytes > 0) { "A file may hold at least one byte, was $maxFileBytes." }
    }

    public fun toBuilder(): Builder = Builder()
        .maxPartsPerReply(maxPartsPerReply)
        .streaming(streaming)
        .finalReplacesPreview(finalReplacesPreview)
        .proactive(proactive)
        .markups(markups)
        .events(events)
        .files(files)
        .maxFileBytes(maxFileBytes)
        .runReplies(runReplies)

    public class Builder internal constructor() {
        private var maxPartsPerReply: Int? = null
        private var streaming: Boolean = false
        private var finalReplacesPreview: Boolean = false
        private var proactive: Boolean = false
        private var markups: Set<Markup> = setOf(Markup.PLAIN)
        private var events: Set<TurnEventKind> = emptySet()
        private var files: Boolean = false
        private var maxFileBytes: Long? = null
        private var runReplies: Boolean = false

        public fun maxPartsPerReply(maxPartsPerReply: Int?): Builder = apply {
            this.maxPartsPerReply = maxPartsPerReply
        }

        public fun streaming(streaming: Boolean): Builder = apply { this.streaming = streaming }

        public fun finalReplacesPreview(finalReplacesPreview: Boolean): Builder =
            apply { this.finalReplacesPreview = finalReplacesPreview }

        public fun proactive(proactive: Boolean): Builder = apply { this.proactive = proactive }

        public fun markups(markups: Set<Markup>): Builder = apply { this.markups = markups.toSet() }

        public fun events(events: Set<TurnEventKind>): Builder = apply { this.events = events.toSet() }

        public fun files(files: Boolean): Builder = apply { this.files = files }

        public fun maxFileBytes(maxFileBytes: Long?): Builder = apply { this.maxFileBytes = maxFileBytes }

        public fun runReplies(runReplies: Boolean): Builder = apply { this.runReplies = runReplies }

        public fun build(): ChannelCapabilities = ChannelCapabilities(
            maxPartsPerReply,
            streaming,
            finalReplacesPreview,
            proactive,
            markups,
            events,
            files,
            maxFileBytes,
            runReplies,
        )
    }

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

public inline fun ChannelCapabilities.rebuild(block: ChannelCapabilities.Builder.() -> Unit): ChannelCapabilities =
    toBuilder().apply(block).build()
