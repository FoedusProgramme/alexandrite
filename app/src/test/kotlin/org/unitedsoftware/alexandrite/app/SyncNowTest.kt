package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNull

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
    fun `sync now stages a brand new remote mcp server rather than pulling it in`() {
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
        assertContains(printed.out, "0 pulled")
        assertContains(printed.out, "held back for review")
        assertNull(file().at("mcp.servers.notes"))

        val review = cli("sync", "review", "--json")
        val key = Json.parseToJsonElement(review.out).jsonObject["pending"]!!.jsonArray.single().jsonObject["key"]!!
            .jsonPrimitive.content

        val applied = cli("sync", "apply", key)

        assertEquals(0, applied.code, applied.err)
        assertEquals("\"mcp-server\"", "${file().at("mcp.servers.notes.command")}")
        assertEquals(
            0,
            cli("sync", "review", "--json").let {
                Json.parseToJsonElement(it.out).jsonObject["pending"]!!.jsonArray.size
            },
        )
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

    @Test
    fun `sync now --dry-run reports what would happen but writes and stages nothing`() {
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

        val printed = cli("sync", "now", "--dry-run")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "Sync would do")
        assertNull(file().at("mcp.servers.notes"))
        assertEquals(
            0,
            cli("sync", "review", "--json").let {
                Json.parseToJsonElement(it.out).jsonObject["pending"]!!.jsonArray.size
            },
        )
        assertEquals(
            0,
            cli("sync", "status", "--json").let {
                Json.parseToJsonElement(it.out).jsonObject["cursor"]?.jsonPrimitive?.content?.toLong() ?: -1L
            },
        )
    }

    @Test
    fun `sync discard leaves a pending change unapplied, and sync review no longer shows it`() {
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
        server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/config/provider/pleochroic%2Fdefault")
        cli("sync", "now")
        val key = Json.parseToJsonElement(cli("sync", "review", "--json").out)
            .jsonObject["pending"]!!.jsonArray.single().jsonObject["key"]!!.jsonPrimitive.content

        val discarded = cli("sync", "discard", key)

        assertEquals(0, discarded.code, discarded.err)
        assertNull(file().at("mcp.servers.notes"))
        assertEquals(
            0,
            cli("sync", "review", "--json").let {
                Json.parseToJsonElement(it.out).jsonObject["pending"]!!.jsonArray.size
            },
        )
    }

    @Test
    fun `sync apply and discard need either --all or named keys, not both and not neither`() {
        cli.config(signedInConfig())
        cli.environment = mapOf("PLEOCHROIC_KEY" to "plc-fixture-key")

        assertEquals(ExitCode.USAGE, cli("sync", "apply").code)
        assertEquals(ExitCode.USAGE, cli("sync", "apply", "--all", "some-key").code)
        assertEquals(ExitCode.USAGE, cli("sync", "discard").code)
        assertEquals(ExitCode.USAGE, cli("sync", "discard", "--all", "some-key").code)
    }

    @Test
    fun `sync apply of an unknown key is a usage-level failure, naming it`() {
        cli.config(signedInConfig())
        cli.environment = mapOf("PLEOCHROIC_KEY" to "plc-fixture-key")

        val printed = cli("sync", "apply", "no-such-key")

        assertEquals(ExitCode.FAILURE, printed.code)
        assertContains(printed.err, "no-such-key")
    }

    @Test
    fun `a brand new remote skill is staged, with its added file listed in the review`() {
        cli.config(signedInConfig())
        cli.environment = mapOf("PLEOCHROIC_KEY" to "plc-fixture-key")
        assertEquals(0, cli("sync", "enable").code)
        server.enqueue(
            fakeResponse {
                body(
                    """{"changes": [{"seq": 1, "area": "skill", "kind": "global", "id": "my-skill", "rev": "rev-1",
                        "deleted": false}], "cursor": 1, "more": false}""",
                )
            },
            "/sync/changes",
        )
        server.enqueue(
            fakeResponse {
                body(
                    """{"rev": "rev-1", "files": [{"path": "SKILL.md", "encoding": "utf8", "content": "# Hi"}]}""",
                )
            },
            "/skills/global/my-skill",
        )
        server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/config/provider/pleochroic%2Fdefault")

        val printed = cli("sync", "now")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "held back for review")
        assertNull(file().at("skills"))

        val review = cli("sync", "review", "--json")
        val pendingFile = Json.parseToJsonElement(review.out).jsonObject["pending"]!!.jsonArray.single().jsonObject
        assertContains(pendingFile["reasons"]!!.jsonArray.toString(), "skill")
        val skillFile = pendingFile["files"]!!.jsonArray.single().jsonObject
        assertEquals("SKILL.md", skillFile["path"]!!.jsonPrimitive.content)
        assertEquals("added", skillFile["status"]!!.jsonPrimitive.content)

        val reviewText = cli("sync", "review")
        assertContains(reviewText.out, "SKILL.md")
        assertContains(reviewText.out, "added")
    }
}

/** Appends a `NAME=value` line to [file], creating it if it does not exist yet. */
private fun writeSecret(file: Path, name: String, value: String) {
    Files.createDirectories(file.parent)
    Files.writeString(file, "$name=$value\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}
