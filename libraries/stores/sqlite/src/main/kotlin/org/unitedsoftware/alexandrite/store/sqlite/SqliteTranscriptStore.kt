package org.unitedsoftware.alexandrite.store.sqlite

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.text.snippet
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.TranscriptMatch
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.store.searchText
import org.unitedsoftware.alexandrite.sdk.store.searchWords
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.InlineMedia
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptCodec
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UnknownEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

@Singleton
@Binds(TranscriptStore::class)
internal class SqliteTranscriptStore(private val database: StoreDatabase, private val media: SqliteMediaStore) :
    TranscriptStore {
    override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry> {
        val encoded = entries.map { entry ->
            require(entry.record == null) { "Entry ${entry.record?.id} is stored already." }
            require(entry.mediaParts().none { it.source is InlineMedia }) {
                "An entry to store holds inline media: store them first, with MediaStore.storeInline."
            }
            Encoded(entry, TranscriptCodec.encode(entry))
        }
        return database.transaction {
            val (conversation, state) = queryOne(
                "SELECT c.id, c.state FROM turns t JOIN conversations c ON c.id = t.conversation WHERE t.id = ?",
                turn.value,
            ) { ConversationId(getString(1)) to ConversationState.of(getString(2)) }
                ?: throw IllegalArgumentException("Turn $turn is not recorded.")
            check(state == ConversationState.ACTIVE) {
                "Conversation $conversation is $state, so it takes no new entries."
            }
            encoded.map { insert(conversation, turn, it) }
        }
    }

    override suspend fun entries(conversation: ConversationId): List<TranscriptEntry> = database.transaction {
        rows("WHERE conversation = ? ORDER BY id", conversation.value)
    }.map { it.entry() }

    override suspend fun entriesAfter(conversation: ConversationId, after: EntryId): List<TranscriptEntry> =
        database.transaction {
            rows("WHERE conversation = ? AND id > ? ORDER BY id", conversation.value, after.value)
        }.map { it.entry() }

    override suspend fun tail(conversation: ConversationId, count: Int): List<TranscriptEntry> {
        require(count >= 0) { "A tail has no negative length, was $count." }
        return database.transaction {
            rows("WHERE conversation = ? ORDER BY id DESC LIMIT ?", conversation.value, count)
        }.asReversed().map { it.entry() }
    }

    override suspend fun turnEntries(turn: TurnId): List<TranscriptEntry> = database.transaction {
        rows("WHERE turn = ? ORDER BY id", turn.value)
    }.map { it.entry() }

    override suspend fun entry(id: EntryId): TranscriptEntry? =
        database.transaction { rows("WHERE id = ?", id.value) }.singleOrNull()?.entry()

    override suspend fun entries(message: ChannelMessageRef): List<TranscriptEntry> = database.transaction {
        val chat = chatIdOrNull(message.chat) ?: return@transaction emptyList()
        rows("WHERE message_chat_id = ? AND message_id = ? ORDER BY id", chat, message.id)
    }.map { it.entry() }

    override suspend fun deleteMessage(message: ChannelMessageRef): Int = database.transaction {
        val chat = chatIdOrNull(message.chat) ?: return@transaction 0
        val ids = query("SELECT id FROM entries WHERE message_chat_id = ? AND message_id = ?", chat, message.id) {
            getLong(1)
        }
        val referred = ids.flatMapTo(LinkedHashSet()) { id ->
            query("SELECT media_id FROM entry_media WHERE entry_id = ?", id) { MediaId(getString(1)) }
        }
        for (id in ids) {
            execute("DELETE FROM entry_media WHERE entry_id = ?", id)
            unindexText(id)
            execute("DELETE FROM entries WHERE id = ?", id)
        }
        media.deleteUnreferenced(this, referred)
        ids.size
    }

    /** Ranks the matches by BM25, then the newest first. */
    override suspend fun search(query: String, keys: Set<AgentChatKey>, limit: Int): List<TranscriptMatch> {
        require(limit >= 1) { "A search finds at least one entry, was $limit." }
        val words = searchWords(query)
        if (words.isEmpty()) return emptyList()
        return database.transaction {
            val scopes = keys.mapNotNull { key -> chatIdOrNull(key.chat)?.let { Scope(key.agent, it) to key } }.toMap()
            if (scopes.isEmpty()) return@transaction emptyList()
            val within = scopes.keys.joinToString(" OR ", "(", ")") { "(c.agent = ? AND c.chat_id = ?)" }
            val arguments = scopes.keys.flatMap { listOf<Any>(it.agent.value, it.chat) }
            query(
                "SELECT entry_grams.docid, matchinfo(entry_grams, 'pcnalx'), c.agent, c.chat_id FROM entry_grams " +
                    "CROSS JOIN entries e ON e.id = entry_grams.docid " +
                    "CROSS JOIN conversations c ON c.id = e.conversation WHERE entry_grams MATCH ? AND $within",
                gramQuery(words),
                *arguments.toTypedArray(),
            ) { Hit(getLong(1), bm25(getBlob(2)), scopes.getValue(Scope(AgentId(getString(3)), getLong(4)))) }
                .sortedWith(compareByDescending<Hit> { it.weight }.thenByDescending { it.id })
                .take(limit)
                .map { hit -> rows("WHERE id = ?", hit.id).single() to hit.key }
        }.map { (stored, key) ->
            val entry = stored.entry()
            TranscriptMatch(entry, key, snippet(entry.searchText(), words, SNIPPET_CHARS))
        }
    }

    private fun Tx.insert(conversation: ConversationId, turn: TurnId, encoded: Encoded): TranscriptEntry {
        val message = ((encoded.entry as? UserEntry)?.origin as? UserOrigin.FromChat)?.message
        val id = insert(
            "INSERT INTO entries (conversation, turn, type, created_at, format, body, message_chat_id, message_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            conversation.value,
            turn.value,
            encoded.type,
            now,
            TranscriptCodec.FORMAT_VERSION,
            encoded.body,
            message?.let { chatId(it.chat) },
            message?.id,
        )
        for (mediaId in encoded.entry.storedMedia()) {
            requireNotNull(queryOne("SELECT 1 FROM media WHERE id = ?", mediaId.value) { true }) {
                "An entry to store refers to the unknown media $mediaId."
            }
            execute("INSERT INTO entry_media (entry_id, media_id) VALUES (?, ?)", id, mediaId.value)
        }
        indexText(id, encoded.entry)
        return encoded.entry.withRecord(EntryRecord(EntryId(id), conversation, turn, now))
    }

    private fun Tx.rows(where: String, vararg arguments: Any?): List<Stored> = query(
        "SELECT id, conversation, turn, created_at, format, type, body FROM entries $where",
        *arguments,
    ) { stored() }

    private fun Row.stored(): Stored = Stored(
        EntryRecord(
            EntryId(getLong("id")),
            ConversationId(getString("conversation")),
            TurnId(getString("turn")),
            instant("created_at"),
        ),
        getInt("format"),
        getString("type"),
        getString("body"),
    )

    private data class Scope(val agent: AgentId, val chat: Long)

    private class Hit(val id: Long, val weight: Double, val key: AgentChatKey)

    private class Encoded(val entry: TranscriptEntry, val body: String) {
        val type: String = Json.parseToJsonElement(body).jsonObject.getValue("type").jsonPrimitive.content
    }

    private class Stored(val record: EntryRecord, val format: Int, val type: String, val body: String) {
        /** The stored entry, an [UnknownEntry] when its format or its content is not one this version reads. */
        fun entry(): TranscriptEntry {
            val entry = decodeStoredEntry(format, body) { e ->
                logger.warn("Entry {} cannot be read and counts as unknown", record.id, e)
            }
            return (entry ?: UnknownEntry(null, type, raw())).withRecord(record)
        }

        /** The body as a JSON object, or wrapped in one when it is none. */
        private fun raw(): JsonObject {
            val parsed = try {
                Json.parseToJsonElement(body)
            } catch (e: SerializationException) {
                null
            }
            return parsed as? JsonObject
                ?: JsonObject(mapOf("type" to JsonPrimitive(type), BODY to JsonPrimitive(body)))
        }
    }
}

private const val SNIPPET_CHARS = 240

/** The field of an unknown entry's JSON that holds a body that is no JSON object. */
private const val BODY = "body"

private val logger = LoggerFactory.getLogger(SqliteTranscriptStore::class.java)
