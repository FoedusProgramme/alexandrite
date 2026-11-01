package org.unitedsoftware.alexandrite.store.memory

import org.unitedsoftware.alexandrite.internal.id.newId
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationKind
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.store.ForkPoint
import org.unitedsoftware.alexandrite.sdk.store.Rotation
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.store.TurnRecord
import org.unitedsoftware.alexandrite.sdk.store.rebuild
import java.time.Instant
import java.time.temporal.ChronoUnit

@Singleton
@Binds(ConversationStore::class)
internal class MemoryConversationStore(private val data: StoreData) : ConversationStore {
    override suspend fun current(key: AgentChatKey): ConversationInfo =
        data.locked { now -> currentOf(key, ConversationKind.USER_LANE, now) }

    override suspend fun heartbeatBase(key: AgentChatKey): ConversationInfo =
        data.locked { now -> currentOf(key, ConversationKind.HEARTBEAT_BASE, now) }

    override suspend fun newConversation(key: AgentChatKey): Rotation = data.locked { now ->
        val old = current[key to ConversationKind.USER_LANE]?.let(conversations::getValue)
        check(old == null || old.state == ConversationState.ACTIVE) {
            "The current conversation ${old?.id} of $key is not active."
        }
        val successor = insert(ConversationKind.USER_LANE, key, lineage = null, fork = null, now)
        val sealed = old?.rebuild {
            state(ConversationState.SEALED)
            sealedAt(now)
            successor(successor.id)
        }
        sealed?.let { conversations[it.id] = it }
        current[key to ConversationKind.USER_LANE] = successor.id
        Rotation(sealed, successor)
    }

    override suspend fun createDelegated(key: AgentChatKey, lineage: TurnLineage, fork: ForkPoint?): ConversationInfo =
        data.locked { now ->
            val parent = turns[lineage.parentTurn]
                ?: throw IllegalArgumentException("The parent turn ${lineage.parentTurn} is not recorded.")
            require(parent.conversation == lineage.parentConversation) {
                "The parent turn ${parent.id} ran in conversation ${parent.conversation}, not in " +
                    "${lineage.parentConversation}."
            }
            require(parent.key.chat == key.chat) {
                "A delegated conversation belongs to the chat of its parent turn, ${parent.key.chat}, not to " +
                    "${key.chat}."
            }
            require(
                lineage.depth == (parent.lineage?.depth ?: 0) + 1 &&
                    lineage.rootTurn == (parent.lineage?.rootTurn ?: parent.id) &&
                    lineage.rootConversation == (parent.lineage?.rootConversation ?: parent.conversation),
            ) { "The lineage of run ${lineage.run} does not descend from its parent turn ${parent.id}." }
            require(fork == null || fork.conversation in conversations) {
                "Cannot fork the unknown ${fork?.conversation}."
            }
            insert(ConversationKind.DELEGATED, key, lineage, fork, now)
        }

    override suspend fun sealDelegated(id: ConversationId): Boolean = data.locked { now ->
        val conversation = requireNotNull(conversations[id]) { "Cannot seal the unknown conversation $id." }
        require(conversation.kind == ConversationKind.DELEGATED) {
            "Conversation $id is ${conversation.kind}, not the conversation of a sub-agent run."
        }
        if (conversation.state != ConversationState.ACTIVE) return@locked false
        conversations[id] = conversation.rebuild {
            state(ConversationState.SEALED)
            sealedAt(now)
        }
        true
    }

    override suspend fun conversation(id: ConversationId): ConversationInfo? = data.locked { conversations[id] }

    override suspend fun chats(agent: AgentId): List<ChatAddress> = data.locked {
        conversations.values.asSequence()
            .filter { it.key.agent == agent && it.kind == ConversationKind.USER_LANE }
            .map { it.key.chat }
            .distinct()
            .toList()
    }

    override suspend fun startTurn(turn: TurnInfo) {
        data.locked { now ->
            val conversation = requireNotNull(conversations[turn.conversation]) {
                "Turn ${turn.id} names the unknown conversation ${turn.conversation}."
            }
            require(conversation.key == turn.key) {
                "Turn ${turn.id} of ${turn.key} cannot run in conversation ${conversation.id} of ${conversation.key}."
            }
            require(turn.id !in turns) { "Turn ${turn.id} is recorded already." }
            check(conversation.state == ConversationState.ACTIVE) {
                "Conversation ${conversation.id} is ${conversation.state}, so it takes no new turns."
            }
            turns[turn.id] = TurnRecord.builder(turn.id, turn.conversation, turn.key, turn.kind, now)
                .chat(turn.chat)
                .actor(turn.actor?.address)
                .lineage(turn.lineage)
                .build()
        }
    }

    override suspend fun endTurn(id: TurnId, end: TurnEndKind): Boolean = data.locked { now ->
        val turn = turns[id]?.takeIf { it.endedAt == null } ?: return@locked false
        turns[id] = turn.toBuilder().endedAt(now).end(end).build()
        true
    }

    override suspend fun turn(id: TurnId): TurnRecord? = data.locked { turns[id] }

    override suspend fun unendedTurns(): List<TurnRecord> =
        data.locked { turns.values.filter { it.endedAt == null }.sortedBy { it.startedAt } }

    override suspend fun markTainted(conversation: ConversationId, reason: String, at: Instant): Boolean = data.locked {
        if (conversation !in conversations || conversation in taints) return@locked false
        taints[conversation] = ConversationTaint(at.truncatedTo(ChronoUnit.MILLIS), reason)
        true
    }

    override suspend fun taint(conversation: ConversationId): ConversationTaint? = data.locked { taints[conversation] }

    override suspend fun clearTaint(conversation: ConversationId): ConversationTaint? =
        data.locked { taints.remove(conversation) }

    private fun StoreData.currentOf(key: AgentChatKey, kind: ConversationKind, now: Instant): ConversationInfo {
        val id = current.getOrPut(key to kind) { insert(kind, key, lineage = null, fork = null, now).id }
        return conversations.getValue(id)
    }

    private fun StoreData.insert(
        kind: ConversationKind,
        key: AgentChatKey,
        lineage: TurnLineage?,
        fork: ForkPoint?,
        now: Instant,
    ): ConversationInfo {
        val info = ConversationInfo.builder(ConversationId(newId()), kind, key, ConversationState.ACTIVE, now)
            .lineage(lineage)
            .fork(fork)
            .build()
        conversations[info.id] = info
        return info
    }
}
