package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * `sync now` end to end, through a loopback [FakeModelServer] standing in for the account's `apiBase`: the glue this
 * round added (`CliHostConfig`, the [org.unitedsoftware.alexandrite.account.pleochroic.SyncRunner] call in [Sync.kt])
 * rather than the engine's own decisions, which `libraries/accounts/pleochroic`'s own `SyncRunnerTest` covers.
 */
class SyncNowTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var server: FakeModelServer
    private lateinit var cli: Commands

    @BeforeTest
    fun setUp() {
        server = FakeModelServer()
        cli = Commands(directory)
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun signedInConfig(extra: String = "") = """{"providers": {"pleochroic": {"endpoints": {"default": {
        "baseUrl": "${server.baseUrl}", "apiKey": "${'$'}{env:PLEOCHROIC_KEY}"}}}}$extra}"""

    private fun file(): JsonObject = Json.parseToJsonElement(cli.config()).jsonObject

    @Test
    fun `sync now pushes a new local config item to the account's apiBase`() {
        cli.config(
            """{"providers": {"pleochroic": {"endpoints": {"default": {"baseUrl": "${server.baseUrl}",
                "apiKey": "${'$'}{env:PLEOCHROIC_KEY}"}}}, "openrouter": {"endpoints": {"work": {
                "baseUrl": "https://openrouter.ai/api/v1", "apiKey": "${'$'}{env:OPENROUTER_KEY}"}}}}}""",
        )
        cli.environment = mapOf("PLEOCHROIC_KEY" to "plc-fixture-key", "OPENROUTER_KEY" to "or-fixture-key")
        assertEquals(0, cli("sync", "enable").code)
        server.enqueue(fakeResponse { body("""{"changes": [], "cursor": 0, "more": false}""") }, "/sync/changes")
        server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/config/provider/openrouter%2Fwork")
        // The signed-in account's own provider endpoint is itself a tracked config item, pushed alongside it.
        server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/config/provider/pleochroic%2Fdefault")

        val printed = cli("sync", "now")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "2 pushed")
        val request = server.requests.single { it.path == "/config/provider/openrouter%2Fwork" }
        assertEquals("Bearer plc-fixture-key", request.header("authorization"))
        assertContains(request.body, "openrouter.ai")
    }

    @Test
    fun `sync now pulls a brand new remote mcp server into the config file`() {
        cli.config(signedInConfig())
        cli.environment = mapOf("PLEOCHROIC_KEY" to "plc-fixture-key")
        assertEquals(0, cli("sync", "enable").code)
        server.enqueue(
            fakeResponse {
                body(
                    """{"changes": [{"seq": 1, "area": "config", "kind": "mcp", "id": "notes", "rev": "rev-9",
                        "deleted": false}], "cursor": 1, "more": false}""",
                )
            },
            "/sync/changes",
        )
        server.enqueue(
            fakeResponse { body("""{"rev": "rev-9", "data": {"command": "mcp-server"}}""") },
            "/config/mcp/notes",
        )
        // The signed-in account's own provider endpoint is itself a tracked config item, pushed in the same run.
        server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/config/provider/pleochroic%2Fdefault")

        val printed = cli("sync", "now")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "1 pushed")
        assertContains(printed.out, "1 pulled")
        assertEquals("\"mcp-server\"", "${file().at("mcp.servers.notes.command")}")
    }

    @Test
    fun `sync now pushes a new secret as the service's one-field value document`() {
        cli.config(signedInConfig())
        cli.environment = mapOf("PLEOCHROIC_KEY" to "plc-fixture-key")
        writeSecret(cli.secrets, "MY_TOKEN", "hunter2")
        assertEquals(0, cli("sync", "enable").code)
        server.enqueue(fakeResponse { body("""{"changes": [], "cursor": 0, "more": false}""") }, "/sync/changes")
        server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/secrets/MY_TOKEN")
        // The signed-in account's own provider endpoint is itself a tracked config item, pushed alongside it.
        server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/config/provider/pleochroic%2Fdefault")

        val printed = cli("sync", "now")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "2 pushed")
        val request = server.requests.single { it.path == "/secrets/MY_TOKEN" }
        assertContains(request.body, "hunter2")
    }
}

/** Appends a `NAME=value` line to [file], creating it if it does not exist yet. */
private fun writeSecret(file: Path, name: String, value: String) {
    Files.createDirectories(file.parent)
    Files.writeString(file, "$name=$value\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}
