package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.path.hardLinks
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** What [block] returns, or the failed result that a [ToolRefusal] or an I/O error makes. */
internal suspend fun refusing(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: ToolRefusal) {
    ToolResult(e.message, isError = true)
} catch (e: IOException) {
    currentCoroutineContext().ensureActive()
    ToolResult(failure(e), isError = true)
}

/** What a file tool says of the I/O error [e]. */
internal fun failure(e: IOException): String = when (e) {
    is NoSuchFileException -> "Nothing exists at ${e.file}."
    is AccessDeniedException -> "${e.file} cannot be read: access denied."
    else -> "A file could not be read: ${e.message ?: e.javaClass.simpleName}"
}

/** What [block] returns, run where it may block. */
internal suspend fun <T> blocking(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

/** [argument] as an absolute path: a relative one resolved against the workspace, a leading `~` as the home directory. */
internal suspend fun ToolContext.absolute(argument: String): Path {
    val path = pathOf(argument) ?: throw ToolRefusal("'$argument' is no valid path.")
    return resolved(path) { if (workspace == null) null else openWorkspace() } ?: throw ToolRefusal(
        "'$argument' is a relative path, and you have no workspace to resolve it against: give an absolute path.",
    )
}

/** The path [argument] names, a leading `~` as the home directory, null when it is no valid path. */
private fun pathOf(argument: String): Path? = try {
    Paths.get(expandHome(argument))
} catch (e: InvalidPathException) {
    null
}

/** [path] made absolute against the [workspace], null when it is relative and there is no workspace. */
private inline fun resolved(path: Path, workspace: () -> Path?): Path? =
    (if (path.isAbsolute) path else workspace()?.resolve(path))?.normalize()

/** Where a search goes: [argument] as an absolute path, or the workspace when it is null or empty. */
internal suspend fun ToolContext.searched(argument: String?): Path {
    if (!argument.isNullOrEmpty()) return absolute(argument)
    if (workspace == null) throw ToolRefusal("Give 'path': you have no workspace to search by default.")
    return openWorkspace()
}

/** [path] made canonical for the call of [context], refused when it lies in a protected place. */
internal fun HardFloor.allowed(path: Path, context: ToolContext): Path = readRoot(path, context).canonical

/** [path] made canonical as by [allowed], and whether the floor asks a person about it. */
internal fun HardFloor.readRoot(path: Path, context: ToolContext): ReadRoot =
    when (val check = checkRead(path, context.turn.agent, context.turn.rootConversation)) {
        is FloorCheck.Allowed -> ReadRoot(check.canonical, false)
        is FloorCheck.Ask -> ReadRoot(check.canonical, true)
        else -> throw ToolRefusal("$path is protected (${check.denial}): no file tool may read it.")
    }

/** A canonical path that a read starts at, [asked] when a person allowed it as the floor asks one. */
internal class ReadRoot(val canonical: Path, val asked: Boolean)

/** Why only a person may allow a call that reaches [path], which the floor [check] asks a person about. */
internal fun askedPlace(path: Path, check: FloorCheck.Ask): String =
    "$path is in a place the floor asks a person about (${check.reason})"

/** The review of a call that reads [argument], the workspace when it is null or empty, asking where the floor asks. */
internal suspend fun HardFloor.readReview(argument: String?, context: ToolContext): CallReview {
    val path = when {
        argument.isNullOrEmpty() -> context.workspace?.normalize()
        else -> pathOf(argument)?.let { path -> resolved(path) { context.workspace } }
    } ?: return CallReview.builder().build()
    val check = blocking { checkRead(path, context.turn.agent, context.turn.rootConversation) }
    return CallReview.builder()
        .person((check as? FloorCheck.Ask)?.let { askedPlace(path, it) })
        .reads(listOfNotNull(path.takeIf { it.isAbsolute }))
        .build()
}

/** The attributes of [path] itself, a link not followed. */
internal fun attributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

/** Refuses [file], shown as [shown], unless it is a regular file, with one hard link when [oneLink]. */
internal fun checkReadable(file: Path, shown: Path, attributes: BasicFileAttributes, oneLink: Boolean) {
    if (attributes.isDirectory) throw ToolRefusal("$shown is a directory: list it with fs.glob.")
    checkRegular(file, shown, attributes, "read", oneLink)
}

/**
 * Refuses [file], shown as [shown], unless it is a regular file, with one hard link when [oneLink], which a file tool
 * then [verb].
 */
internal fun checkRegular(file: Path, shown: Path, attributes: BasicFileAttributes, verb: String, oneLink: Boolean) {
    when {
        !attributes.isRegularFile -> throw ToolRefusal("$shown is no regular file, so it is not $verb.")

        oneLink && hardLinks(file) > 1 -> throw ToolRefusal.blocked(
            "$shown has more than one hard link, so it may be another name of a protected file: it is not $verb.",
        )
    }
}

/** A JSON Schema object of [properties], of which [required] must be given. */
internal fun schema(vararg required: String, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    put("additionalProperties", false)
}

internal fun JsonObjectBuilder.property(name: String, type: String, description: String) {
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }
}

/** Why the floor refused, as [FloorCheck.Denied] says, or that it decided as this version does not know. */
internal val FloorCheck.denial: String
    get() = (this as? FloorCheck.Denied)?.reason ?: "a decision of the floor that this version does not know"
