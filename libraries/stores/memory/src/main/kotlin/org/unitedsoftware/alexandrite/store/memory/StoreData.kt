package org.unitedsoftware.alexandrite.store.memory

import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationKind
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.store.MediaInfo
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.TurnRecord
import org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.TreeMap

/** What the store holds, which every port changes only inside [locked]. */
@Singleton
internal class StoreData(private val clock: Clock) {
    private val lock = Any()

    val conversations = LinkedHashMap<ConversationId, ConversationInfo>()
    val taints = HashMap<ConversationId, ConversationTaint>()
    val current = HashMap<Pair<AgentChatKey, ConversationKind>, ConversationId>()
    val turns = LinkedHashMap<TurnId, TurnRecord>()
    val entries = TreeMap<Long, StoredEntry>()
    val media = LinkedHashMap<MediaId, StoredBytes>()
    val chatStates = HashMap<ChatStateKey, String>()
    val records = HashMap<Pair<String, String>, LinkedHashMap<String, StoredRecord<String>>>()

    /** The id of the last entry stored, deleted ones included. */
    var lastEntry = 0L

    /** Runs [block] alone, with the time to the millisecond. */
    fun <T> locked(block: StoreData.(now: Instant) -> T): T =
        synchronized(lock) { block(clock.instant().truncatedTo(ChronoUnit.MILLIS)) }
}

/** An entry as the store keeps it, in its persisted form. */
internal class StoredEntry(
    val record: EntryRecord,
    val body: String,
    val message: ChannelMessageRef?,
    val media: Set<MediaId>,
)

internal class StoredBytes(val info: MediaInfo, val bytes: ByteArray)

internal data class ChatStateKey(val plugin: String, val name: String, val agent: AgentId?, val chat: ChatAddress)
