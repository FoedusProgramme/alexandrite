package org.unitedsoftware.alexandrite.runtime.engine

import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import kotlin.reflect.KClass

/** An engine port of the SDK, which one plugin at most binds, with the [engines] that bind it on each host. */
internal class EnginePort(val port: KClass<*>, val engines: String) {
    val name: String = port.java.simpleName
}

internal val ENGINE_PORTS: List<EnginePort> = listOf(
    EnginePort(
        HttpEngine::class,
        "alexandrite-engine-jdk-http on a desktop JVM, or alexandrite-engine-okhttp on Android and on a desktop JVM",
    ),
    EnginePort(
        HtmlParser::class,
        "alexandrite-engine-swing-html on a desktop JVM, or alexandrite-engine-jsoup on Android",
    ),
    EnginePort(ImageCodec::class, "alexandrite-engine-awt-images on a desktop JVM"),
    EnginePort(
        SqliteDriver::class,
        "alexandrite-engine-sqlite-xerial on a desktop JVM, or alexandrite-engine-sqlite-bundled on Android and on a " +
            "desktop JVM",
    ),
)

/**
 * The problems of the [ports] that several [enabled] plugins bind, and of each enabled plugin that needs a port no
 * plugin binds.
 */
internal fun engineProblems(
    enabled: List<PluginSet.Member>,
    bindings: Map<String, List<Binding<*>>>,
    ports: List<EnginePort> = ENGINE_PORTS,
): List<Problem> = ports.flatMap { port ->
    val binders = enabled.filter { member -> bindings.getValue(member.id).any { port.boundBy(it) } }
    when {
        binders.size > 1 -> {
            val described = binders.joinToString(" and ") { "plugin '${it.id}' (root '${it.index.configRoot}')" }
            val message =
                "Duplicate engine: $described bind ${port.name}, but one plugin at most provides an engine. " +
                    "Switch all but one of them off with `<root>.${PluginIds.ENABLED_KEY} = false`."
            listOf(Problem(RuntimeProblemKind.DUPLICATE_ENGINE, message, null))
        }

        binders.isEmpty() -> enabled.filter { member -> bindings.getValue(member.id).any { port.neededBy(it) } }
            .map { member ->
                Problem(
                    RuntimeProblemKind.MISSING_ENGINE,
                    "Missing engine: plugin '${member.id}' needs ${port.name}, which no enabled plugin binds. Put an " +
                        "engine that binds it on the class path: ${port.engines}.",
                    member.id,
                )
            }

        else -> emptyList()
    }
}

private fun EnginePort.boundBy(binding: Binding<*>): Boolean =
    !binding.multi && binding.key.qualifier == null && binding.key.type.classifier == port

private fun EnginePort.neededBy(binding: Binding<*>): Boolean = binding.dependencies.any {
    it.kind != DependencyKind.OPTIONAL && it.kind != DependencyKind.ALL && it.key.qualifier == null &&
        it.key.type.classifier == port
}
