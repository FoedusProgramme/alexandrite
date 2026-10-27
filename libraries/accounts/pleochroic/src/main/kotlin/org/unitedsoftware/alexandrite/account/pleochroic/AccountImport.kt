package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A skill or MCP server the account holds, as [AccountImport.list] finds it in the change feed. */
public class AccountItem(public val area: String, public val kind: String, public val id: String) {
    public val key: String get() = syncKey(area, kind, id)
}

/** What [AccountImport.list] found: the importable [items] and the account's own secret names. */
public class AccountListing(public val items: List<AccountItem>, public val secrets: Set<String>)

/** One secret an imported MCP server names, fetched; [missing] when it is not here yet and is to be written. */
public class AccountSecret(
    public val name: String,
    public val value: String,
    public val rev: String,
    public val missing: Boolean,
)

/** What [AccountImport.fetch] made of one [AccountItem]. */
public sealed interface AccountFetch {
    /** [data] in the local form (`${env:NAME}` references), [secrets] every one it names. */
    public class Ready(
        public val item: AccountItem,
        public val data: JsonElement,
        public val rev: String,
        public val secrets: List<AccountSecret>,
    ) : AccountFetch

    /** Not imported, for [reason]; [detail] names the secret or reference it is about, when there is one. */
    public class Refused(public val item: AccountItem, public val reason: Reason, public val detail: String? = null) :
        AccountFetch

    /** Why an item was [Refused], by [id]. */
    public enum class Reason(public val id: String) {
        /** The account no longer holds it, or a secret it names. */
        GONE("gone"),

        /** Its name, scope, shape or a secret's value is not valid here. */
        INVALID("invalid"),

        /** It references a local secret, or a secret the account does not hold ([suspiciousSecretReference]). */
        SUSPICIOUS_REFERENCE("suspicious_reference"),

        /** A secret it names already holds a different value here. */
        SECRET_DIFFERS("secret_differs"),
    }
}

/**
 * Brings a Pleochroic account's own skills and MCP servers into a fresh setup without syncing: it only reads from
 * [transport] and never pushes, deletes or advances [SyncState.cursor], so it is safe with sync off (the owner's
 * decision: nothing leaves this machine until sync is turned on). The caller writes what [fetch] returns and
 * records it with [importedRecords], so a later first sync finds both sides already agreeing instead of holding every
 * imported item back for review.
 */
public class AccountImport(private val transport: SyncTransport) {
    /** The account's skills and MCP servers that still exist, from the whole change feed. */
    public suspend fun list(): AccountListing {
        val latest = transport.changes(0).changes.sortedBy { it.seq }.associateBy { syncKey(it.area, it.kind, it.id) }
        val live = latest.values.filterNot { it.deleted }
        val items = live.filter { it.area == AREA_SKILL || (it.area == AREA_CONFIG && it.kind == KIND_MCP) }
            .map { AccountItem(it.area, it.kind, it.id) }
        val secrets = live.filter { it.area == AREA_SECRET }.mapTo(mutableSetOf()) { it.id }
        return AccountListing(items, secrets)
    }

    /**
     * [item]'s content, ready to write. An MCP server's `${secret:NAME}` references become `${env:NAME}` only for
     * a name in [accountSecrets]; any other reference refuses it, the same hijack check a sync pull makes
     * ([suspiciousSecretReference]). A named secret already in [localSecrets] with a different value refuses it
     * too, rather than overwriting the local one or pointing the server at it.
     */
    public suspend fun fetch(
        item: AccountItem,
        accountSecrets: Set<String>,
        localSecrets: Map<String, String>,
    ): AccountFetch {
        fun refused(reason: AccountFetch.Reason, detail: String? = null) = AccountFetch.Refused(item, reason, detail)
        val fetched = transport.get(item.area, item.kind, item.id) ?: return refused(AccountFetch.Reason.GONE)
        if (item.area == AREA_SKILL) {
            val valid = isValidSkillName(item.id) && isValidSkillScope(item.kind) && fetched.data is JsonObject
            if (!valid) return refused(AccountFetch.Reason.INVALID)
            return AccountFetch.Ready(item, fetched.data, fetched.rev, emptyList())
        }
        if (!isSafeConfigId(item.id) || fetched.data !is JsonObject) return refused(AccountFetch.Reason.INVALID)
        suspiciousSecretReference(fetched.data, accountSecrets)?.let {
            return refused(AccountFetch.Reason.SUSPICIOUS_REFERENCE, it)
        }
        val secrets = mutableListOf<AccountSecret>()
        for (name in referencedSecrets(fetched.data, accountSecrets)) {
            val doc = transport.get(AREA_SECRET, "", name) ?: return refused(AccountFetch.Reason.GONE, name)
            val value = (unwrapSecretValue(doc.data) as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null || !isValidSecretName(name) || !isSafeSecretValue(value)) {
                return refused(AccountFetch.Reason.INVALID, name)
            }
            val here = localSecrets[name]
            if (here != null && here != value) return refused(AccountFetch.Reason.SECRET_DIFFERS, name)
            secrets += AccountSecret(name, value, doc.rev, missing = here == null)
        }
        return AccountFetch.Ready(item, toLocalSecretRefs(fetched.data, accountSecrets), fetched.rev, secrets)
    }
}

/**
 * The [SyncRecord]s for what was imported, by key: each [Ready][AccountFetch.Ready] item and secret [local] now
 * holds as imported. One that did not land (a refused write, an aborted setup) or landed changed is left out, so a
 * later sync decides it afresh rather than reading its absence as a local delete or taking the two sides to agree
 * when they do not. A skill's files are compared regardless of order, since a local read lists them in whatever
 * order the file system walks them; the record holds the local content's own hash, which is what a sync compares.
 */
public suspend fun importedRecords(local: SyncLocal, imported: List<AccountFetch.Ready>): Map<String, SyncRecord> {
    val current = local.collect().associateBy { it.key }
    val expected = buildMap {
        for (ready in imported) {
            put(ready.item.key, ready.rev to ready.data)
            for (secret in ready.secrets) {
                put(syncKey(AREA_SECRET, "", secret.name), secret.rev to JsonPrimitive(secret.value))
            }
        }
    }
    return expected.mapNotNull { (key, revAndData) ->
        val (rev, data) = revAndData
        val here = current[key]?.data ?: return@mapNotNull null
        if (contentHash(filesInOrder(here)) != contentHash(filesInOrder(data))) return@mapNotNull null
        key to SyncRecord(contentHash(here), rev)
    }.toMap()
}

/** [data] with a skill document's `files` sorted by path; anything else as it is. */
private fun filesInOrder(data: JsonElement): JsonElement {
    val files = (data as? JsonObject)?.get("files") as? JsonArray ?: return data
    val sorted = files.sortedBy { ((it as? JsonObject)?.get("path") as? JsonPrimitive)?.content.orEmpty() }
    return JsonObject(data + ("files" to JsonArray(sorted)))
}
