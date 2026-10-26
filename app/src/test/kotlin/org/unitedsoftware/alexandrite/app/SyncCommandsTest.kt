package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `sync enable` / `disable` / `status`: the opt-in and its state file, no network (that is `SyncRunnerTest`'s job). */
class SyncCommandsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    @BeforeTest
    fun setUp() {
        cli = Commands(directory)
        cli.config(
            """{"providers": {"pleochroic": {"endpoints": {"default": {
                "baseUrl": "https://api.pleochroic.co.uk/v1", "apiKey": "${'$'}{env:PLEOCHROIC_KEY}"}}}}}""",
        )
        cli.environment = mapOf("PLEOCHROIC_KEY" to "plc-fixture-key")
    }

    private fun status(): JsonObject = Json.parseToJsonElement(cli("sync", "status", "--json").out).jsonObject

    private fun JsonObject.flag(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.content

    @Test
    fun `sync starts off, and status says so`() {
        val printed = status()

        assertEquals(false, printed.flag("enabled"))
        assertEquals(true, printed.flag("signedIn"))
    }

    @Test
    fun `enable turns sync on with the chosen policy, and disable turns it off again`() {
        val enabled = cli("sync", "enable", "--prefer", "remote")
        assertEquals(ExitCode.OK, enabled.code)
        assertContains(enabled.out + enabled.err, "remote")

        val afterEnable = status()
        assertEquals(true, afterEnable.flag("enabled"))
        assertEquals("remote", afterEnable.text("policy"))

        val disabled = cli("sync", "disable")
        assertEquals(ExitCode.OK, disabled.code)

        assertEquals(false, status().flag("enabled"))
    }

    @Test
    fun `enabling twice without disabling first fails`() {
        cli("sync", "enable", "--prefer", "merge")

        val again = cli("sync", "enable", "--prefer", "local")

        assertEquals(ExitCode.FAILURE, again.code)
    }

    @Test
    fun `an unknown policy is refused with a usage error`() {
        val printed = cli("sync", "enable", "--prefer", "whichever")

        assertEquals(ExitCode.USAGE, printed.code)
    }

    @Test
    fun `sync now before enabling is refused`() {
        val printed = cli("sync", "now")

        assertEquals(ExitCode.FAILURE, printed.code)
    }

    @Test
    fun `without a signed-in account, sync commands are refused`() {
        cli.config("{}")

        assertEquals(ExitCode.FAILURE, cli("sync", "enable").code)
        assertEquals(false, status().flag("signedIn"))
    }
}
