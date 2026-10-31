package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Offers `pleochroic.sync` where the host names its config file, which a sync reads and writes; whether an account is
 * signed in is read at each call, so a later `alexandrite login pleochroic` takes effect without a restart.
 */
@Singleton
@Contribute(ToolSource::class)
internal class PleochroicTools(hostConfig: HostConfig, paths: HostPaths) : ToolSource {
    override val namespace: String = "pleochroic"

    private val offered = MutableStateFlow(
        paths.configFile?.let { configFile ->
            val secrets = configFile.toAbsolutePath().resolveSibling(SECRETS_FILE)
            val tool = SyncTool(
                HostConfigSyncLocal(hostConfig, configFile, secrets),
                SyncStateFile.inDataDirectory(paths.dataRoot),
                AccountTransports(hostConfig, secrets),
            )
            listOf(tool.asTool())
        }.orEmpty(),
    )

    override val tools: StateFlow<List<Tool>> = offered.asStateFlow()
}

/** Opens a [SyncTransport] for one call and closes it after. */
internal interface SyncTransports {
    suspend fun <T> using(block: suspend (SyncTransport) -> T): T
}

/**
 * Reaches the account that the config's [SIGNED_IN_PATH] names now, its key's `${'$'}{env:NAME}` read from the
 * environment, then [secrets], as the CLI reads it.
 */
private class AccountTransports(private val hostConfig: HostConfig, private val secrets: Path) : SyncTransports {
    override suspend fun <T> using(block: suspend (SyncTransport) -> T): T {
        val (apiBase, key) = signedIn() ?: throw Refused(NOT_SIGNED_IN)
        return HttpTransport(HttpTimeouts(10.seconds, 30.seconds, 30.seconds)).use {
            block(HttpSyncTransport(it, apiBase, key))
        }
    }

    private suspend fun signedIn(): Pair<String, String>? {
        val endpoint = hostConfig.get(SIGNED_IN_PATH) as? JsonObject ?: return null
        val baseUrl = endpoint.string("baseUrl") ?: return null
        val rawKey = endpoint.string("apiKey")?.takeIf { it != HostConfig.HIDDEN_SECRET } ?: return null
        val name = envReferenceName(rawKey) ?: return baseUrl to rawKey
        val key = System.getenv(name) ?: try {
            readSecrets(secrets)[name]
        } catch (e: IOException) {
            null
        }
        return key?.let { baseUrl to it }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * The `pleochroic.sync` tool: the running app's `alexandrite sync status`, `sync now` and `sync apply`, through the
 * same state file, so either side picks up where the other left off.
 *
 * `run` is an external action that the permission mode decides like any other, with the would-be counts of a dry run
 * shown to whoever approves it; only a person may allow it in a tainted conversation. `apply` takes in the changes a
 * run held back because they could harm this machine (an MCP server, a provider endpoint, an agent's permissions, a
 * skill), so only a person may allow it, whatever the mode. Turning sync on or off stays with the operator's CLI.
 */
internal class SyncTool(
    local: SyncLocal,
    private val stateFile: SyncStateFile,
    private val transports: SyncTransports,
) {
    private val local = NoHiddenSecrets(local)
    private val lock = Mutex()

    fun asTool(): Tool = object : ReviewedTool {
        override val definition: ToolDefinition get() = this@SyncTool.definition

        override suspend fun review(arguments: JsonObject, context: ToolContext) =
            this@SyncTool.review(arguments, context)

        override suspend fun execute(arguments: JsonObject, context: ToolContext) =
            this@SyncTool.execute(arguments, context)
    }

    val definition: ToolDefinition = ToolDefinition(
        name = "pleochroic.sync",
        description = "Syncs Alexandrite's config, secrets and skills with the signed-in Pleochroic account, as an " +
            "operator's `alexandrite sync` does. `status` tells whether sync is on and lists the pulled changes " +
            "held for review. `run` syncs both ways once: whoever approves it sees what it would push, pull and " +
            "delete. `apply` takes in held changes, by their keys or all of them, and only a person may allow it. " +
            "Sync is turned on and off by an operator with `alexandrite sync enable` and `disable`.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { ACTIONS.forEach { add(JsonPrimitive(it)) } }
                    put("description", "status, run or apply.")
                }
                putJsonObject("keys") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "For apply: the keys of the held changes, as status lists them. Default all.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("action")) }
            put("additionalProperties", false)
        },
        risk = ToolRisk.EXTERNAL_ACTION,
        timeout = 2.minutes,
        activity = ToolActivity.SEND,
        titleArguments = listOf("action"),
    )

    suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = reviewing {
        when (action(arguments)) {
            STATUS -> CallReview.builder().risk(ToolRisk.READ_ONLY).build()

            RUN -> {
                val state = enabledState()
                val (report, _) = transports.using { SyncRunner(local, it).run(state, dryRun = true) }
                CallReview.builder()
                    .preview(ReviewPreview.Text(runPreview(report)))
                    .snapshot(snapshot(state))
                    .person(if (context.tainted) TAINTED_RUN else null)
                    .build()
            }

            else -> {
                val state = enabledState()
                val keys = keys(arguments, state)
                val reviews = transports.using { PendingSync(local, it).review(state) }
                    .filter { keys == null || it.key in keys }
                val preview = applyPreview(reviews)
                if (preview.length > MAX_SHOWN_CHARS) {
                    throw Refused("The held changes are too long for a person to review at once: name fewer keys.")
                }
                CallReview.builder()
                    .preview(ReviewPreview.Text(preview))
                    .snapshot(snapshot(state))
                    .person(APPLY_PERSON)
                    .build()
            }
        }
    }

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = answering {
        when (action(arguments)) {
            STATUS -> ToolResult(status(stateFile.read()))

            RUN -> lock.withLock {
                val state = cleared(context, if (context.tainted) TAINTED_RUN else null)
                val (report, next) = transports.using { SyncRunner(local, it).run(state) }
                saved(next, "Synced: ${counts(report)}.${held(report)}")
            }

            else -> lock.withLock {
                val state = cleared(context, APPLY_PERSON)
                val keys = keys(arguments, state)
                val (report, next) = transports.using { PendingSync(local, it).apply(state, keys) }
                saved(
                    next,
                    "Applied ${report.applied}, pushed ${report.pushed}, deleted ${report.deletedRemote} from the " +
                        "account${if (report.conflicts > 0) ", with ${report.conflicts} conflicts merged" else ""}.",
                )
            }
        }
    }

    private fun enabledState(): SyncState {
        val state = stateFile.read()
        if (!state.enabled) throw Refused(SYNC_OFF)
        return state
    }

    /**
     * The state to act on, refused unless it is the one the call's review saw and, where [person] says only a person
     * may allow the call, a person did.
     */
    private fun cleared(context: ToolContext, person: String?): SyncState {
        val clearance = context.clearance
        val reviewed = clearance?.snapshot ?: throw Refused("This call was not reviewed, so it changes nothing.")
        val state = enabledState()
        if (reviewed != snapshot(state)) {
            throw Refused("Sync ran or changed since this call was reviewed: repeat the call if it still applies.")
        }
        if (person != null && clearance.kind != ClearanceKind.HUMAN) {
            throw Refused("${person.replaceFirstChar(Char::uppercaseChar)}, and no person allowed it.")
        }
        return state
    }

    private fun saved(next: SyncState, done: String): ToolResult = try {
        stateFile.write(next)
        ToolResult(done)
    } catch (e: IOException) {
        logger.warn("The sync state could not be written to {}: {}", stateFile.file, e.toString())
        ToolResult(
            "$done But its record could not be written to ${stateFile.file} (${e.message ?: e}), so the next sync " +
                "repeats it: tell an operator.",
            isError = true,
        )
    }

    private suspend fun status(state: SyncState): String = buildString {
        if (!state.enabled) return SYNC_OFF
        append("Sync is on, resolving conflicts by ").append(state.policy.id).append(", with ")
            .append(state.records.size).append(" items in sync.")
        if (state.pending.isEmpty()) {
            append(" No pulled change is held for review.")
            return@buildString
        }
        append(" Held for review, which apply takes in once a person allows it:")
        val reviews = transports.using { PendingSync(local, it).review(state) }
        for (review in reviews) append("\n- ").append(review.key).append(": ").append(review.summary)
    }

    private fun keys(arguments: JsonObject, state: SyncState): Set<String>? {
        val given = arguments["keys"]
        if (given == null || given == JsonNull) {
            if (state.pending.isEmpty()) {
                throw Refused(
                    "No pulled change is held for review: there is nothing to apply.",
                )
            }
            return null
        }
        val keys = (given as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.takeIf { list -> list.isNotEmpty() && list.all { it != null } }?.filterNotNull()?.toSet()
            ?: throw Refused("'keys' must be a list of the held changes' keys, as status lists them.")
        keys.firstOrNull {
            it !in state.pending
        }?.let { throw Refused("No held change has the key '$it': see status.") }
        return keys
    }

    private fun action(arguments: JsonObject): String {
        val action = (arguments["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (action !in ACTIONS) throw Refused("Give 'action': one of ${ACTIONS.joinToString()}.")
        return action!!
    }

    /** The review that [block] makes, a problem for what it refuses. */
    private suspend fun reviewing(block: suspend () -> CallReview): CallReview = try {
        block()
    } catch (e: Refused) {
        CallReview.builder().problem(e.message).build()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CallReview.builder().problem(failure(e)).build()
    }

    /** What [block] returns, or the failed result of what stopped it. */
    private suspend fun answering(block: suspend () -> ToolResult): ToolResult = try {
        block()
    } catch (e: Refused) {
        ToolResult(e.message, isError = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult(failure(e), isError = true)
    }

    private fun failure(e: Exception): String = when (e) {
        is HiddenSecretException -> e.message
        is SyncServiceException -> "The Pleochroic service answered: ${e.message}"
        is SyncConflictException -> "The account changed during the sync: run it again."
        is ConfigEditException -> "The config file refused a change of the sync: ${e.message ?: e}"
        is IOException -> "The Pleochroic service could not be reached: ${e.message ?: e}"
        else -> throw e
    }
}

/**
 * The [SyncLocal] a running app syncs through: its [HostConfig] shows a secret written as a value in place of an
 * `${'$'}{env:NAME}` reference as [HostConfig.HIDDEN_SECRET], which a sync would push as if it were the value, so it
 * refuses to read such a config document at all.
 */
private class NoHiddenSecrets(private val local: SyncLocal) : SyncLocal {
    override suspend fun collect(): List<LocalItem> = local.collect().onEach { item ->
        val data = item.data
        if (item.area == AREA_CONFIG && data != null && holdsHiddenSecret(data)) {
            throw HiddenSecretException(item.key)
        }
    }

    override suspend fun apply(changes: List<LocalChange>) = local.apply(changes)
}

private class HiddenSecretException(key: String) :
    Exception(
        "The config file writes a secret of $key as its value rather than as an \${env:NAME} reference, which the " +
            "running app cannot read, so it does not sync. An operator moves the secret into secrets.env, or runs " +
            "`alexandrite sync now`.",
    ) {
    override val message: String get() = super.message!!
}

private fun holdsHiddenSecret(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.values.any(::holdsHiddenSecret)
    is JsonArray -> element.any(::holdsHiddenSecret)
    is JsonPrimitive -> element.isString && element.content == HostConfig.HIDDEN_SECRET
}

/** Ends a call with [message] for the model. */
private class Refused(override val message: String) : Exception(message)

private fun runPreview(report: SyncReport): String =
    "Sync with the Pleochroic account once: ${counts(report)}.${held(report)}"

private fun counts(report: SyncReport): String = buildString {
    append("push ").append(report.pushed).append(", pull ").append(report.pulled)
        .append(", delete ").append(report.deletedRemote).append(" from the account and ")
        .append(report.deletedLocal).append(" here")
    if (report.merged > 0) append(", merge ").append(report.merged)
}

private fun held(report: SyncReport): String =
    if (report.staged > 0) " ${report.staged} pulled changes are held for review." else ""

private fun applyPreview(reviews: List<PendingReview>): String = buildString {
    append("Take in ").append(reviews.size).append(" held changes from the Pleochroic account:")
    for (review in reviews) {
        append("\n\n").append(review.key).append(": ").append(review.summary)
        review.changedKeys.takeIf { it.isNotEmpty() }?.let { append("\nChanged: ").append(it.joinToString()) }
        for (file in review.skillFiles) {
            append("\n").append(file.path).append(": ").append(file.status.name.lowercase())
            file.textDiff?.let { append("\n").append(it) }
        }
    }
}

/** What a call compares before it acts: the sync state as its review found it. */
private fun snapshot(state: SyncState): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(encodeSyncState(state).toByteArray(Charsets.UTF_8))
    return "sync:" + digest.joinToString("") { "%02x".format(it) }
}

private const val STATUS = "status"
private const val RUN = "run"
private const val APPLY = "apply"
private val ACTIONS = listOf(STATUS, RUN, APPLY)

/** The endpoint that `alexandrite login pleochroic` writes and `logout` removes. */
private const val SIGNED_IN_PATH = "providers.pleochroic.endpoints.default"

/** The file beside the config file that its `${'$'}{env:NAME}` references also read, as the CLI's. */
private const val SECRETS_FILE = "secrets.env"

private const val NOT_SIGNED_IN = "No Pleochroic account is signed in: an operator signs in with " +
    "`alexandrite login pleochroic`."

private const val SYNC_OFF = "Sync with the Pleochroic account is off: an operator turns it on with " +
    "`alexandrite sync enable`."

private const val TAINTED_RUN =
    "the conversation took in untrusted content, and in such a conversation only a person may sync"

private const val APPLY_PERSON =
    "a held change is one that could harm this machine, which only a person may take in"

/** The characters of held changes that a person reviews at most. */
private const val MAX_SHOWN_CHARS = 12_000

private val logger = LoggerFactory.getLogger(SyncTool::class.java)
