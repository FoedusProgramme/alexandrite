package org.unitedsoftware.alexandrite.sdk.turn

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import java.time.Instant

/**
 * The current conversations of the agents' chats, read without changing anything.
 *
 * Sealed conversations are left out: a host that keeps conversations apart gives each its own chat.
 */
@HostApi
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ChatHistory {
    /** The chats where [agent] has a conversation, the latest active first. */
    public suspend fun chats(agent: AgentId): List<ChatConversation>

    /**
     * The entries of the current conversation of [agent] at [chat], else of the chat's agent, in the order they were
     * stored: at most [limit] of those whose ids are greater than [after], or from the first when it is null.
     *
     * A running turn's entries show once they are stored. Throws [ControlRefusedException] when [agent] is null and no
     * agent serves [chat], and [IllegalArgumentException] when [limit] is less than 1.
     */
    public suspend fun entries(chat: ChatAddress, after: EntryId?, limit: Int, agent: AgentId? = null): TranscriptPage
}

/** The current conversation of [agent] at [chat]. */
@Poko
public class ChatConversation @InternalAlexandriteApi constructor(
    public val agent: AgentId,
    /** The chat that keeps the conversation, the anchor of a linked group. */
    public val chat: ChatAddress,
    public val conversation: ConversationId,
    public val startedAt: Instant,
    /** When the conversation's last entry was stored, [startedAt] while it has none. */
    public val lastActivity: Instant,
)

/** Entries that [ChatHistory.entries] read. */
@Poko
public class TranscriptPage @InternalAlexandriteApi constructor(
    /** Null when the agent has no conversation at the chat. */
    public val conversation: ConversationId?,
    public val entries: List<TranscriptEntry>,
    /** Whether the conversation holds entries after the last of [entries]. */
    public val more: Boolean,
)
