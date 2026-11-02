package org.unitedsoftware.alexandrite.engine.sqliteandroid

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.Test
import kotlin.test.assertIs

class AndroidSqliteDriverTest {
    @Test
    fun `the plugin binds its driver`() {
        runBlocking {
            PluginHarness.builder(AlexandriteEngineSqliteAndroidIndex()).build().run {
                assertIs<AndroidSqliteDriver>(get<SqliteDriver>())
            }
        }
    }
}
