package org.unitedsoftware.alexandrite.account.pleochroic

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
 * Two-way config/secret/skill sync against a Pleochroic account or a self-hosted `standalone-service`
 * (`docs/API.md` "同步"). The engine is transport-agnostic ([SyncTransport]) and filesystem-agnostic ([SyncLocal]
 * is handed in by the caller), so both are faked in tests, and a host other than `app` supplies its own [SyncLocal].
 */
@Serializable(with = SyncPolicySerializer::class)
public enum class SyncPolicy(public val id: String) {
    /** The side that changed here wins a conflict; a remote delete of a locally-changed item is undone. */
    LOCAL("local"),

    /** The service's side wins a conflict; a local delete is undone by a concurrent remote change. */
    REMOTE("remote"),

    /** Object documents merge key by key (locally-changed keys win); anything else falls back to [LOCAL]. */
    MERGE("merge"),
    ;

    public companion object {
        public fun of(id: String): SyncPolicy? = entries.firstOrNull { it.id == id }
    }
}

/** `<area>:<kind>:<id>`, the one key [SyncState.records] and a sync run's bookkeeping track an item by. */
public fun syncKey(area: String, kind: String, id: String): String = "$area:$kind:$id"

/** What the engine knew of one item after its last successful sync. */
@Serializable
public class SyncRecord(public val localHash: String?, public val remoteRev: String?)

/** The sync feature's whole persisted state. A host keeps it in a file of its own, never in the config. */
@Serializable
public class SyncState(
    public val enabled: Boolean = false,
    public val policy: SyncPolicy = SyncPolicy.MERGE,
    public val cursor: Long = 0,
    public val records: Map<String, SyncRecord> = emptyMap(),
) {
    public fun with(
        enabled: Boolean = this.enabled,
        policy: SyncPolicy = this.policy,
        cursor: Long = this.cursor,
        records: Map<String, SyncRecord> = this.records,
    ): SyncState = SyncState(enabled, policy, cursor, records)

    public companion object {
        public val OFF: SyncState = SyncState()
    }
}

/** Keeps the state file readable (`"policy": "merge"`) rather than an enum ordinal or constant name. */
public object SyncPolicySerializer : KSerializer<SyncPolicy> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.unitedsoftware.alexandrite.account.pleochroic.SyncPolicy", PrimitiveKind.STRING)

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

/** [state] as the text a host keeps its sync state file in. */
public fun encodeSyncState(state: SyncState): String = STATE_JSON.encodeToString(SyncState.serializer(), state)

/** The [SyncState] a host's sync state file holds. */
public fun decodeSyncState(text: String): SyncState = STATE_JSON.decodeFromString(SyncState.serializer(), text)

/** [element] with every object's keys sorted (recursively), so two documents equal in content hash equal. */
private fun sorted(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
    is JsonArray -> JsonArray(element.map(::sorted))
    else -> element
}

/** The content hash [SyncRecord.localHash] and the engine's own change detection compare by. */
public fun contentHash(element: JsonElement): String = sha256Hex(sorted(element).toString())
