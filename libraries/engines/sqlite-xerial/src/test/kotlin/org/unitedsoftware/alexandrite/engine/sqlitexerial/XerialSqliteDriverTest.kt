package org.unitedsoftware.alexandrite.engine.sqlitexerial

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.SQLITE_DRIVER_CONTRACT
import kotlin.test.Test
import kotlin.test.assertIs

class XerialSqliteDriverTest {
    @TestFactory
    fun `the xerial driver keeps the SQLite driver contract`(): List<DynamicTest> =
        SQLITE_DRIVER_CONTRACT.map { check ->
            DynamicTest.dynamicTest(check.name) { check.run(XerialSqliteDriver()) }
        }

    @Test
    fun `the plugin binds its driver`() {
        runBlocking {
            PluginHarness.builder(AlexandriteEngineSqliteXerialIndex()).build().run {
                assertIs<XerialSqliteDriver>(get<SqliteDriver>())
            }
        }
    }
}
