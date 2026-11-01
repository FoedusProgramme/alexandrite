package org.unitedsoftware.alexandrite.runtime.engine

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.internal.http.JdkHttpEngine
import org.unitedsoftware.alexandrite.runtime.Events
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.TestIndex
import org.unitedsoftware.alexandrite.runtime.execute
import org.unitedsoftware.alexandrite.runtime.explicit
import org.unitedsoftware.alexandrite.runtime.spec
import org.unitedsoftware.alexandrite.runtime.startFailure
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.PluginBindings
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class EnginePortsTest {
    @TempDir
    lateinit var dataDir: Path

    private val events = Events()

    private class OwnEngine : HttpEngine {
        override suspend fun open(request: HttpEngineRequest): HttpEngineResponse = error("never sent")
    }

    private fun engine(plugin: String, make: () -> HttpEngine = ::OwnEngine): Binding<HttpEngine> =
        binding(key<HttpEngine>(), plugin, "engine of $plugin") {
            events.record("create $plugin")
            make()
        }

    /** A binding of [plugin] that needs an engine as [kind]. */
    private fun client(plugin: String, kind: DependencyKind = DependencyKind.INSTANCE): Binding<String> = binding(
        key<String>("client"),
        plugin,
        "client of $plugin",
        dependencies = listOf(Dependency(key<HttpEngine>(), kind, "engine")),
    ) { "client" }

    @Test
    fun `the runtime binds the JDK engine when no plugin binds one`() {
        var engine: HttpEngine? = null

        spec(explicit(TestIndex("probe")), dataDir).execute { engine = services.resolver().get(key<HttpEngine>()) }

        assertIs<JdkHttpEngine>(engine)
    }

    @Test
    fun `the engine a plugin binds replaces the JDK default`() {
        val own = OwnEngine()
        var engine: HttpEngine? = null
        val plugins = explicit(TestIndex("okhttp", bindings = listOf(engine("okhttp") { own })), TestIndex("probe"))

        spec(plugins, dataDir).execute { engine = services.resolver().get(key<HttpEngine>()) }

        assertSame(own, engine)
    }

    @Test
    fun `two enabled plugins that bind an engine port fail CONFIG, naming both, before anything is created`() {
        val plugins = explicit(
            TestIndex("okhttp", bindings = listOf(engine("okhttp"))),
            TestIndex("ktor", bindings = listOf(engine("ktor"))),
            TestIndex("probe"),
        )

        val error = spec(plugins, dataDir).startFailure()

        assertEquals(StartStage.CONFIG, error.stage)
        val problem = error.problems.single()
        assertEquals(RuntimeProblemKind.DUPLICATE_ENGINE, problem.kind)
        assertContains(problem.message, "'okhttp'")
        assertContains(problem.message, "'ktor'")
        assertContains(problem.message, "HttpEngine")
        assertEquals(emptyList(), events.all())
    }

    @Test
    fun `a disabled engine plugin leaves the port to the other`() {
        val plugins = explicit(
            TestIndex("okhttp", bindings = listOf(engine("okhttp"))),
            TestIndex("ktor", bindings = listOf(engine("ktor"))),
        )

        spec(plugins, dataDir, """{"plugins": {"ktor": {"enabled": false}}}""").execute()

        assertEquals(listOf("create okhttp"), events.all())
    }

    @Test
    fun `a port without a JDK default that a plugin needs names the port, the plugins and an engine to add`() {
        val port = EnginePort(key<HttpEngine>(), listOf("alexandrite-engine-okhttp"), jdkDefault = null)
        val plugins = explicit(
            TestIndex("web", bindings = listOf(client("web"))),
            TestIndex("mcp", bindings = listOf(client("mcp", DependencyKind.LAZY))),
            TestIndex("maybe", bindings = listOf(client("maybe", DependencyKind.OPTIONAL))),
        ).members
        val bindings = plugins.associate { it.id to it.index.bindings() }

        val problem = engineProblems(plugins, bindings, listOf(port)).single()
        val unneeded = engineProblems(plugins.filter { it.id == "maybe" }, bindings, listOf(port))

        assertEquals(RuntimeProblemKind.MISSING_ENGINE, problem.kind)
        assertEquals(
            "Missing engine: plugin 'web' and plugin 'mcp' need HttpEngine, but no enabled plugin binds it and " +
                "this host has no JDK default for it. Add an engine plugin that binds it, such as " +
                "alexandrite-engine-okhttp.",
            problem.message,
        )
        assertEquals(emptyList(), unneeded)
        assertEquals(emptyList(), engineDefaults(listOf(PluginBindings("web", bindings.getValue("web"))), listOf(port)))
    }
}
