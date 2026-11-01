package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.permission.Principal
import org.unitedsoftware.alexandrite.agent.permission.askedPlace
import org.unitedsoftware.alexandrite.agent.permission.denial
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.CompanionTool
import org.unitedsoftware.alexandrite.agent.tool.property
import org.unitedsoftware.alexandrite.agent.tool.review
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.io.readAtMost
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.internal.path.openRegularFile
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.internal.tool.booleanArgument
import org.unitedsoftware.alexandrite.internal.tool.stringArgument
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.io.IOException
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** Sends a file to the chat of the turn, the root turn's for a sub-agent run, through its channel. */
@Singleton
@Contribute(Tool::class)
internal class SendFileTool(private val floor: HardFloor, private val channels: ChannelDirectory) :
    ReviewedTool,
    CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "chat.send_file",
        description = "Sends a file to this chat, such as a document, an image or an archive you made or found. The " +
            "chat gets it at once, beside your reply. 'path' is absolute or relative to your workspace. An image is " +
            "shown as a picture unless 'asFile' is true. Keep the caption short: a longer text belongs in your reply.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                property("path", "string", "The file to send.")
                property("caption", "string", "A short text sent with the file.")
                property("asFile", "boolean", "Send an image as a file to download, uncompressed.")
            }
            put("required", JsonArray(listOf(JsonPrimitive("path"))))
            put("additionalProperties", false)
        },
        risk = ToolRisk.EXTERNAL_ACTION,
        activity = ToolActivity.SEND,
        titleArguments = listOf("path"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        val request = requested(arguments, context)
        val file = checked(request.path, context)
        val sha256 = runInterruptible(Dispatchers.IO) { hashed(file.path, floor.protectsAnything) }
        val untainted = (context as? AgentToolContext)?.taint?.tainted == false
        CallReview.builder()
            .preview(ReviewPreview.OutgoingFile(file.path, file.size, sha256, context.turn.chat, request.caption))
            .snapshot("$SHA256$sha256")
            .person(file.person)
            .exempt(untainted && Principal.of(context.turn) != Principal.GUEST)
            .rule(RuleSubject(definition.name, emptyList(), definition.name))
            .build()
    } catch (e: ToolRefusal) {
        e.review()
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        CallReview.builder().problem(unreadable(e)).build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = try {
        val request = requested(arguments, context)
        val file = checked(request.path, context)
        val reviewed = context.clearance?.snapshot
        val bytes = runInterruptible(Dispatchers.IO) { read(file) }
        val sha256 = sha256Hex(bytes)
        if (reviewed != null && reviewed != "$SHA256$sha256") {
            throw ToolRefusal("${file.path} changed since this call was reviewed, so it was not sent: repeat the call.")
        }
        send(request, file, bytes, sha256, context)
    } catch (e: ToolRefusal) {
        ToolResult(e.message, isError = true)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        ToolResult(unreadable(e), isError = true)
    }

    /** Sends [bytes] of [file] as [request] asks, and tells the model what reached the chat. */
    private suspend fun send(
        request: Request,
        file: CheckedFile,
        bytes: ByteArray,
        sha256: String,
        context: ToolContext,
    ): ToolResult {
        val turn = context.turn
        val chat = turn.chat
        val name = file.path.fileName.toString()
        val mediaType = mediaTypeOf(name)
        val markdown = Markup.MARKDOWN in file.capabilities.markups
        val message = OutboundMessage.builder(request.caption.orEmpty(), MessageKind.REPLY)
            .markup(if (markdown) Markup.MARKDOWN else Markup.PLAIN)
            .conversation(turn.conversation)
            .files(listOf(OutboundFile.builder(name, mediaType, bytes).asFile(request.asFile).build()))
            .build()
        val sent = "$name ($mediaType, ${bytes.size} bytes, SHA-256 $sha256)"
        return when (val delivery = delivered { file.channel.send(chat, message) }) {
            is Delivery.Delivered -> {
                logger.info("Turn {} sent {} ({} bytes, {}) to chat {}", turn.id, file.path, bytes.size, sha256, chat)
                ToolResult("Sent $sent from ${file.path} to this chat ($chat).")
            }

            is Delivery.NotDelivered -> {
                logger.warn("Turn {} could not send {} to chat {}: {}", turn.id, file.path, chat, delivery)
                val detail = delivery.detail?.let { ": ${it.lineSequence().first()}" }.orEmpty()
                ToolResult("$sent did not reach this chat ($chat): ${delivery.kind}$detail.", isError = true)
            }
        }
    }

    /** What the call asks for. */
    private suspend fun requested(arguments: JsonObject, context: ToolContext): Request {
        val path = arguments.stringArgument("path")?.takeIf { it.isNotEmpty() }
            ?: throw ToolRefusal("Give 'path', the file to send.")
        val caption = arguments.stringArgument("caption")?.takeIf { it.isNotBlank() }
        val asFile = arguments.booleanArgument("asFile") ?: false
        return Request(absolute(path, context), caption, asFile)
    }

    /** [path] checked by the floor and against what the turn's chat takes. */
    private suspend fun checked(path: Path, context: ToolContext): CheckedFile {
        val turn = context.turn
        var person: String? = null
        val file = when (val check = floor.checkRead(path, turn.agent, turn.rootConversation)) {
            is FloorCheck.Allowed -> check.canonical

            is FloorCheck.Ask -> check.canonical.also { person = askedPlace(path, check) }

            else ->
                throw ToolRefusal("$path is protected (${check.denial}): no file of it may be sent.", blocked = true)
        }
        val attributes = runInterruptible(Dispatchers.IO) {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
        when {
            attributes.isDirectory -> throw ToolRefusal("$path is a directory: send an archive of it instead.")

            !attributes.isRegularFile -> throw ToolRefusal("$path is no regular file, so it is not sent.")

            floor.protectsAnything && runInterruptible(Dispatchers.IO) { hardLinks(file) } > 1 -> throw ToolRefusal(
                "$path has more than one hard link, so it may be another name of a protected file: it is not sent.",
                blocked = true,
            )
        }
        val channel = channels.channel(turn.chat.instance)
            ?: throw ToolRefusal("The channel of this chat is not open, so no file can be sent.")
        val capabilities = channel.capabilities(turn.chat)
        if (!capabilities.files) throw ToolRefusal("This chat's channel cannot send files.")
        val limit = minOf(capabilities.maxFileBytes ?: MAX_FILE_BYTES, MAX_FILE_BYTES)
        val size = attributes.size()
        if (size > limit) {
            throw ToolRefusal("$path holds $size bytes, more than the $limit bytes a file sent to this chat may hold.")
        }
        return CheckedFile(file, size, limit, channel, capabilities, person)
    }

    /** The bytes of [file], read once. */
    private fun read(file: CheckedFile): ByteArray {
        val bytes = openRegularFile(file.path, floor.protectsAnything).use { it.readAtMost((file.limit + 1).toInt()) }
        if (bytes.size > file.limit) {
            throw ToolRefusal("${file.path} grew past the ${file.limit} bytes a file sent to this chat may hold.")
        }
        return bytes
    }

    private class Request(val path: Path, val caption: String?, val asFile: Boolean)

    private class CheckedFile(
        val path: Path,
        val size: Long,
        val limit: Long,
        val channel: Channel,
        val capabilities: ChannelCapabilities,
        /** Why only a person may allow sending the file, null when anyone may. */
        val person: String?,
    )
}

/** [argument] as an absolute path, a relative one resolved against the workspace and a leading `~` as the home. */
private suspend fun absolute(argument: String, context: ToolContext): Path {
    val path = try {
        Paths.get(expandHome(argument))
    } catch (e: InvalidPathException) {
        throw ToolRefusal("'$argument' is no valid path.")
    }
    if (path.isAbsolute) return path.normalize()
    if (context.workspace == null) {
        throw ToolRefusal(
            "'$argument' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
        )
    }
    return context.openWorkspace().resolve(path).normalize()
}

/** The SHA-256 of the bytes of [file] in lowercase hex, read without holding them. */
private fun hashed(file: Path, oneLink: Boolean): String = openRegularFile(file, oneLink).use { sha256Hex(it) }

/** The IANA media type that the file [name] has by its extension. */
internal fun mediaTypeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return MEDIA_TYPES[extension]
        ?: URLConnection.getFileNameMap().getContentTypeFor(name)
        ?: "application/octet-stream"
}

private fun unreadable(e: IOException): String = when (e) {
    is NoSuchFileException -> "Nothing exists at ${e.file}."
    else -> "The file could not be read: ${e.message ?: e.javaClass.simpleName}"
}

private val MEDIA_TYPES = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "gz" to "application/gzip",
    "tar" to "application/x-tar",
    "json" to "application/json",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "html" to "text/html",
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "mp3" to "audio/mpeg",
    "ogg" to "audio/ogg",
    "wav" to "audio/wav",
)

private const val SHA256 = "sha256:"

/** The most bytes a file sent may hold, whatever its channel takes. */
private const val MAX_FILE_BYTES = 256L * 1024 * 1024

private val logger = LoggerFactory.getLogger(SendFileTool::class.java)
