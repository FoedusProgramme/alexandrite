package org.unitedsoftware.alexandrite.app.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex

/**
 * Two-way config/secret sync against a Pleochroic account or a self-hosted `standalone-service`
 * (`docs/API.md` "同步"). The engine ([SyncEngine]) is transport-agnostic ([SyncTransport]) and filesystem-agnostic
 * ([SyncItem] is handed in by the caller), so both can be faked in tests.
 */
@Serializable(with = SyncPolicySerializer::class)
internal enum class SyncPolicy(val id: String) {
    /** The side that changed here wins a conflict; a remote delete of a locally-changed item is undone. */
    LOCAL("local"),

    /** The service's side wins a conflict; a local delete is undone by a concurrent remote change. */
    REMOTE("remote"),

    /** Object documents merge key by key (locally-changed keys win); anything else falls back to [LOCAL]. */
    MERGE("merge"),
    ;

    companion object {
        fun of(id: String): SyncPolicy? = entries.firstOrNull { it.id == id }
    }
}

/** One locally held document or secret, named within its [area] (`config` / `secret`) by [kind] and [id]. */
internal class SyncItem(val area: String, val kind: String, val id: String, val data: JsonElement) {
    val key: String get() = syncKey(area, kind, id)
}

internal fun syncKey(area: String, kind: String, id: String): String = "$area:$kind:$id"

/** The inverse of [syncKey]: (area, kind, id). */
internal fun parseSyncKey(key: String): Triple<String, String, String> {
    val area = key.substringBefore(':')
    val rest = key.substringAfter(':')
    return Triple(area, rest.substringBefore(':'), rest.substringAfter(':'))
}

/** What the engine knew of one item after its last successful sync. */
@Serializable
internal class SyncRecord(val localHash: String?, val remoteRev: String?)

/** The sync feature's whole persisted state (kept in a file of its own in the data directory, never in the config). */
@Serializable
internal class SyncState(
    val enabled: Boolean = false,
    val policy: SyncPolicy = SyncPolicy.MERGE,
    val cursor: Long = 0,
    val records: Map<String, SyncRecord> = emptyMap(),
) {
    fun with(
        enabled: Boolean = this.enabled,
        policy: SyncPolicy = this.policy,
        cursor: Long = this.cursor,
        records: Map<String, SyncRecord> = this.records,
    ): SyncState = SyncState(enabled, policy, cursor, records)

    companion object {
        val OFF: SyncState = SyncState()
    }
}

/** Keeps the state file readable (`"policy": "merge"`) rather than an enum ordinal or constant name. */
internal object SyncPolicySerializer : KSerializer<SyncPolicy> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.unitedsoftware.alexandrite.app.sync.SyncPolicy", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SyncPolicy) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): SyncPolicy {
        val id = decoder.decodeString()
        return SyncPolicy.of(id) ?: throw SerializationException("'$id' is no sync policy")
    }
}

private val STATE_JSON = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

internal fun encodeSyncState(state: SyncState): String = STATE_JSON.encodeToString(SyncState.serializer(), state)

internal fun decodeSyncState(text: String): SyncState = STATE_JSON.decodeFromString(SyncState.serializer(), text)

/** [element] with every object's keys sorted (recursively), so two documents equal in content hash equal. */
private fun sorted(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
    is JsonArray -> JsonArray(element.map(::sorted))
    else -> element
}

/** The content hash [SyncRecord.localHash] and the engine's own change detection compare by. */
internal fun contentHash(element: JsonElement): String = sha256Hex(sorted(element).toString())
