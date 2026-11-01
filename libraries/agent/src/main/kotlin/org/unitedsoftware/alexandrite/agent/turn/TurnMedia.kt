package org.unitedsoftware.alexandrite.agent.turn

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.directory.Inbox
import org.unitedsoftware.alexandrite.agent.directory.Saving
import org.unitedsoftware.alexandrite.agent.directory.generatedName
import org.unitedsoftware.alexandrite.agent.directory.inboxName
import org.unitedsoftware.alexandrite.agent.media.MediaPlan
import org.unitedsoftware.alexandrite.agent.media.MediaReadException
import org.unitedsoftware.alexandrite.agent.media.MediaReader
import org.unitedsoftware.alexandrite.agent.media.MediaReading
import org.unitedsoftware.alexandrite.agent.media.MediaRoute
import org.unitedsoftware.alexandrite.agent.prompt.Escaper
import org.unitedsoftware.alexandrite.agent.prompt.NotedMedia
import org.unitedsoftware.alexandrite.agent.prompt.mediaNote
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.agent.prompt.shortened
import org.unitedsoftware.alexandrite.internal.media.NO_IMAGE_CODEC
import org.unitedsoftware.alexandrite.internal.media.NormalizedImage
import org.unitedsoftware.alexandrite.internal.media.imageType
import org.unitedsoftware.alexandrite.internal.media.modelImage
import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
import org.unitedsoftware.alexandrite.sdk.channel.MediaAttachment
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.storeInline
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.Part
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserPart
import kotlin.coroutines.cancellation.CancellationException

/**
 * The media of the turn [turn]: its messages' attachments saved into the [inbox], put into [store] for a model that
 * takes them natively or [reader] for one that does not, and the bytes its requests carry, images fit by [codec].
 */
internal class TurnMedia(
    private val turn: TurnId,
    private val store: MediaStore,
    private val maxBytes: Long,
    private val inbox: Inbox,
    private val reader: MediaReader,
    private val codec: ImageCodec?,
) {
    /** Why an image is not shown when it cannot be fit. */
    private val unfit = if (codec == null) NO_IMAGE_CODEC else "the image could not be decoded"

    /** The bytes of the media the turn put or read, null where the store has none. */
    private val loaded = HashMap<MediaId, InlineMedia?>()

    /**
     * The parts that carry [message]'s attachments as [plan] routes each kind, followed by the note that names where
     * each was saved in the inbox of [info] or why it was not.
     */
    suspend fun put(info: TurnInfo, message: IncomingMessage, plan: MediaPlan): List<UserPart> {
        val parts = mutableListOf<UserPart>()
        val noted = message.media.mapIndexed { index, attachment ->
            val generated = generatedName(attachment.kind, attachment.mediaType, message.ref.id, index + 1)
            put(info, attachment, generated, plan, parts)
        }
        if (noted.isNotEmpty()) parts += mediaNote(noted)
        return parts
    }

    /**
     * [result] with its inline media put into the store, read now for [turn] where [plan] routes the kind to a reader.
     */
    suspend fun stored(turn: TurnInfo, result: ToolResultEntry, plan: MediaPlan): ToolResultEntry {
        val small = result.withContent { part ->
            val image = (part as? MediaPart)?.takeIf { it.kind == MediaKind.IMAGE && it.source is InlineMedia }
            when {
                (part.inlineBytes()?.size ?: 0) > maxBytes -> leftOut(part, "larger than $maxBytes bytes")
                image != null -> normalized(image) ?: leftOut(part, unfit)
                else -> part
            }
        }
        val stored = try {
            store.storeInline(small) as ToolResultEntry
        } catch (e: IllegalArgumentException) {
            logger.debug("Turn {} cannot store the media of a tool result", this.turn, e)
            return small.withContent { if (it.inlineBytes() != null) leftOut(it, "the store refused it") else it }
        }
        for ((part, put) in small.content.zip(stored.content)) {
            val bytes = part.inlineBytes() ?: continue
            val id = ((put as? MediaPart)?.source as? StoredMedia)?.id ?: continue
            loaded[id] = bytes
        }
        for (part in stored.content) {
            val media = part as? MediaPart ?: continue
            val route = plan.route(media.kind) as? MediaRoute.Read ?: continue
            warm(turn, media, route.model)
        }
        return stored
    }

    /** [entries] with the stored media they refer to carried as [plan] routes each kind. */
    suspend fun inline(entries: List<TranscriptEntry>, plan: MediaPlan): List<TranscriptEntry> = entries.map { entry ->
        when (entry) {
            is UserEntry -> UserEntry(entry.record, entry.parts.map { userPart(it, plan) }, entry.origin)

            is ToolResultEntry -> ToolResultEntry(
                entry.record,
                entry.callId,
                entry.toolName,
                entry.content.map { outputPart(it, plan) },
                entry.outcome,
            )

            else -> entry
        }
    }

    /**
     * Saves [attachment] into the inbox of [info] as its own name or else [generated], and adds the part that [plan]
     * carries to the model for its kind to [parts].
     */
    private suspend fun put(
        info: TurnInfo,
        attachment: MediaAttachment,
        generated: String,
        plan: MediaPlan,
        parts: MutableList<UserPart>,
    ): NotedMedia {
        val content = attachment.content ?: return notSaved(attachment, checkNotNull(attachment.unavailable))
        val tooLarge = "larger than the $maxBytes bytes a message may carry"
        if ((attachment.size ?: 0) > maxBytes) return notSaved(attachment, tooLarge)
        val bytes = try {
            content.read(maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Turn {} cannot load an attachment: {}", turn, e.toString())
            return notSaved(attachment, "it could not be loaded")
        }
        if (bytes.size > maxBytes) return notSaved(attachment, tooLarge, bytes.size.toLong())
        val saving = inbox.save(info, attachment.name?.let(::inboxName) ?: generated, bytes)
        val refusal = (saving as? Saving.Refused)?.reason
        refusal?.let {
            logger.warn("Turn {} did not save an attachment of type {}: {}", turn, attachment.mediaType, it)
        }
        val view = view(info, attachment, bytes, plan, parts)
        return NotedMedia(attachment, bytes.size.toLong(), (saving as? Saving.Saved)?.path, refusal, view)
    }

    /** How the model takes [attachment] as [plan] routes its kind, adding the part it is shown to [parts]. */
    private suspend fun view(
        info: TurnInfo,
        attachment: MediaAttachment,
        bytes: ByteArray,
        plan: MediaPlan,
        parts: MutableList<UserPart>,
    ): NotedMedia.View = when (val route = plan.route(attachment.kind)) {
        is MediaRoute.Saved -> NotedMedia.View.Saved(route.reason)

        MediaRoute.Native -> shown(attachment, bytes, parts)

        is MediaRoute.Read -> {
            val view = shown(attachment, bytes, parts)
            if (view != NotedMedia.View.Shown) {
                view
            } else {
                try {
                    reader.read(info, parts.last() as MediaPart, route.model)
                    NotedMedia.View.Read(route.model)
                } catch (e: MediaReadException) {
                    NotedMedia.View.Saved(e.reason)
                }
            }
        }
    }

    /**
     * Adds the part that carries [attachment] inline to [parts], storing its bytes, an image's normalized so that no
     * model refuses its type or size.
     */
    private suspend fun shown(
        attachment: MediaAttachment,
        bytes: ByteArray,
        parts: MutableList<UserPart>,
    ): NotedMedia.View {
        val part = with(attachment) { MediaPart(kind, mediaType, InlineMedia(bytes), name, width, height) }
        val shown = if (part.kind == MediaKind.IMAGE) normalized(part) else part
        if (shown == null) return NotedMedia.View.Saved(unfit)
        val carried = (shown.source as InlineMedia).bytes()
        val stored = try {
            store.put(carried, shown.kind, shown.mediaType)
        } catch (e: IllegalArgumentException) {
            logger.debug("Turn {} cannot store an attachment: {}", turn, e.toString())
            return NotedMedia.View.Saved("it could not be stored")
        }
        loaded[stored.id] = InlineMedia(carried)
        parts += with(shown) { MediaPart(kind, mediaType, stored, name, width, height) }
        return NotedMedia.View.Shown
    }

    /** Reads [part] with [model] now, to warm the cache; a failure is only logged. */
    private suspend fun warm(turn: TurnInfo, part: MediaPart, model: ModelRef) {
        try {
            reader.read(turn, part, model)
        } catch (e: MediaReadException) {
            logger.debug("Turn {} did not warm the reading of a tool result's {}: {}", this.turn, part.kind, e)
        }
    }

    private fun notSaved(attachment: MediaAttachment, why: String, size: Long? = attachment.size): NotedMedia {
        logger.warn("Turn {} leaves out an attachment of type {} ({})", turn, attachment.mediaType, why)
        return NotedMedia(attachment, size, null, why, null)
    }

    private suspend fun userPart(part: UserPart, plan: MediaPlan): UserPart =
        if (part is MediaPart) rendered(part, plan) as UserPart else part

    private suspend fun outputPart(part: ToolOutputPart, plan: MediaPlan): ToolOutputPart =
        if (part is MediaPart) rendered(part, plan) as ToolOutputPart else part

    /** [part] as [plan] routes its kind: its bytes inline, its cached reading, or a marker naming why not. */
    private suspend fun rendered(part: MediaPart, plan: MediaPlan): Part = when (val route = plan.route(part.kind)) {
        MediaRoute.Native -> inlined(part) ?: missing(part)
        is MediaRoute.Read -> reading(part, route.model) ?: TextPart(notReadMarker(part))
        is MediaRoute.Saved -> TextPart(savedMarker(part, route.reason))
    }

    /** The cached reading of [part] by [model], framed as a context block; null where the cache has none. */
    private suspend fun reading(part: MediaPart, model: ModelRef): TextPart? {
        val found = reader.cached(part, model) ?: return null
        return TextPart(readingText(part, found))
    }

    /** [part] with its stored bytes inline, null where the store has none. */
    private suspend fun inlined(part: MediaPart): MediaPart? {
        val source = part.source as? StoredMedia ?: return part
        val bytes = (if (source.id in loaded) loaded[source.id] else read(source.id)) ?: return null
        return MediaPart(part.kind, part.mediaType, bytes, part.name, part.width, part.height)
    }

    /** What the model reads in place of [part] of a tool result, left out for [why]. */
    private fun leftOut(part: ToolOutputPart, why: String): ToolOutputPart {
        if (part !is MediaPart) return part
        logger.warn("Turn {} leaves out a tool result's {} of type {} ({})", turn, part.kind, part.mediaType, why)
        return TextPart("(${part.kind} of type ${part.mediaType}, left out: $why)")
    }

    /** What the model reads in place of [part], whose bytes the store no longer has. */
    private fun missing(part: MediaPart): TextPart =
        TextPart("(${part.kind} of type ${part.mediaType}, no longer stored)")

    /** The inline image [part] fit to a type and size every model that views images takes, null if it cannot be. */
    private fun normalized(part: MediaPart): MediaPart? {
        val bytes = (part.source as InlineMedia).bytes()
        val image = fitted(bytes) ?: return null
        val width = image.width?.takeIf { it > 0 }
        val height = image.height?.takeIf { it > 0 }
        return MediaPart(MediaKind.IMAGE, image.mediaType, InlineMedia(image.bytes), part.name, width, height)
    }

    /** [bytes] fit by the [codec], or as they are where none is bound. */
    private fun fitted(bytes: ByteArray): NormalizedImage? {
        val type = imageType(bytes) ?: return null
        return modelImage(bytes, type, codec?.let { it::normalized })
    }

    private suspend fun read(id: MediaId): InlineMedia? {
        val bytes = store.read(id)?.let(::InlineMedia)
        if (bytes == null) logger.warn("Turn {} finds no media {} in the store", turn, id)
        loaded[id] = bytes
        return bytes
    }
}

/** [part]'s [reading], framed as a context block like the others. */
private fun readingText(part: MediaPart, reading: MediaReading): String = buildString {
    appendLine("[alexandrite:media-reading]")
    appendLine("${mediaLabel(part)}, as ${reading.model} read it:")
    appendLine(Escaper.HEADERS.text(reading.text))
    append("[/alexandrite:media-reading]")
}

/** What the request reads in place of [part], which no cache entry gives a reading of for this turn's model. */
private fun notReadMarker(part: MediaPart): String = "(${mediaLabel(part)}: not read for this model)"

/** What the request reads in place of [part], whose route only saves it for [reason]. */
private fun savedMarker(part: MediaPart, reason: String): String = "(${mediaLabel(part)} not shown: ${oneLine(reason)})"

private fun mediaLabel(part: MediaPart): String = buildString {
    append(part.kind)
    part.name?.let { append(" \"").append(shortened(oneLine(it))).append('"') }
}

private fun ToolOutputPart.inlineBytes(): InlineMedia? = (this as? MediaPart)?.source as? InlineMedia

private fun ToolResultEntry.withContent(part: (ToolOutputPart) -> ToolOutputPart): ToolResultEntry =
    ToolResultEntry(record, callId, toolName, content.map(part), outcome)

private val logger = LoggerFactory.getLogger(TurnMedia::class.java)

private fun ImageCodec.normalized(bytes: ByteArray, mediaType: String, maxSide: Int, maxBytes: Int): NormalizedImage? =
    fitted(bytes, mediaType, maxSide, maxBytes)?.let {
        NormalizedImage(it.bytes, it.mediaType, it.dimensions?.width, it.dimensions?.height)
    }
