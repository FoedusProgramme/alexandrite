package org.unitedsoftware.alexandrite.runtime.engine

import org.junit.jupiter.api.io.TempDir
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
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MissingEnginesTest {
    @TempDir
    lateinit var dataDir: Path

    private class Database

    private fun needing(kind: DependencyKind): TestIndex = TestIndex(
        "files",
        bindings = listOf(
            binding(
                key<Database>(),
                "files",
                "Database",
                dependencies = listOf(Dependency(key<SqliteDriver>(), kind, "driver")),
            ) {
                Database()
            },
        ),
    )

    private fun driver(): TestIndex = TestIndex(
        "engine",
        bindings = listOf<Binding<*>>(
            binding(key<SqliteDriver>(), "engine", "driver") {
                object : SqliteDriver {
                    override fun open(path: String): SqliteConnection = error("never opened")
                }
            },
        ),
    )

    @Test
    fun `a plugin that needs a SqliteDriver that no plugin binds fails CONFIG, naming the engines that bind one`() {
        val error = spec(explicit(needing(DependencyKind.INSTANCE)), dataDir).startFailure()

        assertEquals(StartStage.CONFIG, error.stage)
        val problem = error.problems.single()
        assertEquals(RuntimeProblemKind.MISSING_REQUIREMENT to "files", problem.kind to problem.plugin)
        assertContains(problem.message, "plugin 'files' needs a SqliteDriver")
        assertContains(problem.message, "alexandrite-engine-sqlite-bundled")
    }

    @Test
    fun `a plugin that takes a SqliteDriver when there is one, or that an engine serves, starts`() {
        spec(explicit(needing(DependencyKind.OPTIONAL)), dataDir).execute {}
        spec(explicit(needing(DependencyKind.LAZY), driver()), dataDir).execute {}
    }
}
