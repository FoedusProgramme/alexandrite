package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonElement

/** The area a tracked item is in, matching the change feed's `area` (`docs/API.md` "变更流"). */
public const val AREA_CONFIG: String = "config"
public const val AREA_SECRET: String = "secret"
public const val AREA_SKILL: String = "skill"

/** One remote document's or skill's content, as [SyncTransport.get] reads it. */
public class RemoteDoc(public val rev: String, public val data: JsonElement)

/** One remote write's or listing entry's meta: its new or current rev, [deleted] only in a change page. */
public class RemoteMeta(public val rev: String, public val deleted: Boolean = false)

/**
 * One entry of the change feed (`docs/API.md` "变更流"); [kind] is `""` for a [AREA_SECRET] entry (the service
 * sends `kind: null`), the scope for a [AREA_SKILL] entry, and [rev] null when a defensive read could not tell it.
 */
public class ChangeEntry(
    public val seq: Long,
    public val area: String,
    public val kind: String,
    public val id: String,
    public val rev: String?,
    public val deleted: Boolean,
)

/** One page of `GET /sync/changes`, already followed to its end. */
public class ChangePage(public val changes: List<ChangeEntry>, public val cursor: Long)

/** `409 conflict`: [current] is the document's meta now, which the caller re-reads before retrying. */
public class SyncConflictException(public val current: RemoteMeta) : Exception()

/** Any other error the service answered with, by its `error.code` (`docs/API.md` "约定"). */
public class SyncServiceException(public val code: String, message: String) : Exception(message)

/**
 * Talks to a Pleochroic account's or a self-hosted `standalone-service`'s sync surface (`docs/API.md` "同步" and
 * "Skill"), one account at a time. [SyncRunner] decides what to read and write; this only carries the calls out, so
 * a fake stands in for it in tests.
 *
 * Secrets ([AREA_SECRET]) are modeled the same way config documents are, as the one-field document
 * `{"value": "..."}` with an empty `kind`. Skills ([AREA_SKILL]) use `kind` for the scope (`global`,
 * `agent:<id>`) and `id` for the skill's name; their document is `{"files": [{path, encoding, content}]}`, the
 * shape `GET /skills/:scope/:name` and `PUT /skills/:scope/:name` already use.
 */
public interface SyncTransport {
    /** `GET /config/:kind/:id`, `GET /secrets/:id` or `GET /skills/:scope/:name`; null when there is none. */
    public suspend fun get(area: String, kind: String, id: String): RemoteDoc?

    /** A conditional write: `If-Match: ifMatch`, or `If-None-Match: *` when [ifMatch] is null (a new document). */
    public suspend fun put(area: String, kind: String, id: String, data: JsonElement, ifMatch: String?): RemoteMeta

    /** A conditional delete: `If-Match: ifMatch`. */
    public suspend fun delete(area: String, kind: String, id: String, ifMatch: String)

    /** `GET /sync/changes?since=since`, paged through to its end; [since] 0 for the first page (the full history:
     * config, secret and skill change feeds are never compacted, unlike Lattice's op logs). */
    public suspend fun changes(since: Long): ChangePage
}
