package org.unitedsoftware.alexandrite.store.memory

import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import java.time.Clock

/** A plugin that binds the memory store's ports over one [StoreData], which outlives the runtimes it is given to. */
internal class SharedStore(clock: Clock = Clock.systemUTC()) : PluginIndex {
    private val data = StoreData(clock)
    private val conversations = MemoryConversationStore(data)
    private val transcripts = MemoryTranscriptStore(data)
    private val media = MemoryMediaStore(data)
    private val chatStates = MemoryChatStateStore(data)
    private val records = MemoryRecordStore(data)

    override val info: PluginInfo =
        PluginInfo(ID, "Shared store", "test", "", AlexandriteSdk.API_VERSION, emptyList(), javaClass.name)

    override val configRoot: String = PluginIds.thirdPartyRoot(ID)

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()

    override fun bindings(): List<Binding<*>> = listOf(
        instanceBinding(key<ConversationStore>(), conversations, ID, "conversations"),
        instanceBinding(key<TranscriptStore>(), transcripts, ID, "transcripts"),
        instanceBinding(key<MediaStore>(), media, ID, "media"),
        instanceBinding(key<ChatStateStore>(), chatStates, ID, "chat states"),
        instanceBinding(key<RecordStore>(), records, ID, "records"),
    )

    private companion object {
        const val ID = "shared-store"
    }
}
