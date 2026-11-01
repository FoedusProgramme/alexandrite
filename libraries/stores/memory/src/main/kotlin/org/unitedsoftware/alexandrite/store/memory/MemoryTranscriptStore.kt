package org.unitedsoftware.alexandrite.store.memory

import org.unitedsoftware.alexandrite.internal.text.snippet
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
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
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptCodec
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

/** The entries, kept in the form [TranscriptCodec] persists. */
@Singleton
@Binds(TranscriptStore::class)
internal class MemoryTranscriptStore(private val data: StoreData) : TranscriptStore {
    override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry> {
        val encoded = entries.map { entry ->
            require(entry.record == null) { "Entry ${entry.record?.id} is stored already." }
            require(entry.mediaParts().none { it.source is InlineMedia }) {
                "An entry to store holds inline media: store them first, with MediaStore.storeInline."
            }
            entry to TranscriptCodec.encode(entry)
        }
        return data.locked { now ->
            val conversation = turns[turn]?.conversation?.let(conversations::getValue)
                ?: throw IllegalArgumentException("Turn $turn is not recorded.")
            check(conversation.state == ConversationState.ACTIVE) {
                "Conversation ${conversation.id} is ${conversation.state}, so it takes no new entries."
            }
            for ((entry, _) in encoded) {
                val unknown = entry.storedMedia().firstOrNull { it !in media }
                require(unknown == null) { "An entry to store refers to the unknown media $unknown." }
            }
            encoded.map { (entry, body) ->
                val record = EntryRecord(EntryId(++lastEntry), conversation.id, turn, now)
                val message = ((entry as? UserEntry)?.origin as? UserOrigin.FromChat)?.message
                this.entries[record.id.value] = StoredEntry(record, body, message, entry.storedMedia())
                entry.withRecord(record)
            }
        }
    }

    override suspend fun entries(conversation: ConversationId): List<TranscriptEntry> =
        stored { it.record.conversation == conversation }.map { it.read() }

    override suspend fun entriesAfter(conversation: ConversationId, after: EntryId): List<TranscriptEntry> =
        data.locked {
            entries.tailMap(after.value, false).values.filter { it.record.conversation == conversation }
        }.map { it.read() }

    override suspend fun tail(conversation: ConversationId, count: Int): List<TranscriptEntry> {
        require(count >= 0) { "A tail has no negative length, was $count." }
        return stored { it.record.conversation == conversation }.takeLast(count).map { it.read() }
    }

    override suspend fun turnEntries(turn: TurnId): List<TranscriptEntry> =
        stored { it.record.turn == turn }.map { it.read() }

    override suspend fun entry(id: EntryId): TranscriptEntry? = data.locked { entries[id.value] }?.read()

    override suspend fun entries(message: ChannelMessageRef): List<TranscriptEntry> =
        stored { it.message == message }.map { it.read() }

    override suspend fun deleteMessage(message: ChannelMessageRef): Int = data.locked {
        val deleted = entries.values.filter { it.message == message }
        deleted.forEach { entries.remove(it.record.id.value) }
        val referred = entries.values.flatMapTo(HashSet()) { it.media }
        deleted.flatMap { it.media }.filter { it !in referred }.forEach { media.remove(it) }
        deleted.size
    }

    /** Ranks the matches by how often the words occur, then the newest first. */
    override suspend fun search(query: String, keys: Set<AgentChatKey>, limit: Int): List<TranscriptMatch> {
        require(limit >= 1) { "A search finds at least one entry, was $limit." }
        val words = searchWords(query)
        if (words.isEmpty()) return emptyList()
        val searched = data.locked {
            entries.values.mapNotNull { stored ->
                conversations[stored.record.conversation]?.key?.takeIf { it in keys }?.let { stored to it }
            }
        }
        val found = searched.mapNotNull { (stored, key) ->
            val entry = stored.read()
            val text = entry.searchText()
            if (!words.all { text.contains(it, ignoreCase = true) }) return@mapNotNull null
            val score = words.sumOf { occurrences(text, it) }
            Found(TranscriptMatch(entry, key, snippet(text, words, SNIPPET_CHARS)), score, stored.record.id.value)
        }
        return found.sortedWith(BEST_FIRST).take(limit).map { it.match }
    }

    private suspend fun stored(predicate: (StoredEntry) -> Boolean): List<StoredEntry> =
        data.locked { entries.values.filter(predicate) }

    private fun StoredEntry.read(): TranscriptEntry = TranscriptCodec.decode(body).withRecord(record)
}

/** The media in the store that the entry refers to. */
internal fun TranscriptEntry.storedMedia(): Set<MediaId> =
    mediaParts().mapNotNullTo(LinkedHashSet()) { (it.source as? StoredMedia)?.id }

private fun TranscriptEntry.mediaParts(): List<MediaPart> = when (this) {
    is UserEntry -> parts.filterIsInstance<MediaPart>()
    is ToolResultEntry -> content.filterIsInstance<MediaPart>()
    else -> emptyList()
}

private class Found(val match: TranscriptMatch, val score: Int, val id: Long)

private val BEST_FIRST = compareByDescending<Found> { it.score }.thenByDescending { it.id }

private fun occurrences(text: String, word: String): Int {
    var count = 0
    var at = text.indexOf(word, ignoreCase = true)
    while (at >= 0) {
        count++
        at = text.indexOf(word, at + word.length, ignoreCase = true)
    }
    return count
}

private const val SNIPPET_CHARS = 240
