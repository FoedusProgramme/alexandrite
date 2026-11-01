package org.unitedsoftware.alexandrite.runtime.store

import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.runtime.plugin.binds
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import kotlin.reflect.KClass

/** The problem of several [enabled] plugins binding store ports, null when one at most does. */
internal fun duplicateStores(enabled: List<PluginSet.Member>, bindings: Map<String, List<Binding<*>>>): Problem? {
    val stores = stores(enabled, bindings)
    if (stores.size < 2) return null
    val described = stores.joinToString(" and ") { (member, ports) ->
        "plugin '${member.id}' (root '${member.index.configRoot}') binds ${ports.joinToString { it.java.simpleName }}"
    }
    return Problem(
        RuntimeProblemKind.DUPLICATE_STORE,
        "Duplicate store: $described, but one plugin provides the whole store. Switch all but one of them off with " +
            "`<root>.${PluginIds.ENABLED_KEY} = false`.",
        null,
    )
}

/**
 * The warning that the one plugin of [enabled] that binds store ports leaves records or chat states to memory, null
 * when it binds both.
 */
internal fun partialStore(enabled: List<PluginSet.Member>, bindings: Map<String, List<Binding<*>>>): String? {
    val (member, ports) = stores(enabled, bindings).singleOrNull() ?: return null
    val missing = MEMORY_FALLBACKS.filterKeys { it !in ports }
    if (missing.isEmpty()) return null
    return "plugin '${member.id}' provides the store but binds no ${missing.keys.joinToString {
        it.java.simpleName
    }}: " +
        "${missing.values.joinToString(" and ")} live in memory and are lost at a restart"
}

private fun stores(
    enabled: List<PluginSet.Member>,
    bindings: Map<String, List<Binding<*>>>,
): List<Pair<PluginSet.Member, List<KClass<*>>>> = enabled.mapNotNull { member ->
    val ports = STORE_PORTS.filter { port -> bindings.getValue(member.id).any { it.binds(port) } }
    if (ports.isEmpty()) null else member to ports
}

/** The ports that the runtime keeps in memory when no plugin binds them, with what they keep. */
private val MEMORY_FALLBACKS: Map<KClass<*>, String> = mapOf(
    RecordStore::class to "the plugins' records, runtime grants among them,",
    ChatStateStore::class to "chat states",
)

private val STORE_PORTS: List<KClass<*>> =
    listOf(
        ConversationStore::class,
        TranscriptStore::class,
        MediaStore::class,
        ChatStateStore::class,
        RecordStore::class,
    )
