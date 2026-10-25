package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The remote side of one tracked item, as one sync run found it. */
internal sealed interface RemoteState {
    /** The service holds [rev] with [data]. */
    class Present(val rev: String, val data: JsonElement) : RemoteState

    /** The service holds [rev] as a tombstone: the item once existed and was deleted there. */
    class Deleted(val rev: String) : RemoteState

    /** Not named by this run's listing or change page, so nothing changed there since the last sync. */
    data object Unchanged : RemoteState
}

/** What one sync does with a tracked item, in terms a transport applies without knowing why. */
internal sealed interface SyncOutcome {
    /** Writes [data] to the service; local already holds it. */
    class Push(val data: JsonElement) : SyncOutcome

    /** Deletes the item at the service; it is already gone locally. */
    data object DeleteRemote : SyncOutcome

    /** Writes [data] locally; the service already holds it. */
    class Pull(val data: JsonElement) : SyncOutcome

    /** Deletes the item locally; it is already gone at the service. */
    data object DeleteLocal : SyncOutcome

    /** Writes [data] both locally and at the service: an object conflict merged key by key. */
    class Merged(val data: JsonElement) : SyncOutcome

    /** Neither side changed, or both now agree; nothing to do. */
    data object Noop : SyncOutcome
}

/** One item's decision, [conflict] when both sides had changed since the last sync. */
internal class SyncDecision(val outcome: SyncOutcome, val conflict: Boolean)

/**
 * Decides, from [SyncRecord] (what the last successful sync recorded for one item), its current local content and
 * [RemoteState] (what this run's listing or change page found), what a sync does with it next.
 *
 * Pure: no I/O, no clock, and it never sees a secret's value (only [SyncRecord.localHash]), so it is tested and
 * logged freely; a [SyncTransport] carries out the [SyncOutcome] it decides.
 */
internal class SyncEngine(private val policy: SyncPolicy) {
    /** [local] is null when the item does not exist locally (never did, or was deleted). */
    fun decide(before: SyncRecord?, local: JsonElement?, remote: RemoteState): SyncDecision {
        val localHash = local?.let(::contentHash)
        val localChanged = before?.localHash != localHash
        val remoteChanged = when (remote) {
            RemoteState.Unchanged -> false
            is RemoteState.Present -> before?.remoteRev != remote.rev
            is RemoteState.Deleted -> before?.remoteRev != remote.rev
        }
        return when {
            !localChanged && !remoteChanged -> SyncDecision(SyncOutcome.Noop, conflict = false)
            localChanged && !remoteChanged -> SyncDecision(oneWayToRemote(local), conflict = false)
            !localChanged && remoteChanged -> SyncDecision(oneWayToLocal(remote), conflict = false)
            else -> SyncDecision(resolve(local, remote), conflict = true)
        }
    }

    private fun oneWayToRemote(local: JsonElement?): SyncOutcome =
        if (local == null) SyncOutcome.DeleteRemote else SyncOutcome.Push(local)

    private fun oneWayToLocal(remote: RemoteState): SyncOutcome = when (remote) {
        is RemoteState.Present -> SyncOutcome.Pull(remote.data)
        is RemoteState.Deleted -> SyncOutcome.DeleteLocal
        RemoteState.Unchanged -> SyncOutcome.Noop
    }

    /** Both sides changed since the last sync: resolved by [policy]. */
    private fun resolve(local: JsonElement?, remote: RemoteState): SyncOutcome {
        if (remote is RemoteState.Unchanged) return oneWayToRemote(local)
        return when (policy) {
            SyncPolicy.LOCAL -> oneWayToRemote(local)
            SyncPolicy.REMOTE -> oneWayToLocal(remote)
            SyncPolicy.MERGE -> merge(local, remote)
        }
    }

    /** Object documents merge key by key, locally-changed keys winning; anything else falls back to local. */
    private fun merge(local: JsonElement?, remote: RemoteState): SyncOutcome {
        val remoteData = (remote as? RemoteState.Present)?.data
        if (local is JsonObject && remoteData is JsonObject) return SyncOutcome.Merged(JsonObject(remoteData + local))
        return oneWayToRemote(local)
    }
}
