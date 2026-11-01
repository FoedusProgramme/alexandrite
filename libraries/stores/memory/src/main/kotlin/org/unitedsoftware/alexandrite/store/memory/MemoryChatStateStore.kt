package org.unitedsoftware.alexandrite.store.memory

import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore

@Singleton
@Binds(ChatStateStore::class)
internal class MemoryChatStateStore(private val data: StoreData) : ChatStateStore {
    override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? =
        data.locked { chatStates[ChatStateKey(plugin, name, agent, chat)] }

    override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {
        val key = ChatStateKey(plugin, name, agent, chat)
        data.locked { if (json == null) chatStates.remove(key) else chatStates[key] = json }
    }
}
