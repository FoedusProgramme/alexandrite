package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonElement

/** What one sync run did; [staged] is how many dangerous pulled changes it held back for review ([PendingSync]). */
public class SyncReport(
    public val pushed: Int = 0,
    public val pulled: Int = 0,
    public val deletedRemote: Int = 0,
    public val deletedLocal: Int = 0,
    public val merged: Int = 0,
    public val conflicts: Int = 0,
    public val skippedProfiles: Int = 0,
    public val staged: Int = 0,
)

/**
 * Runs one sync: reads the change feed from [SyncState.cursor], decides every tracked item with [SyncEngine], and
 * applies it through [local] (config, secrets, instructions, skills) and [transport] (pushes, remote deletes) —
 * except a pulled change [dangerReasons] or a never-before-synced local item's being overwritten or deleted marks
 * dangerous, which this holds in [SyncState.pending] for [PendingSync] instead of applying (the owner's decision: a
 * pull must never be able to harm the local machine on its own). [dryRun] decides nothing differently but writes
 * and stages nothing, for a caller that only wants [SyncReport]'s counts.
 */
public class SyncRunner(private val local: SyncLocal, private val transport: SyncTransport) {
    public suspend fun run(state: SyncState, dryRun: Boolean = false): Pair<SyncReport, SyncState> {
        val engine = SyncEngine(state.policy)
        val page = transport.changes(state.cursor)
        val remote = page.changes.filterNot { it.kind == "profile" }.associateBy { syncKey(it.area, it.kind, it.id) }
        val skippedProfiles = page.changes.count { it.kind == "profile" }

        val localItems = local.collect().associateBy { it.key }
        val syncedSecrets = syncedSecretsOf(localItems.values, remote.values)
        val localChanges = mutableListOf<LocalChange>()
        var records = state.records
        var pending = state.pending
        var pushed = 0
        var pulled = 0
        var deletedRemote = 0
        var deletedLocal = 0
        var merged = 0
        var conflicts = 0
        var staged = 0

        for (key in (records.keys + remote.keys + localItems.keys)) {
            val (area, kind, id) = splitKey(key)
            val before = records[key]
            val localData = localItems[key]?.data
            val remoteState = remoteStateOf(transport, remote[key], area, kind, id, syncedSecrets)
            val decision = engine.decide(before, localData, remoteState)
            if (decision.conflict) conflicts++
            val remoteRevNow = (remoteState as? RemoteState.Present)?.rev ?: before?.remoteRev
            val outcome = decision.outcome

            val reasons = if (writesLocally(outcome)) {
                val after = outcomeData(outcome)
                val unsynced = if (before == null &&
                    localData != null
                ) {
                    listOf("unsynced_local_overwrite")
                } else {
                    emptyList()
                }
                dangerReasons(area, kind, id, localData, after, syncedSecrets) + unsynced
            } else {
                emptyList()
            }

            if (reasons.isNotEmpty()) {
                staged++
                if (!dryRun) pending = pending + (key to pendingChangeOf(area, kind, id, before, remoteState, reasons))
                continue
            }
            if (!dryRun && pending.containsKey(key)) pending = pending - key
            if (dryRun) {
                when (outcome) {
                    is SyncOutcome.Push -> pushed++
                    SyncOutcome.DeleteRemote -> deletedRemote++
                    is SyncOutcome.Pull -> pulled++
                    SyncOutcome.DeleteLocal -> deletedLocal++
                    is SyncOutcome.Merged -> merged++
                    SyncOutcome.Noop -> Unit
                }
                continue
            }

            when (outcome) {
                is SyncOutcome.Push -> {
                    val wire = if (area == AREA_CONFIG) toWireSecretRefs(outcome.data, syncedSecrets) else outcome.data
                    val meta = pushOne(transport, area, kind, id, wire, remoteRevNow)
                    pushed++
                    records = records + (key to SyncRecord(contentHash(outcome.data), meta.rev))
                }

                SyncOutcome.DeleteRemote -> {
                    remoteRevNow?.let { transport.delete(area, kind, id, it) }
                    deletedRemote++
                    records = records - key
                }

                is SyncOutcome.Pull -> {
                    localChanges += LocalChange(area, kind, id, outcome.data)
                    pulled++
                    records = records + (key to SyncRecord(contentHash(outcome.data), remoteRevNow))
                }

                SyncOutcome.DeleteLocal -> {
                    localChanges += LocalChange(area, kind, id, null)
                    deletedLocal++
                    records = records - key
                }

                is SyncOutcome.Merged -> {
                    localChanges += LocalChange(area, kind, id, outcome.data)
                    val wire = if (area == AREA_CONFIG) toWireSecretRefs(outcome.data, syncedSecrets) else outcome.data
                    val meta = pushOne(transport, area, kind, id, wire, remoteRevNow)
                    merged++
                    records = records + (key to SyncRecord(contentHash(outcome.data), meta.rev))
                }

                SyncOutcome.Noop -> Unit
            }
        }

        if (!dryRun && localChanges.isNotEmpty()) local.apply(localChanges)
        val report = SyncReport(pushed, pulled, deletedRemote, deletedLocal, merged, conflicts, skippedProfiles, staged)
        val newState = if (dryRun) state else state.with(cursor = page.cursor, records = records, pending = pending)
        return report to newState
    }
}

/** The account's own secret names, as this run knows them: locally held, or named by the change feed's page. */
internal fun syncedSecretsOf(local: Collection<LocalItem>, remote: Collection<ChangeEntry>): Set<String> = buildSet {
    local.filter { it.area == AREA_SECRET }.forEach { add(it.id) }
    remote.filter { it.area == AREA_SECRET }.forEach { add(it.id) }
}

internal fun writesLocally(outcome: SyncOutcome): Boolean =
    outcome is SyncOutcome.Pull || outcome is SyncOutcome.DeleteLocal || outcome is SyncOutcome.Merged

private fun outcomeData(outcome: SyncOutcome): JsonElement? = when (outcome) {
    is SyncOutcome.Pull -> outcome.data
    is SyncOutcome.Merged -> outcome.data
    else -> null
}

internal fun pendingChangeOf(
    area: String,
    kind: String,
    id: String,
    before: SyncRecord?,
    remoteState: RemoteState,
    reasons: List<String>,
): PendingChange = when (remoteState) {
    is RemoteState.Present -> PendingChange(area, kind, id, before, remoteState.rev, false, remoteState.data, reasons)
    is RemoteState.Deleted -> PendingChange(area, kind, id, before, remoteState.rev, true, null, reasons)
    RemoteState.Unchanged -> PendingChange(area, kind, id, before, before?.remoteRev, false, null, reasons)
}

/**
 * [changed]'s content at [area]/[kind]/[id], fetched through [transport] and, for a config document, translated
 * from the wire's `${secret:NAME}` to the local `${env:NAME}` form for every name in [syncedSecrets] (a name
 * outside it, or a literal `${env:NAME}` on the wire, is left exactly as received: [dangerReasons] reads it from
 * there to decide this is not a change to apply without review).
 */
internal suspend fun remoteStateOf(
    transport: SyncTransport,
    changed: ChangeEntry?,
    area: String,
    kind: String,
    id: String,
    syncedSecrets: Set<String>,
): RemoteState = when {
    changed == null -> RemoteState.Unchanged

    changed.deleted -> RemoteState.Deleted(changed.rev ?: "deleted")

    else -> {
        val fetched = transport.get(area, kind, id)
        if (fetched == null) {
            RemoteState.Deleted(changed.rev ?: "deleted")
        } else {
            val data = when (area) {
                AREA_SECRET -> unwrapSecretValue(fetched.data)
                AREA_CONFIG -> toLocalSecretRefs(fetched.data, syncedSecrets)
                else -> fetched.data
            }
            RemoteState.Present(fetched.rev, data)
        }
    }
}

internal suspend fun pushOne(
    transport: SyncTransport,
    area: String,
    kind: String,
    id: String,
    data: JsonElement,
    ifMatch: String?,
): RemoteMeta = try {
    transport.put(area, kind, id, data, ifMatch)
} catch (e: SyncConflictException) {
    // Decided already: this item's local content wins the conflict, so it overwrites what just landed too.
    transport.put(area, kind, id, data, e.current.rev)
}

/**
 * The inverse of [syncKey]: splits [key] on its first and last colon, since a skill's `kind` (its scope, such as
 * `agent:work`) may itself hold one, while `area` and `id` never do.
 */
internal fun splitKey(key: String): Triple<String, String, String> {
    val first = key.indexOf(':')
    val last = key.lastIndexOf(':')
    if (first < 0) return Triple(key, "", "")
    return Triple(key.substring(0, first), key.substring(first + 1, last), key.substring(last + 1))
}
