package org.unitedsoftware.alexandrite.store.memory

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.STORE_CONTRACT

class StoreContractTest {
    @TestFactory
    fun `the memory store keeps the store contract while its data outlives the runtimes`(): List<DynamicTest> =
        STORE_CONTRACT.map { check ->
            DynamicTest.dynamicTest(check.name) {
                val store = SharedStore()
                runBlocking { check.run { PluginHarness.builder(store) } }
            }
        }
}
