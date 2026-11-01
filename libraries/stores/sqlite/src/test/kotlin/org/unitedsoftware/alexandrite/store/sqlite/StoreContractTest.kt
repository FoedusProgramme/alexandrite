package org.unitedsoftware.alexandrite.store.sqlite

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.STORE_CONTRACT

class StoreContractTest {
    @TestFactory
    fun `the SQLite store keeps the store contract`(): List<DynamicTest> = STORE_CONTRACT.map { check ->
        DynamicTest.dynamicTest(check.name) {
            runBlocking { check.run { PluginHarness.builder(AlexandriteStoreSqliteIndex()).plugin(driverIndex) } }
        }
    }
}
