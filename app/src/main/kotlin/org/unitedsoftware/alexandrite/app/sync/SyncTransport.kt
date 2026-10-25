package org.unitedsoftware.alexandrite.app.sync

import kotlinx.serialization.json.JsonElement

/** One remote document's content, as [SyncTransport.get] reads it. */
internal class RemoteDoc(val rev: String, val data: JsonElement)

/** One remote write's or listing entry's meta: its new or current rev, [deleted] only in a change page. */
internal class RemoteMeta(val rev: String, val deleted: Boolean = false)

/** One entry of the change feed (`docs/API.md` "变更流"); [rev] null when a defensive read could not tell it. */
internal class ChangeEntry(
    val seq: Long,
    val area: String,
    val kind: String,
    val id: String,
    val rev: String?,
    val deleted: Boolean,
)

/** One page of `GET /sync/changes`. */
internal class ChangePage(val changes: List<ChangeEntry>, val cursor: Long)

/** `409 conflict`: [current] is the document's meta now, which the caller re-reads before retrying. */
internal class SyncConflictException(val current: RemoteMeta) : Exception()

/** Any other error the service answered with, by its `error.code` (`docs/API.md` "约定"). */
internal class SyncServiceException(val code: String, message: String) : Exception(message)

/**
 * Talks to a Pleochroic account's or a self-hosted `standalone-service`'s sync surface (`docs/API.md` "同步"), one
 * account at a time. [SyncRunner] decides what to read and write; this only carries the calls out, so a fake stands
 * in for it in tests. Secrets are area `"secret"` with an empty `kind`, modeled as the one-field document
 * `{"value": "..."}`, so [SyncEngine] need not know config documents and secrets apart.
 */
internal interface SyncTransport {
    /** `GET /config/:kind/:id` or `GET /secrets/:id`, null when there is none. */
    suspend fun get(area: String, kind: String, id: String): RemoteDoc?

    /** A conditional write: `If-Match: ifMatch`, or `If-None-Match: *` when [ifMatch] is null (a new document). */
    suspend fun put(area: String, kind: String, id: String, data: JsonElement, ifMatch: String?): RemoteMeta

    /** A conditional delete: `If-Match: ifMatch`. */
    suspend fun delete(area: String, kind: String, id: String, ifMatch: String)

    /** `GET /sync/changes?since=since`, paged through to its end; [since] 0 for the first page. */
    suspend fun changes(since: Long): ChangePage
}
