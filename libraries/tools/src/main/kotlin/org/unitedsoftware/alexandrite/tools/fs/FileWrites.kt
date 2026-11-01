package org.unitedsoftware.alexandrite.tools.fs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.path.openRegularFile
import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import org.unitedsoftware.alexandrite.internal.text.UnifiedDiff
import org.unitedsoftware.alexandrite.internal.text.Utf8Text
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.rootConversation
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The file at [file], the canonical path of what a call names as [shown], as a write tool found it. */
internal class Target(
    val file: Path,
    val shown: Path,
    /** Null when nothing exists at [file]. */
    val current: Utf8Text?,
    /** The hash of the file's bytes, or [ABSENT]. */
    val snapshot: String,
    /** Whether [file] lies in the turn's own conversation directory, where a write is never gated outside `plan` mode. */
    val exempt: Boolean,
    /** Why only a person may allow the write, null when anyone may. */
    val person: String? = null,
)

/** The snapshot of a file that does not exist. */
internal const val ABSENT = "absent"

/** The bytes of a file that the write tools change at most. */
internal const val MAX_WRITE_BYTES: Long = 16L * MIB

/** The review that [block] makes, a problem or a refusal for what it refuses. */
internal suspend fun reviewing(block: suspend () -> CallReview): CallReview = try {
    block()
} catch (e: ToolRefusal) {
    CallReview.builder().apply { if (e.blocked) refusal(e.message) else problem(e.message) }.build()
} catch (e: IOException) {
    currentCoroutineContext().ensureActive()
    CallReview.builder().problem(failure(e)).build()
}

/** The file that [argument] names for a write by the call of [context], once the floor allows it. */
internal suspend fun HardFloor.target(argument: String, context: ToolContext): Target {
    val shown = context.absolute(argument)
    val agent = context.turn.agent
    val conversation = context.turn.rootConversation
    return blocking {
        val check = checkWrite(shown, agent, conversation)
        val file = when (check) {
            is FloorCheck.Allowed -> check.canonical

            is FloorCheck.Ask -> check.canonical

            else ->
                throw ToolRefusal.blocked("$shown is protected (${check.denial}): no file tool may write it.")
        }
        val directory = context.conversationDirectory
        val person = (check as? FloorCheck.Ask)?.let { askedPlace(shown, it) }
        val exempt = file == directory || file.startsWith(directory)
        checkParents(file, shown)
        load(file, shown, exempt, person, protectsAnything)
    }
}

/** Refuses a write of [file], shown as [shown], whose nearest existing parent is no directory. */
private fun checkParents(file: Path, shown: Path) {
    var directory = file.parent
    while (directory != null && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) directory = directory.parent
    if (directory != null && !Files.isDirectory(directory)) {
        throw ToolRefusal("$directory is no directory, so $shown cannot be written.")
    }
}

/**
 * [file] (canonical), shown as [shown], as it is now, [exempt] when it lies in the conversation directory, with why
 * only a [person] may allow writing it, refused with a second hard link when [oneLink].
 */
private fun load(file: Path, shown: Path, exempt: Boolean, person: String?, oneLink: Boolean): Target {
    val attributes = try {
        attributes(file)
    } catch (e: NoSuchFileException) {
        return Target(file, shown, null, ABSENT, exempt, person)
    }
    if (attributes.isDirectory) throw ToolRefusal("$shown is a directory.")
    checkRegular(file, shown, attributes, "written", oneLink)
    val bytes = openRegularFile(file, oneLink).use { readAtMost(it, MAX_WRITE_BYTES.toInt() + 1) }
    if (bytes.size > MAX_WRITE_BYTES) {
        throw ToolRefusal("$shown is over ${MAX_WRITE_BYTES / MIB} MiB, more than the write tools change.")
    }
    val text = Utf8Text.decode(bytes)
        ?: throw ToolRefusal("$shown is no UTF-8 text (it looks binary): the write tools change text files only.")
    return Target(file, shown, text, HASH_PREFIX + sha256Hex(bytes), exempt, person)
}

/** The first [limit] bytes of [input], fewer when it ends before. */
private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(minOf(limit, BUFFER))
    while (bytes.size() < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - bytes.size()))
        if (count < 0) break
        bytes.write(buffer, 0, count)
    }
    return bytes.toByteArray()
}

/** Refuses a write of the existing [target] unless [context]'s conversation read it as it is now. */
internal fun ReadLedger.checkRead(target: Target, context: ToolContext) {
    if (target.current == null) return
    when (hash(context.turn.conversation, target.file)) {
        null -> throw ToolRefusal(
            "You have not read ${target.shown} in this conversation: read it with fs.read first, then write it.",
        )

        target.snapshot -> return

        else -> throw ToolRefusal("${target.shown} changed since you read it: read it again with fs.read first.")
    }
}

/** What an approver sees of a call that makes [target] hold [text], whose risk is [risk] unless it is null. */
internal fun Target.review(text: String, risk: ToolRisk? = null): CallReview = CallReview.builder()
    .risk(risk)
    .preview(ReviewPreview.Diff(file, diff(text).text))
    .snapshot(snapshot)
    .exempt(exempt)
    .person(person)
    .reads(listOf(file))
    .build()

/** The diff from the file's text to [text], ignoring carriage returns unless only they change. */
internal fun Target.diff(text: String): UnifiedDiff {
    val old = current?.text.orEmpty()
    val oldLabel = if (current == null) "/dev/null" else "$file"
    val plain = UnifiedDiff.of(old.replace("\r\n", "\n"), text.replace("\r\n", "\n"), oldLabel, "$file")
    return if (plain.text.isEmpty()) UnifiedDiff.of(old, text, oldLabel, "$file") else plain
}

/** Refuses the call of [context] unless [target] is as its review found it. */
internal fun Target.checkReviewed(context: ToolContext) {
    val reviewed = context.clearance?.snapshot
    if (reviewed != null && reviewed != snapshot) {
        throw ToolRefusal(
            "$shown changed since this call was reviewed: read it again with fs.read, then repeat the call.",
        )
    }
}

/** Makes [target] hold [text], records the new file in [ledger] for the call of [context] and returns the diff. */
internal suspend fun Target.write(text: String, ledger: ReadLedger, context: ToolContext): UnifiedDiff {
    val bytes = current?.encode(text) ?: text.toByteArray(Charsets.UTF_8)
    if (exempt) {
        context.conversationQuotaRefusal(file, bytes.size.toLong())?.let { throw ToolRefusal(it) }
    }
    val conversation = context.turn.conversation
    if (exempt) context.openConversationDirectory()
    blocking {
        try {
            Files.createDirectories(file.parent)
            writeAtomically(file) { it.write(bytes) }
        } catch (e: IOException) {
            val why = if (e is AccessDeniedException) "access denied" else e.message ?: e.javaClass.simpleName
            throw ToolRefusal("$shown could not be written: $why.")
        }
    }
    ledger.record(conversation, file, HASH_PREFIX + sha256Hex(bytes))
    context.touched(file)
    return diff(text)
}

/**
 * [ToolRisk.PERSISTENT_STATE] when [target] lies in the persona directory of the agent of [context]'s turn, null for
 * any other file and in a delegated run.
 */
internal fun AgentPlaces.personaRisk(target: Target, context: ToolContext): ToolRisk? {
    val turn = context.turn
    if (turn.kind == TurnKind.DELEGATED || turn.lineage != null) return null
    val directory = personaDirectory(turn.agent) ?: return null
    val real = canonicalizer.canonicalize(directory.toAbsolutePath().normalize()).real
    return ToolRisk.PERSISTENT_STATE.takeIf { canonicalizer.canonicalize(target.file).real.startsWith(real) }
}

private val canonicalizer = PathCanonicalizer()

private const val BUFFER = 64 * 1024

/** `+added -removed lines` of [diff]. */
internal fun changed(diff: UnifiedDiff): String = "+${diff.added} -${diff.removed} lines"
