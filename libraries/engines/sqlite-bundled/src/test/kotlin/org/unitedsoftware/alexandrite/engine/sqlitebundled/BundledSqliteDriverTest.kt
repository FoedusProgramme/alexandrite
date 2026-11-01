package org.unitedsoftware.alexandrite.engine.sqlitebundled

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.SQLITE_DRIVER_CONTRACT
import kotlin.test.Test
import kotlin.test.assertIs

class BundledSqliteDriverTest {
    @TestFactory
    fun `the driver keeps the SQLite driver contract`(): List<DynamicTest> = SQLITE_DRIVER_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) { check.run(BundledSqliteDriver()) }
    }

    @Test
    fun `the plugin binds the driver`() {
        runBlocking {
            PluginHarness.builder(AlexandriteEngineSqliteBundledIndex()).build().run {
                assertIs<BundledSqliteDriver>(get<SqliteDriver>())
            }
        }
    }
}
