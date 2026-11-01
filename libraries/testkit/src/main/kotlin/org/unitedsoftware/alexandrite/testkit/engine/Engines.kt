package org.unitedsoftware.alexandrite.testkit.engine

import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.Resolver
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex

/** What [index] binds to [T], created from the bindings of [index] alone. */
public inline fun <reified T : Any> boundBy(index: PluginIndex): T = boundBy(index, key<T>())

/** What [index] binds to [key], created from the bindings of [index] alone. */
public fun <T : Any> boundBy(index: PluginIndex, key: Key<T>): T = IndexResolver(index).get(key)

/** Creates what [index] binds, each time it is asked for, from the bindings of [index] alone. */
private class IndexResolver(private val index: PluginIndex) : Resolver {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: Key<T>): T = index.bindings().single { it.key == key }.create(this) as T

    override fun <T : Any> getOrNull(key: Key<T>): T? = null

    override fun <T : Any> getAll(key: Key<T>): List<T> = emptyList()

    override fun <T : Any> lazy(key: Key<T>): Lazy<T> = lazy { get(key) }

    override fun <T : Any> provider(key: Key<T>): () -> T = { get(key) }

    override fun pluginOf(instance: Any): String? = null
}
