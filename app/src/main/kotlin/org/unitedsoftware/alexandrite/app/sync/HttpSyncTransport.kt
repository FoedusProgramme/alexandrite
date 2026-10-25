package org.unitedsoftware.alexandrite.app.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import java.net.URI
import java.net.URLEncoder

private val JSON = Json { ignoreUnknownKeys = true }

private const val LIMIT = 8 * 1024 * 1024

/**
 * The real [SyncTransport]: the standalone-service API (`docs/API.md` "同步"), bearer-authenticated at [baseUrl]
 * with [apiKey]. A secret (area [AREA_SECRET], empty `kind`) is modeled the same way a config document is, as the
 * one-field object `{"value": "..."}` ([SyncLocal.unwrapSecretValue] / [SyncRunner] undo this at the edges), so
 * [SyncEngine] need not know the difference.
 */
internal class HttpSyncTransport(
    private val transport: HttpTransport,
    private val baseUrl: String,
    private val apiKey: String,
) : SyncTransport {
    override suspend fun get(area: String, kind: String, id: String): RemoteDoc? {
        val answer = call("GET", path(area, kind, id), emptyMap()) ?: return null
        val (headers, json) = answer
        val rev = rev(headers, json) ?: return null
        val data = if (area == AREA_SECRET) buildJsonObject { put(VALUE, json?.string(VALUE)) } else json?.get(DATA)
        return data?.let { RemoteDoc(rev, it) }
    }

    override suspend fun put(area: String, kind: String, id: String, data: JsonElement, ifMatch: String?): RemoteMeta {
        val headers = if (ifMatch != null) mapOf(IF_MATCH to quoted(ifMatch)) else mapOf(IF_NONE_MATCH to "*")
        val body = if (area == AREA_SECRET) {
            buildJsonObject { put(VALUE, (data as? JsonObject)?.get(VALUE) ?: data) }
        } else {
            buildJsonObject { put(DATA, data) }
        }
        val target = path(area, kind, id)
        val (responseHeaders, json) = call("PUT", target, headers, body.toString())
            ?: throw SyncServiceException("protocol", "The write to $target answered with no body.")
        val rev =
            rev(responseHeaders, json) ?: throw SyncServiceException("protocol", "The write to $target named no rev.")
        return RemoteMeta(rev)
    }

    override suspend fun delete(area: String, kind: String, id: String, ifMatch: String) {
        call("DELETE", path(area, kind, id), mapOf(IF_MATCH to quoted(ifMatch)))
    }

    override suspend fun changes(since: Long): ChangePage {
        val all = mutableListOf<ChangeEntry>()
        var cursor = since
        var more = true
        while (more) {
            val (_, json) = call("GET", "/sync/changes?since=$cursor&limit=$PAGE_LIMIT", emptyMap())
                ?: return ChangePage(all, cursor)
            val page = try {
                JSON.decodeFromString(ChangesWire.serializer(), json.toString())
            } catch (e: SerializationException) {
                throw SyncServiceException("protocol", "The change feed answered with no readable page.")
            }
            all += page.changes.map { ChangeEntry(it.seq, it.area, it.kind.orEmpty(), it.id, it.rev, it.deleted) }
            cursor = page.cursor
            more = page.more && page.changes.isNotEmpty()
        }
        return ChangePage(all, cursor)
    }

    /** `null` for a 404, else the response headers this cares about alongside a body JSON object, or null for none. */
    private suspend fun call(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String? = null,
    ): Pair<Map<String, String>, JsonObject?>? {
        val all = buildMap {
            put("authorization", "Bearer $apiKey")
            put("accept", "application/json")
            if (body != null) put("content-type", "application/json")
            putAll(headers)
        }
        val call = HttpCall(method, URI("$baseUrl$path"), all, body, secrets = listOf(apiKey))
        return try {
            var result: Pair<Map<String, String>, JsonObject?>? = null
            transport.open(call) { stream ->
                val text = stream.text(LIMIT)
                val json = if (text.isBlank()) null else parseObject(text)
                val etag = stream.headers.firstValue(ETAG).orElse(null)
                result = (if (etag != null) mapOf(ETAG to etag) else emptyMap()) to json
            }
            result
        } catch (e: HttpException) {
            if (e.status == 404) return null
            throw toSyncException(e)
        }
    }

    private fun path(area: String, kind: String, id: String): String {
        val encodedId = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")
        return if (area ==
            AREA_SECRET
        ) {
            "/secrets/$encodedId"
        } else {
            "/config/${URLEncoder.encode(kind, Charsets.UTF_8)}/$encodedId"
        }
    }

    private fun rev(headers: Map<String, String>, json: JsonObject?): String? =
        json?.string(REV) ?: headers[ETAG]?.removeSurrounding("\"")

    companion object {
        private const val DATA = "data"
        private const val VALUE = "value"
        private const val REV = "rev"
        private const val ETAG = "etag"
        private const val IF_MATCH = "if-match"
        private const val IF_NONE_MATCH = "if-none-match"
        private const val PAGE_LIMIT = 500

        private fun quoted(rev: String) = "\"$rev\""

        private fun parseObject(text: String): JsonObject? = try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        }

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun toSyncException(e: HttpException): Exception {
            val detail = e.body?.let(::parseObject)
            val error = detail?.get("error") as? JsonObject
            val code = error?.string("code") ?: "http_${e.status ?: 0}"
            if (code == "conflict") {
                val current = error?.get("current") as? JsonObject
                return SyncConflictException(RemoteMeta(current?.string(REV) ?: "?", deleted = false))
            }
            return SyncServiceException(code, error?.string("message") ?: e.message ?: code)
        }
    }
}

@kotlinx.serialization.Serializable
private class ChangesWire(val changes: List<ChangeWire> = emptyList(), val cursor: Long = 0, val more: Boolean = false)

@kotlinx.serialization.Serializable
private class ChangeWire(
    val seq: Long,
    val area: String,
    val kind: String? = null,
    val id: String,
    val rev: String? = null,
    val deleted: Boolean = false,
)
