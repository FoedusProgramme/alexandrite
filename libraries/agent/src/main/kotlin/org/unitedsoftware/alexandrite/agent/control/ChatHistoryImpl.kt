package org.unitedsoftware.alexandrite.agent.control

import org.unitedsoftware.alexandrite.agent.routing.ChatTopology
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.transcript.EntryId
import org.unitedsoftware.alexandrite.sdk.turn.ChatConversation
import org.unitedsoftware.alexandrite.sdk.turn.ChatHistory
import org.unitedsoftware.alexandrite.sdk.turn.TranscriptPage

@Singleton
@Binds(ChatHistory::class)
internal class ChatHistoryImpl(
    private val runner: ControlRunner,
    private val topology: ChatTopology,
    private val conversations: ConversationStore,
    private val transcripts: TranscriptStore,
) : ChatHistory {
    override suspend fun chats(agent: AgentId): List<ChatConversation> = conversations.chats(agent).map { chat ->
        val conversation = conversations.current(AgentChatKey(agent, chat))
        val last = transcripts.tail(conversation.id, 1).lastOrNull()?.record?.createdAt
        ChatConversation(agent, chat, conversation.id, conversation.createdAt, last ?: conversation.createdAt)
    }.sortedByDescending { it.lastActivity }

    override suspend fun entries(chat: ChatAddress, after: EntryId?, limit: Int, agent: AgentId?): TranscriptPage {
        require(limit >= 1) { "A page holds at least one entry, was $limit." }
        val key = if (agent == null) runner.key(chat) else topology.key(agent, chat)
        val conversation = conversations.existing(key) ?: return TranscriptPage(null, emptyList(), false)
        val entries = if (after == null) {
            transcripts.entries(conversation)
        } else {
            transcripts.entriesAfter(conversation, after)
        }
        return TranscriptPage(conversation, entries.take(limit), entries.size > limit)
    }
}

/** The current conversation of [key], null when it has none, without creating one. */
internal suspend fun ConversationStore.existing(key: AgentChatKey): ConversationId? =
    if (key.chat in chats(key.agent)) current(key).id else null
