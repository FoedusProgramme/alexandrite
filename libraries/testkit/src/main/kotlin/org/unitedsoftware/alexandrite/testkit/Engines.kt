package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.Resolver
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex

/** What [index] binds to [T], created from the bindings of [index] alone. */
public inline fun <reified T : Any> boundBy(index: PluginIndex): T = boundBy(index, key<T>())

@PublishedApi
internal fun <T : Any> boundBy(index: PluginIndex, key: Key<T>): T = IndexResolver(index).get(key)

/** Creates what [index] binds, each time it is asked for, from the bindings of [index] alone. */
private class IndexResolver(private val index: PluginIndex) : Resolver {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: Key<T>): T =
        index.bindings().single { it.key == key && !it.multi }.create(this) as T

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getOrNull(key: Key<T>): T? =
        index.bindings().singleOrNull { it.key == key && !it.multi }?.create(this) as T?

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getAll(key: Key<T>): List<T> =
        index.bindings().filter { it.key == key && it.multi }.map { it.create(this) as T }

    override fun <T : Any> lazy(key: Key<T>): Lazy<T> = lazy { get(key) }

    override fun <T : Any> provider(key: Key<T>): () -> T = { get(key) }

    override fun pluginOf(instance: Any): String? = null
}
