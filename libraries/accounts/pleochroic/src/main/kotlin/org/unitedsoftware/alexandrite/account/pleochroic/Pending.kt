package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One [PendingChange] alongside [local], its item's current local content (null when there is none): a fresh read
 * for display, not the [local] a run saw when it staged the change, so a review always shows where things stand
 * now, including a local edit made since.
 */
public class PendingReview(public val key: String, public val change: PendingChange, public val local: JsonElement?) {
    /** A short, human-readable line describing what applying this change would do and why it was held back. */
    public val summary: String
        get() = buildString {
            append(change.area)
            if (change.kind.isNotEmpty()) append(' ').append(change.kind)
            append(' ').append(change.id)
            append(if (change.deleted) ": the account deleted it" else ": the account changed it")
            if (change.reasons.isNotEmpty()) append(" — ").append(change.reasons.joinToString(", "))
        }

    /** The top-level keys [local] and [change]'s remote content disagree on, for an object document's diff. */
    public val changedKeys: Set<String>
        get() {
            val before = local as? JsonObject
            val after = change.remoteData as? JsonObject
            if (before == null && after == null) return emptySet()
            val keys = (before?.keys.orEmpty() + after?.keys.orEmpty())
            return keys.filterTo(mutableSetOf()) { before?.get(it) != after?.get(it) }
        }
}

/** What [PendingSync.apply] did with the pending changes it resolved. */
public class PendingApplyReport(
    public val applied: Int = 0,
    public val pushed: Int = 0,
    public val deletedRemote: Int = 0,
    public val conflicts: Int = 0,
)

/**
 * Reviews, applies or discards the dangerous pulled changes [SyncRunner.run] held in [SyncState.pending] instead of
 * applying. [apply] re-decides each one against the item's current local content with the same [SyncEngine] a run
 * uses, so a local edit made after the change was staged is not silently lost — it is merged or wins outright by
 * [SyncState.policy], exactly as a sync run would have decided it, not overwritten by a stale snapshot.
 */
public class PendingSync(private val local: SyncLocal, private val transport: SyncTransport) {
    /** [state]'s pending changes, each alongside its item's current local content for a review to show a diff. */
    public suspend fun review(state: SyncState): List<PendingReview> {
        val currentLocal = local.collect().associateBy { it.key }
        return state.pending.map { (key, change) -> PendingReview(key, change, currentLocal[key]?.data) }
    }

    /** Applies [keys] (every pending change when null), each re-decided against current local content. */
    public suspend fun apply(state: SyncState, keys: Set<String>? = null): Pair<PendingApplyReport, SyncState> {
        val engine = SyncEngine(state.policy)
        val targets = state.pending.keys.filter { keys == null || it in keys }
        val currentLocal = local.collect().associateBy { it.key }
        val syncedSecrets = syncedSecretsOf(currentLocal.values, emptyList())
        val localChanges = mutableListOf<LocalChange>()
        var records = state.records
        var pending = state.pending
        var applied = 0
        var pushed = 0
        var deletedRemote = 0
        var conflicts = 0

        for (key in targets) {
            val change = pending[key] ?: continue
            val (area, kind, id) = splitKey(key)
            val localNow = currentLocal[key]?.data
            val remoteState = remoteStateOf(change) ?: continue
            val decision = engine.decide(change.before, localNow, remoteState)
            if (decision.conflict) conflicts++
            val remoteRevNow = (remoteState as? RemoteState.Present)?.rev ?: change.before?.remoteRev
            when (val outcome = decision.outcome) {
                is SyncOutcome.Push -> {
                    val wire = if (area == AREA_CONFIG) toWireSecretRefs(outcome.data, syncedSecrets) else outcome.data
                    val meta = pushOne(transport, area, kind, id, wire, remoteRevNow)
                    records = records + (key to SyncRecord(contentHash(outcome.data), meta.rev))
                    pushed++
                }

                SyncOutcome.DeleteRemote -> {
                    remoteRevNow?.let { transport.delete(area, kind, id, it) }
                    records = records - key
                    deletedRemote++
                }

                is SyncOutcome.Pull -> {
                    localChanges += LocalChange(area, kind, id, outcome.data)
                    records = records + (key to SyncRecord(contentHash(outcome.data), remoteRevNow))
                    applied++
                }

                SyncOutcome.DeleteLocal -> {
                    localChanges += LocalChange(area, kind, id, null)
                    records = records - key
                    applied++
                }

                is SyncOutcome.Merged -> {
                    localChanges += LocalChange(area, kind, id, outcome.data)
                    val wire = if (area == AREA_CONFIG) toWireSecretRefs(outcome.data, syncedSecrets) else outcome.data
                    val meta = pushOne(transport, area, kind, id, wire, remoteRevNow)
                    records = records + (key to SyncRecord(contentHash(outcome.data), meta.rev))
                    applied++
                }

                SyncOutcome.Noop -> Unit
            }
            pending = pending - key
        }
        if (localChanges.isNotEmpty()) local.apply(localChanges)
        val report = PendingApplyReport(applied, pushed, deletedRemote, conflicts)
        return report to state.with(records = records, pending = pending)
    }

    /**
     * Discards [keys] (every pending change when null): the pull is not applied, and the item's record is set to
     * its current local content against the change's remote revision, so the same remote version is not staged
     * again, but a genuinely new remote change still will be.
     */
    public suspend fun discard(state: SyncState, keys: Set<String>? = null): SyncState {
        val targets = state.pending.keys.filter { keys == null || it in keys }
        val currentLocal = local.collect().associateBy { it.key }
        var records = state.records
        var pending = state.pending
        for (key in targets) {
            val change = pending[key] ?: continue
            val localHash = currentLocal[key]?.data?.let(::contentHash)
            records = if (change.deleted && localHash == null) {
                records - key
            } else {
                records + (key to SyncRecord(localHash, change.remoteRev))
            }
            pending = pending - key
        }
        return state.with(records = records, pending = pending)
    }

    private fun remoteStateOf(change: PendingChange): RemoteState? = when {
        change.deleted -> RemoteState.Deleted(change.remoteRev ?: "deleted")
        change.remoteData != null -> RemoteState.Present(change.remoteRev ?: return null, change.remoteData)
        else -> null
    }
}
