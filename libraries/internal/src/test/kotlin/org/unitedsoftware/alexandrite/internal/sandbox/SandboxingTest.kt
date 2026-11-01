package org.unitedsoftware.alexandrite.internal.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxingTest {
    private class Environment(val name: String, val available: Boolean = true)

    private fun select(type: String, vararg environments: Environment): Pair<String?, List<String>> {
        val missing = mutableListOf<String>()
        val selected = selectEnvironment(type, environments.toList(), { it.name }, { it.available }) { missing += it }
        return selected?.name to missing
    }

    @Test
    fun `auto takes the first available sandbox`() {
        assertEquals(
            "bubblewrap" to emptyList(),
            select(AUTO_SANDBOX, Environment("seatbelt", available = false), Environment("bubblewrap")),
        )
    }

    @Test
    fun `auto runs on the host, saying why, when no sandbox is contributed or none is available`() {
        assertEquals(null to listOf("no plugin contributes a sandbox"), select(AUTO_SANDBOX))
        assertEquals(
            null to listOf("none of the sandboxes seatbelt is available on this host"),
            select(AUTO_SANDBOX, Environment("seatbelt", available = false)),
        )
    }

    @Test
    fun `host runs on the host without a word`() {
        assertEquals(null to emptyList(), select(HOST_SANDBOX, Environment("seatbelt")))
    }

    @Test
    fun `a named sandbox that is not contributed or not available is refused`() {
        assertEquals(
            "no plugin contributes the sandbox 'seatbelt'. Sandboxes: none.",
            assertFailsWith<IllegalArgumentException> { select("seatbelt") }.message,
        )
        assertEquals(
            "the sandbox 'bubblewrap' is not available on this host",
            assertFailsWith<IllegalArgumentException> {
                select("bubblewrap", Environment("bubblewrap", available = false))
            }.message,
        )
    }

    @Test
    fun `a named sandbox is taken when it is available`() {
        assertEquals("seatbelt" to emptyList(), select("seatbelt", Environment("bubblewrap"), Environment("seatbelt")))
    }
}
