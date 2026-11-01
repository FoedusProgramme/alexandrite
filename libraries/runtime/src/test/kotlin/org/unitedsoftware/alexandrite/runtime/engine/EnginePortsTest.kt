package org.unitedsoftware.alexandrite.runtime.engine

import org.junit.jupiter.api.io.TempDir
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
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineRequest
import org.unitedsoftware.alexandrite.sdk.net.HttpEngineResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

    /** A plugin [id] with a binding that needs an engine as [kind]. */
    private fun client(id: String, kind: DependencyKind = DependencyKind.INSTANCE) = TestIndex(
        id,
        bindings = listOf(
            binding(
                key<String>("client"),
                id,
                "client of $id",
                dependencies = listOf(
                    Dependency(key<HttpEngine>(), kind, "engine"),
                    Dependency(key<ImageCodec>(), DependencyKind.OPTIONAL, "codec"),
                ),
            ) { "client" },
        ),
    )

    @Test
    fun `the engine a plugin binds serves the plugins that need one`() {
        val own = OwnEngine()
        var engine: HttpEngine? = null
        val plugins = explicit(TestIndex("okhttp", bindings = listOf(engine("okhttp") { own })), client("web"))

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
    fun `each plugin that needs a port no plugin binds fails CONFIG, naming the port and the engines that bind it`() {
        val error = spec(explicit(client("web"), client("mcp", DependencyKind.LAZY)), dataDir).startFailure()

        assertEquals(StartStage.CONFIG, error.stage)
        assertEquals(
            listOf(RuntimeProblemKind.MISSING_ENGINE to "mcp", RuntimeProblemKind.MISSING_ENGINE to "web"),
            error.problems.map { it.kind to it.plugin },
        )
        val message = error.problems.first { it.plugin == "web" }.message
        assertContains(message, "plugin 'web' needs HttpEngine, which no enabled plugin binds")
        assertContains(message, "alexandrite-engine-jdk-http on a desktop JVM")
        assertContains(message, "alexandrite-engine-okhttp")
    }

    @Test
    fun `a plugin that takes a port only when one is bound starts without an engine`() {
        spec(explicit(client("web", DependencyKind.OPTIONAL)), dataDir).execute {}
    }
}
