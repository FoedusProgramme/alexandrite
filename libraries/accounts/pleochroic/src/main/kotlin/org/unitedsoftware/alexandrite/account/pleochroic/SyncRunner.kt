package org.unitedsoftware.alexandrite.account.pleochroic

/** What one sync run did. */
public class SyncReport(
    public val pushed: Int = 0,
    public val pulled: Int = 0,
    public val deletedRemote: Int = 0,
    public val deletedLocal: Int = 0,
    public val merged: Int = 0,
    public val conflicts: Int = 0,
    public val skippedProfiles: Int = 0,
)

/**
 * Runs one sync: reads the change feed from [SyncState.cursor], decides every tracked item with [SyncEngine], and
 * applies it through [local] (config, secrets, instructions, skills) and [transport] (pushes, remote deletes).
 */
public class SyncRunner(private val local: SyncLocal, private val transport: SyncTransport) {
    public suspend fun run(state: SyncState): Pair<SyncReport, SyncState> {
        val engine = SyncEngine(state.policy)
        val page = transport.changes(state.cursor)
        val remote = page.changes.filterNot { it.kind == "profile" }.associateBy { syncKey(it.area, it.kind, it.id) }
        val skippedProfiles = page.changes.count { it.kind == "profile" }

        val localItems = local.collect().associateBy { it.key }
        val localChanges = mutableListOf<LocalChange>()
        var records = state.records
        var pushed = 0
        var pulled = 0
        var deletedRemote = 0
        var deletedLocal = 0
        var merged = 0
        var conflicts = 0

        for (key in (records.keys + remote.keys + localItems.keys)) {
            val (area, kind, id) = splitKey(key)
            val before = records[key]
            val localData = localItems[key]?.data
            val remoteState = remoteStateOf(remote[key], area, kind, id)
            val decision = engine.decide(before, localData, remoteState)
            if (decision.conflict) conflicts++
            val remoteRevNow = (remoteState as? RemoteState.Present)?.rev ?: before?.remoteRev
            when (val outcome = decision.outcome) {
                is SyncOutcome.Push -> {
                    val meta = pushOne(area, kind, id, outcome.data, remoteRevNow)
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
                    val meta = pushOne(area, kind, id, outcome.data, remoteRevNow)
                    merged++
                    records = records + (key to SyncRecord(contentHash(outcome.data), meta.rev))
                }

                SyncOutcome.Noop -> Unit
            }
        }

        if (localChanges.isNotEmpty()) local.apply(localChanges)
        val report = SyncReport(pushed, pulled, deletedRemote, deletedLocal, merged, conflicts, skippedProfiles)
        return report to state.with(cursor = page.cursor, records = records)
    }

    private suspend fun remoteStateOf(changed: ChangeEntry?, area: String, kind: String, id: String): RemoteState =
        when {
            changed == null -> RemoteState.Unchanged

            changed.deleted -> RemoteState.Deleted(changed.rev ?: "deleted")

            else -> {
                val fetched = transport.get(area, kind, id)
                if (fetched == null) {
                    RemoteState.Deleted(changed.rev ?: "deleted")
                } else {
                    val data = if (area == AREA_SECRET) unwrapSecretValue(fetched.data) else fetched.data
                    RemoteState.Present(fetched.rev, data)
                }
            }
        }

    private suspend fun pushOne(
        area: String,
        kind: String,
        id: String,
        data: kotlinx.serialization.json.JsonElement,
        ifMatch: String?,
    ): RemoteMeta = try {
        transport.put(area, kind, id, data, ifMatch)
    } catch (e: SyncConflictException) {
        // Decided already: this item's local content wins the conflict, so it overwrites what just landed too.
        transport.put(area, kind, id, data, e.current.rev)
    }

    /**
     * The inverse of [syncKey]: splits [key] on its first and last colon, since a skill's `kind` (its scope, such
     * as `agent:work`) may itself hold one, while `area` and `id` never do.
     */
    private fun splitKey(key: String): Triple<String, String, String> {
        val first = key.indexOf(':')
        val last = key.lastIndexOf(':')
        if (first < 0) return Triple(key, "", "")
        return Triple(key.substring(0, first), key.substring(first + 1, last), key.substring(last + 1))
    }
}
