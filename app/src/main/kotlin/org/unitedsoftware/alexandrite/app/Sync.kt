package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.account.pleochroic.HostConfigSyncLocal
import org.unitedsoftware.alexandrite.account.pleochroic.HttpSyncTransport
import org.unitedsoftware.alexandrite.account.pleochroic.PendingReview
import org.unitedsoftware.alexandrite.account.pleochroic.PendingSync
import org.unitedsoftware.alexandrite.account.pleochroic.SyncConflictException
import org.unitedsoftware.alexandrite.account.pleochroic.SyncPolicy
import org.unitedsoftware.alexandrite.account.pleochroic.SyncReport
import org.unitedsoftware.alexandrite.account.pleochroic.SyncRunner
import org.unitedsoftware.alexandrite.account.pleochroic.SyncServiceException
import org.unitedsoftware.alexandrite.account.pleochroic.SyncState
import org.unitedsoftware.alexandrite.account.pleochroic.decodeSyncState
import org.unitedsoftware.alexandrite.account.pleochroic.encodeSyncState
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import org.unitedsoftware.alexandrite.runtime.config.writtenConfig
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.time.Duration.Companion.seconds

/** The id of the one Pleochroic endpoint `pleochroic login`/`logout` manage (`PleochroicCommands.kt`). */
private const val SIGNED_IN_ENDPOINT = "default"
private const val PROVIDERS_PATH = "providers.pleochroic.endpoints"

internal fun requireSyncPolicy(text: String): SyncPolicy =
    SyncPolicy.of(text) ?: throw UsageException("usage.sync_policy", "value" to text)

internal fun syncEnable(cli: Cli, policy: SyncPolicy): Int {
    credentials(cli) ?: return cli.fail(cli.t("sync.not_signed_in"))
    val state = readSyncState(cli)
    if (state.enabled) return cli.fail(cli.t("sync.already_enabled"))
    writeSyncState(cli, state.with(enabled = true, policy = policy))
    cli.ui.good(cli.t("sync.enabled", "policy" to policy.id))
    cli.say(cli.t("sync.run_now_hint"))
    return ExitCode.OK
}

internal fun syncDisable(cli: Cli): Int {
    val state = readSyncState(cli)
    if (!state.enabled) return cli.done(cli.t("sync.already_disabled"))
    writeSyncState(cli, state.with(enabled = false))
    return cli.done(cli.t("sync.disabled"))
}

internal fun syncNow(cli: Cli, json: Boolean, dryRun: Boolean = false): Int {
    val (apiBase, key) = credentials(cli) ?: return cli.fail(cli.t("sync.not_signed_in"))
    val state = readSyncState(cli)
    if (!state.enabled) return cli.fail(cli.t("sync.not_enabled"))
    val local = HostConfigSyncLocal(CliHostConfig(cli), cli.locations.configFile, cli.secrets)
    val http = HttpTransport(HttpTimeouts(10.seconds, 30.seconds, 30.seconds))
    val (report, next) = try {
        http.use { transport ->
            runBlocking { SyncRunner(local, HttpSyncTransport(transport, apiBase, key)).run(state, dryRun) }
        }
    } catch (e: SyncServiceException) {
        return cli.fail(cli.t("sync.unreachable", "reason" to e.message))
    } catch (e: SyncConflictException) {
        return cli.fail(cli.t("sync.unreachable", "reason" to "conflict"))
    } catch (e: ConfigEditException) {
        return cli.fail(cli.t("sync.config_unwritable"))
    }
    if (!dryRun) writeSyncState(cli, next)
    if (json) {
        cli.printJson(reportJson(report, next))
        return ExitCode.OK
    }
    printReport(cli, report, dryRun)
    return ExitCode.OK
}

internal fun syncStatus(cli: Cli, json: Boolean): Int {
    val state = readSyncState(cli)
    val signedIn = credentials(cli) != null
    if (json) {
        cli.printJson(
            buildJsonObject {
                put("enabled", state.enabled)
                put("signedIn", signedIn)
                put("policy", state.policy.id)
                put("cursor", state.cursor)
                put("trackedItems", state.records.size)
                put("pending", state.pending.size)
            },
        )
        return ExitCode.OK
    }
    if (!signedIn) return cli.done(cli.t("sync.not_signed_in"))
    cli.out.println(
        cli.t(
            if (state.enabled) "sync.status_enabled" else "sync.status_disabled",
            "policy" to state.policy.id,
            "items" to state.records.size,
        ),
    )
    if (state.pending.isNotEmpty()) cli.say(cli.t("sync.pending_hint", "count" to state.pending.size))
    return ExitCode.OK
}

/** `sync review`: every pending change, with its current local content, as a reviewable summary and diff. */
internal fun syncReview(cli: Cli, json: Boolean): Int = withPendingSync(cli) { pending, state ->
    val reviews = runBlocking { pending.review(state) }
    if (json) {
        cli.printJson(buildJsonObject { put("pending", buildJsonArray { reviews.forEach { add(reviewJson(it)) } }) })
        return@withPendingSync ExitCode.OK
    }
    if (reviews.isEmpty()) return@withPendingSync cli.done(cli.t("sync.review_none"))
    for (review in reviews) {
        cli.ui.summary(
            review.key,
            listOfNotNull(
                cli.t("sync.review_summary") to review.summary,
                review.changedKeys.takeIf { it.isNotEmpty() }
                    ?.let { cli.t("sync.review_changed") to it.joinToString(", ") },
            ),
        )
    }
    ExitCode.OK
}

/** `sync apply [--all] [<key>...]`: applies the pending changes named, re-deciding each against current local
 * content so a local edit made since staging is not silently lost. */
internal fun syncApply(cli: Cli, keys: Set<String>?): Int = withPendingSync(cli) { pending, state ->
    if (keys != null) checkKnownPendingKeys(cli, state, keys)?.let { return@withPendingSync it }
    val (report, next) = runBlocking { pending.apply(state, keys) }
    writeSyncState(cli, next)
    cli.ui.good(
        cli.t(
            "sync.apply_done",
            "applied" to report.applied,
            "pushed" to report.pushed,
            "deletedRemote" to report.deletedRemote,
        ),
    )
    if (report.conflicts > 0) {
        cli.say(cli.t("sync.conflicts", "merged" to report.conflicts, "conflicts" to report.conflicts))
    }
    ExitCode.OK
}

/** `sync discard [--all] [<key>...]`: does not apply the named pending changes, and does not stage the same
 * remote revision again. */
internal fun syncDiscard(cli: Cli, keys: Set<String>?): Int = withPendingSync(cli) { pending, state ->
    if (keys != null) checkKnownPendingKeys(cli, state, keys)?.let { return@withPendingSync it }
    val count = keys?.size ?: state.pending.size
    val next = runBlocking { pending.discard(state, keys) }
    writeSyncState(cli, next)
    cli.done(cli.t("sync.discard_done", "count" to count))
}

/**
 * The signed-in [PendingSync] (its own [HttpTransport], so [block] can push back a change that, re-decided, turns
 * out local after all) and current [SyncState], for [block]; failures are reported the same way [syncNow]'s are.
 */
private fun withPendingSync(cli: Cli, block: (PendingSync, SyncState) -> Int): Int {
    val (apiBase, key) = credentials(cli) ?: return cli.fail(cli.t("sync.not_signed_in"))
    val state = readSyncState(cli)
    val local = HostConfigSyncLocal(CliHostConfig(cli), cli.locations.configFile, cli.secrets)
    val http = HttpTransport(HttpTimeouts(10.seconds, 30.seconds, 30.seconds))
    return try {
        http.use { transport -> block(PendingSync(local, HttpSyncTransport(transport, apiBase, key)), state) }
    } catch (e: SyncServiceException) {
        cli.fail(cli.t("sync.unreachable", "reason" to e.message))
    } catch (e: SyncConflictException) {
        cli.fail(cli.t("sync.unreachable", "reason" to "conflict"))
    } catch (e: ConfigEditException) {
        cli.fail(cli.t("sync.config_unwritable"))
    }
}

/** Null when every one of [keys] names a pending change, else a usage failure naming the first that does not. */
private fun checkKnownPendingKeys(cli: Cli, state: SyncState, keys: Set<String>): Int? {
    val unknown = keys.firstOrNull { it !in state.pending }
    return if (unknown == null) null else cli.fail(cli.t("sync.unknown_pending", "key" to unknown))
}

private fun reviewJson(review: PendingReview): JsonObject = buildJsonObject {
    put("key", review.key)
    put("area", review.change.area)
    put("kind", review.change.kind)
    put("id", review.change.id)
    put("deleted", review.change.deleted)
    put("reasons", buildJsonArray { review.change.reasons.forEach { add(JsonPrimitive(it)) } })
    put("changedKeys", buildJsonArray { review.changedKeys.forEach { add(JsonPrimitive(it)) } })
}

private fun printReport(cli: Cli, report: SyncReport, dryRun: Boolean = false) {
    val key = if (dryRun) "sync.would_do" else "sync.done"
    cli.ui.good(
        cli.t(
            key,
            "pushed" to report.pushed,
            "pulled" to report.pulled,
            "deletedRemote" to report.deletedRemote,
            "deletedLocal" to report.deletedLocal,
        ),
    )
    if (report.merged > 0 || report.conflicts > 0) {
        cli.say(cli.t("sync.conflicts", "merged" to report.merged, "conflicts" to report.conflicts))
    }
    if (report.skippedProfiles > 0) cli.say(cli.t("sync.profiles_skipped", "count" to report.skippedProfiles))
    if (report.staged > 0) cli.say(cli.t("sync.staged_hint", "count" to report.staged))
}

private fun reportJson(report: SyncReport, state: SyncState): JsonObject = buildJsonObject {
    put("pushed", report.pushed)
    put("pulled", report.pulled)
    put("deletedRemote", report.deletedRemote)
    put("deletedLocal", report.deletedLocal)
    put("merged", report.merged)
    put("conflicts", report.conflicts)
    put("skippedProfiles", report.skippedProfiles)
    put("staged", report.staged)
    put("cursor", state.cursor)
    put("pending", state.pending.size)
}

/** The signed-in Pleochroic account's `apiBase` and key, null when `pleochroic login` has not been run. */
private fun credentials(cli: Cli): Pair<String, String>? {
    val config = cli.rawConfig() ?: return null
    val endpoint = config.at("$PROVIDERS_PATH.$SIGNED_IN_ENDPOINT") as? JsonObject ?: return null
    val baseUrl = endpoint["baseUrl"].text ?: return null
    val rawKey = endpoint["apiKey"].text ?: return null
    val variable = EnvReferences.whole(rawKey)
    val key = if (variable == null) {
        rawKey
    } else {
        try {
            withSecrets(cli.environment, cli.secrets)[variable]
        } catch (e: IOException) {
            null
        }
    }
    return key?.let { baseUrl to it }
}

private val SYNC_STATE_FILE = "pleochroic-sync.json"

internal fun readSyncState(cli: Cli): SyncState {
    val text = try {
        Files.readString(cli.locations.dataDir.resolve(SYNC_STATE_FILE))
    } catch (e: NoSuchFileException) {
        return SyncState.OFF
    } catch (e: IOException) {
        return SyncState.OFF
    }
    return try {
        decodeSyncState(text)
    } catch (e: SerializationException) {
        SyncState.OFF
    }
}

internal fun writeSyncState(cli: Cli, state: SyncState) {
    val file = cli.locations.dataDir.resolve(SYNC_STATE_FILE)
    try {
        Files.createDirectories(file.parent)
        writeAtomically(file, ownerOnly = true) { it.write(encodeSyncState(state).toByteArray(Charsets.UTF_8)) }
    } catch (e: IOException) {
        cli.say(cli.t("sync.state_unwritable", "file" to "$file", "reason" to (e.message ?: "$e")))
    }
}

/**
 * The [HostConfig] the library's sync runs through: every write goes through [writeChanges], the same path
 * `alexandrite config set` uses, so a running app takes a sync's config changes live and a secret value is still
 * never written in place of a `${'$'}{env:NAME}` reference (a pulled document already names one, same as a local one).
 */
@OptIn(InternalAlexandriteApi::class)
private class CliHostConfig(private val cli: Cli) : HostConfig {
    override suspend fun get(path: String): JsonElement? {
        val config = quietConfig() ?: return null
        return if (path.isEmpty()) config else config.at(path)
    }

    override suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges {
        val recorder = Recorder(quietConfig() ?: JsonObject(emptyMap()))
        recorder.block()
        if (recorder.changes.isEmpty()) return ConfigChanges(emptyList(), emptyList())
        val written = writeChanges(cli, recorder.changes)
            ?: throw ConfigEditException(ConfigEditRefusal.IO_ERROR, "The config file refused a sync change.")
        return written.changes
    }

    override suspend fun replace(text: String): ConfigChanges = localConfig(cli).replace(text)

    private fun quietConfig(): JsonObject? = try {
        writtenConfig(Files.readString(cli.locations.configFile))
    } catch (e: IOException) {
        null
    }

    /** Records what [block] does against a snapshot, as the [ConfigChangeView]s [writeChanges] takes. */
    private class Recorder(initial: JsonObject) : ConfigEdit {
        private var current: JsonObject = initial
        val changes = mutableListOf<ConfigChangeView>()

        override fun get(path: String): JsonElement? = current.at(path)

        override fun set(path: String, value: JsonElement) {
            current = setAt(current, ConfigPath.parse(path), value)
            changes += ConfigChangeView(path, value)
        }

        override fun remove(path: String) {
            current = removeAt(current, ConfigPath.parse(path))
            changes += ConfigChangeView(path, remove = true)
        }
    }
}

private fun setAt(root: JsonObject, keys: List<String>, value: JsonElement): JsonObject {
    val key = keys.first()
    if (keys.size == 1) return JsonObject(root + (key to value))
    val child = root[key] as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject(root + (key to setAt(child, keys.drop(1), value)))
}

private fun removeAt(root: JsonObject, keys: List<String>): JsonObject {
    val key = keys.first()
    if (keys.size == 1) return JsonObject(root - key)
    val child = root[key] as? JsonObject ?: return root
    return JsonObject(root + (key to removeAt(child, keys.drop(1))))
}
