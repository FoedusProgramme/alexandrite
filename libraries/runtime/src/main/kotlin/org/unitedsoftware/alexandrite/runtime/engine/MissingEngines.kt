package org.unitedsoftware.alexandrite.runtime.engine

import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import kotlin.reflect.KClass

/** The problems of the [enabled] plugins that need an engine port that the runtime has no default for and none binds. */
internal fun missingEngines(enabled: List<PluginSet.Member>, bindings: Map<String, List<Binding<*>>>): List<Problem> {
    val all = enabled.flatMap { bindings.getValue(it.id) }
    return WITHOUT_DEFAULT.filterKeys { port -> all.none { it.binds(port) } }.flatMap { (port, engines) ->
        enabled.filter { member -> bindings.getValue(member.id).any { it.needs(port) } }.map { member ->
            Problem(
                RuntimeProblemKind.MISSING_REQUIREMENT,
                "Missing engine: plugin '${member.id}' needs a ${port.java.simpleName}, which no enabled plugin " +
                    "binds. Put an engine that binds one on the class path: $engines.",
                member.id,
            )
        }
    }
}

/** The engine ports that the runtime binds no default for, with the engines that bind them. */
private val WITHOUT_DEFAULT: Map<KClass<*>, String> = mapOf(
    SqliteDriver::class to
        "alexandrite-engine-sqlite-xerial on a desktop JVM, or alexandrite-engine-sqlite-bundled on Android and on " +
        "a desktop JVM",
)

private fun Binding<*>.binds(port: KClass<*>): Boolean = !multi && key.qualifier == null && key.type.classifier == port

private fun Binding<*>.needs(port: KClass<*>): Boolean = dependencies.any {
    it.kind != DependencyKind.OPTIONAL && it.key.qualifier == null && it.key.type.classifier == port
}
