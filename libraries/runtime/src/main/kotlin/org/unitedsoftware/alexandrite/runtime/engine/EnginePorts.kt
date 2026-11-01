package org.unitedsoftware.alexandrite.runtime.engine

import org.unitedsoftware.alexandrite.internal.http.JdkHttpEngine
import org.unitedsoftware.alexandrite.internal.portable.JdkFeatures
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.lifecycle.RUNTIME_PLUGIN
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.PluginBindings
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import kotlin.reflect.KClass

/** An engine port of the SDK, which one plugin at most binds and the runtime binds its JDK default for otherwise. */
internal class EnginePort<T : Any>(
    val key: Key<T>,
    /** Alexandrite's engine plugins that bind it, which the problem of a missing engine suggests. */
    val engines: List<String>,
    /** The JDK default, null where the port has none or this process lacks the JDK API it needs. */
    val jdkDefault: (() -> T)?,
) {
    val name: String = (key.type.classifier as KClass<*>).java.simpleName
}

internal val ENGINE_PORTS: List<EnginePort<*>> = listOf(
    EnginePort(
        key<HttpEngine>(),
        engines = listOf("alexandrite-engine-okhttp"),
        jdkDefault = if (JdkFeatures.httpClient) ::JdkHttpEngine else null,
    ),
)

/**
 * The problems of the [ports] that several [enabled] plugins bind, and of those that none binds and that have no JDK
 * default while a plugin needs them.
 */
internal fun engineProblems(
    enabled: List<PluginSet.Member>,
    bindings: Map<String, List<Binding<*>>>,
    ports: List<EnginePort<*>> = ENGINE_PORTS,
): List<Problem> = ports.mapNotNull { port ->
    val binders = enabled.filter { member -> bindings.getValue(member.id).any { port.boundBy(it) } }
    when {
        binders.size > 1 -> {
            val described = binders.joinToString(" and ") { "plugin '${it.id}' (root '${it.index.configRoot}')" }
            Problem(
                RuntimeProblemKind.DUPLICATE_ENGINE,
                "Duplicate engine: $described bind ${port.name}, but one plugin at most provides an engine. Switch " +
                    "all but one of them off with `<root>.${PluginIds.ENABLED_KEY} = false`.",
                null,
            )
        }

        binders.isEmpty() && port.jdkDefault == null -> {
            val needing = enabled.filter { member -> bindings.getValue(member.id).any { port.neededBy(it) } }
            if (needing.isEmpty()) return@mapNotNull null
            val suggested = port.engines.takeIf { it.isNotEmpty() }?.joinToString(" or ", prefix = ", such as ")
            Problem(
                RuntimeProblemKind.MISSING_ENGINE,
                "Missing engine: ${needing.joinToString(" and ") { "plugin '${it.id}'" }} " +
                    "need${if (needing.size == 1) "s" else ""} ${port.name}, but no enabled plugin binds it and " +
                    "this host has no JDK default for it. Add an engine plugin that binds it${suggested.orEmpty()}.",
                null,
            )
        }

        else -> null
    }
}

/** The bindings of the JDK defaults of the [ports] that none of [plugins] binds. */
internal fun engineDefaults(
    plugins: List<PluginBindings>,
    ports: List<EnginePort<*>> = ENGINE_PORTS,
): List<Binding<*>> = ports
    .filter { port -> plugins.none { plugin -> plugin.bindings.any { port.boundBy(it) } } }
    .mapNotNull { it.defaultBinding() }

private fun <T : Any> EnginePort<T>.defaultBinding(): Binding<T>? {
    val default = jdkDefault ?: return null
    return binding(key, RUNTIME_PLUGIN, "the JDK default of $name") { default() }
}

private fun EnginePort<*>.boundBy(binding: Binding<*>): Boolean =
    !binding.multi && binding.key.qualifier == null && binding.key.type.classifier == key.type.classifier

private fun EnginePort<*>.neededBy(binding: Binding<*>): Boolean = binding.dependencies.any {
    it.kind != DependencyKind.OPTIONAL && it.kind != DependencyKind.ALL && it.key.qualifier == null &&
        it.key.type.classifier == key.type.classifier
}
