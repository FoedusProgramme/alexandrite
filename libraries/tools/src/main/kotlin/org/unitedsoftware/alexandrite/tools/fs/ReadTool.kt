package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.internal.hash.hex
import org.unitedsoftware.alexandrite.internal.hash.sha256Digest
import org.unitedsoftware.alexandrite.internal.media.imageType
import org.unitedsoftware.alexandrite.internal.path.openRegularFile
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.internal.tool.countArgument
import org.unitedsoftware.alexandrite.internal.tool.requiredString
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.tools.media.NO_IMAGE_CODEC
import org.unitedsoftware.alexandrite.tools.media.fittedImage
import org.unitedsoftware.alexandrite.tools.media.imageDimensions
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest

@Singleton
@Contribute(Tool::class)
internal class ReadTool(private val floor: HardFloor, private val ledger: ReadLedger, private val codec: ImageCodec?) :
    ReviewedTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "fs.read",
        description = "Reads a UTF-8 text file and returns its lines, each after its line number and a tab. " +
            "Returns at most $MAX_LINES lines from line 'offset' on, each cut after $MAX_LINE_CHARS characters, and " +
            "says where to go on when more follow. An image (png, jpeg, gif, webp, bmp, tiff, heic or heif, by its " +
            "bytes) is returned as an image instead of text. Refuses directories (list them with fs.glob), other " +
            "binary files and protected places.",
        parameters = schema("path") {
            property("path", "string", "The file: an absolute path, or one relative to your workspace (~ is home).")
            property("offset", "integer", "The number of the first line to return, from 1. Default 1.")
            property("limit", "integer", "How many lines to return at most. Default and at most $MAX_LINES.")
        },
        risk = ToolRisk.READ_ONLY,
        activity = ToolActivity.READ,
        titleArguments = listOf("path"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        floor.readReview(arguments.requiredString("path"), context)
    } catch (e: ToolRefusal) {
        CallReview.builder().build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = refusing {
        val path = context.absolute(arguments.requiredString("path"))
        val offset = arguments.countArgument("offset") ?: 1
        val limit = minOf(arguments.countArgument("limit") ?: MAX_LINES, MAX_LINES)
        val conversation = context.turn.conversation
        blocking {
            val file = floor.allowed(path, context)
            val attributes = attributes(file)
            checkReadable(file, path, attributes, floor.protectsAnything)
            context.touched(file)
            val digest = sha256Digest()
            DigestInputStream(openRegularFile(file, floor.protectsAnything), digest).use { input ->
                val prefix = input.readUpTo(IMAGE_SNIFF_BYTES)
                val mediaType = imageType(prefix)
                if (mediaType != null) {
                    imageResult(path, file, attributes, mediaType, prefix, input, digest, conversation)
                } else {
                    val combined = SequenceInputStream(prefix.inputStream(), input)
                    val text = try {
                        rendered(TextLines(combined, MAX_LINE_CHARS, MAX_READ_BYTES), path, offset, limit)
                    } catch (e: NotText) {
                        throw ToolRefusal("$path is no UTF-8 text (it looks binary): fs.read reads text files only.")
                    }
                    if (attributes.size() <= MAX_WRITE_BYTES && drained(combined)) {
                        ledger.record(conversation, file, HASH_PREFIX + hex(digest.digest()))
                    }
                    ToolResult(text)
                }
            }
        }
    }

    /**
     * [path]'s content as an image of [mediaType], read the rest of [input] from past [prefix], [digest] already
     * covering what either held.
     */
    private fun imageResult(
        path: Path,
        file: Path,
        attributes: BasicFileAttributes,
        mediaType: String,
        prefix: ByteArray,
        input: InputStream,
        digest: MessageDigest,
        conversation: ConversationId,
    ): ToolResult {
        if (attributes.size() > MAX_READ_BYTES) {
            return ToolResult(
                "$path is an image ($mediaType, ${attributes.size()} bytes): it is more than the " +
                    "${MAX_READ_BYTES / MIB} MiB fs.read reads, so it cannot be shown.",
            )
        }
        val bytes = prefix + input.readBytes()
        val dimensions = imageDimensions(bytes, mediaType, codec)
        val shown = if (dimensions == null) "" else ", ${dimensions.first}x${dimensions.second}"
        val why = if (codec == null) ": $NO_IMAGE_CODEC" else ""
        val normalized = fittedImage(bytes, mediaType, codec) ?: return ToolResult(
            "$path is an image ($mediaType, ${bytes.size} bytes$shown) that fs.read cannot show$why.",
        )
        if (attributes.size() <= MAX_WRITE_BYTES) {
            ledger.record(conversation, file, HASH_PREFIX + hex(digest.digest()))
        }
        return ToolResult(
            listOf(
                TextPart("$path is an image ($mediaType, ${bytes.size} bytes$shown)."),
                MediaPart(
                    MediaKind.IMAGE,
                    normalized.mediaType,
                    InlineMedia(normalized.bytes),
                    path.fileName?.toString(),
                    normalized.width.takeIf { it > 0 },
                    normalized.height.takeIf { it > 0 },
                ),
            ),
        )
    }

    /** The next [count] bytes of the stream, fewer where it ends first. */
    private fun InputStream.readUpTo(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(buffer, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == count) buffer else buffer.copyOf(filled)
    }

    /** Whether [input] ends within [MAX_WRITE_BYTES] more bytes, which it reads. */
    private fun drained(input: InputStream): Boolean {
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        var left = MAX_WRITE_BYTES
        while (left >= 0) {
            val read = input.read(buffer)
            if (read < 0) return true
            left -= read
        }
        return false
    }

    /** Lines [offset] to [offset] + [limit] of [lines], numbered, with notes on what was left out. */
    private fun rendered(lines: TextLines, path: Path, offset: Int, limit: Int): String {
        var number = 0
        while (number < offset - 1) {
            lines.next() ?: throw ToolRefusal(pastEnd(path, number, offset, lines.truncated))
            number++
        }
        var next = lines.next()
        if (next == null) {
            if (offset > 1) throw ToolRefusal(pastEnd(path, number, offset, lines.truncated))
            return "[The file is empty.]"
        }
        val text = StringBuilder()
        val cut = mutableListOf<Int>()
        while (next != null && number - offset + 1 < limit) {
            val line = "${number + 1}\t${next.text}\n"
            if (text.isNotEmpty() && text.length + line.length > MAX_OUTPUT_CHARS) break
            text.append(line)
            number++
            if (next.cut) cut += number
            next = lines.next()
        }
        val notes = listOfNotNull(
            named("Lines cut after $MAX_LINE_CHARS characters", cut.map { "$it" }),
            when {
                next != null -> "More lines follow: read them with offset ${number + 1}."

                lines.truncated ->
                    "The file goes on past its first ${MAX_READ_BYTES / MIB} MiB, which is all " +
                        "fs.read reads."

                else -> null
            },
        )
        return text.toString() + notes.joinToString("") { "[$it]\n" }
    }

    private fun pastEnd(path: Path, lines: Int, offset: Int, truncated: Boolean): String = if (truncated) {
        "Offset $offset lies past the first ${MAX_READ_BYTES / MIB} MiB of $path, which is all fs.read reads."
    } else {
        "$path has $lines lines, so offset $offset lies past its end."
    }
}

/** The most lines that `fs.read` returns. */
internal const val MAX_LINES = 2000

/** The characters of a line that `fs.read` returns. */
internal const val MAX_LINE_CHARS = 2000

/** The most characters of lines that one call returns. */
internal const val MAX_OUTPUT_CHARS = 100_000

internal const val MIB: Long = 1024 * 1024

private const val DRAIN_BUFFER_BYTES = 64 * 1024

/** The bytes of a file that `fs.read` reads at most. */
internal const val MAX_READ_BYTES: Long = 64L * MIB

/** The leading bytes of a file that `fs.read` sniffs for an image's magic bytes. */
private const val IMAGE_SNIFF_BYTES = 32
