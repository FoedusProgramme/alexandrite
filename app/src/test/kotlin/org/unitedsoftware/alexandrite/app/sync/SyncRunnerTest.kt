package org.unitedsoftware.alexandrite.app.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.Cli
import org.unitedsoftware.alexandrite.app.FakeMachine
import org.unitedsoftware.alexandrite.app.Locations
import org.unitedsoftware.alexandrite.app.Platform
import org.unitedsoftware.alexandrite.app.at
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An in-memory [SyncTransport] a test scripts directly, no HTTP involved. */
private class FakeSyncTransport : SyncTransport {
    val docs = mutableMapOf<String, Pair<String, JsonElement>>()
    var nextRev = 1
    var changesSince0 = ChangePage(emptyList(), 0)
    val puts = mutableListOf<Triple<String, String, String>>()
    val deletes = mutableListOf<Triple<String, String, String>>()

    override suspend fun get(area: String, kind: String, id: String): RemoteDoc? =
        docs[syncKey(area, kind, id)]?.let { (rev, data) -> RemoteDoc(rev, data) }

    override suspend fun put(area: String, kind: String, id: String, data: JsonElement, ifMatch: String?): RemoteMeta {
        puts += Triple(area, kind, id)
        val rev = "rev-${nextRev++}"
        docs[syncKey(area, kind, id)] = rev to data
        return RemoteMeta(rev)
    }

    override suspend fun delete(area: String, kind: String, id: String, ifMatch: String) {
        deletes += Triple(area, kind, id)
        docs.remove(syncKey(area, kind, id))
    }

    override suspend fun changes(since: Long): ChangePage =
        if (since == 0L) changesSince0 else ChangePage(emptyList(), since)
}

class SyncRunnerTest {
    private fun cli(directory: Path): Cli {
        val configFile = directory.resolve("config/alexandrite.json")
        configFile.parent.createDirectories()
        if (!Files.exists(configFile)) configFile.writeText("{}")
        val locations = Locations(configFile, directory.resolve("data"), directory.resolve("cache"))
        return Cli(
            locations,
            emptyMap(),
            PrintStream(java.io.ByteArrayOutputStream()),
            PrintStream(java.io.ByteArrayOutputStream()),
            FakeMachine(),
            Platform.UNIX,
            directory.resolve("home"),
            null,
        )
    }

    @Test
    fun `a new local provider endpoint pushes, and the state file records its rev`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runBlocking {
        val cli = cli(tmp)
        cli.locations.configFile.writeText(
            """{"providers": {"openrouter": {"endpoints": {"work": {"baseUrl": "https://openrouter.ai/api/v1",
                "apiKey": "${'$'}{env:OPENROUTER_API_KEY}"}}}}}""",
        )
        val transport = FakeSyncTransport()

        val (report, state) = SyncRunner(cli, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

        assertEquals(1, report.pushed)
        assertEquals(0, report.pulled)
        assertTrue(transport.puts.contains(Triple("config", "provider", "openrouter/work")))
        val record = state.records[syncKey("config", "provider", "openrouter/work")]
        assertEquals("rev-1", record?.remoteRev)
    }

    @Test
    fun `a brand new remote mcp server pulls into the config file`(@org.junit.jupiter.api.io.TempDir tmp: Path) =
        runBlocking {
            val cli = cli(tmp)
            val transport = FakeSyncTransport()
            val serverDoc = buildJsonObject { put("command", "mcp-server") }
            transport.docs[syncKey("config", "mcp", "notes")] = "rev-9" to serverDoc
            val change = ChangeEntry(1, "config", "mcp", "notes", "rev-9", deleted = false)
            transport.changesSince0 = ChangePage(listOf(change), 1)

            val (report, state) = SyncRunner(cli, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

            assertEquals(1, report.pulled)
            val written = cli.rawConfig()
            val expected = JsonObject(mapOf("command" to serverDoc.getValue("command")))
            assertEquals(expected, written?.at("mcp.servers.notes"))
            assertEquals(1L, state.cursor)
        }

    @Test
    fun `a remote tombstone deletes a tracked agent locally`(@org.junit.jupiter.api.io.TempDir tmp: Path) =
        runBlocking {
            val cli = cli(tmp)
            cli.locations.configFile.writeText("""{"agent": {"agents": {"helper": {"name": "Helper"}}}}""")
            val before = SyncState(
                enabled = true,
                policy = SyncPolicy.MERGE,
                records = mapOf(syncKey("config", "agent", "helper") to SyncRecord(contentHash(helperDoc()), "rev-5")),
            )
            val transport = FakeSyncTransport()
            val change = ChangeEntry(2, "config", "agent", "helper", "rev-6", deleted = true)
            transport.changesSince0 = ChangePage(listOf(change), 2)

            val (report, state) = SyncRunner(cli, transport).run(before)

            assertEquals(1, report.deletedLocal)
            assertEquals(null, cli.rawConfig()?.at("agent.agents.helper"))
            assertTrue(state.records.isEmpty())
        }

    @Test
    fun `a secret new only locally pushes its value as the service's one-field document`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) = runBlocking {
        val cli = cli(tmp)
        cli.secrets.writeText("MY_TOKEN=hunter2\n")
        val transport = FakeSyncTransport()

        val (report, _) = SyncRunner(cli, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

        assertEquals(1, report.pushed)
        assertTrue(transport.puts.contains(Triple("secret", "", "MY_TOKEN")))
    }

    private fun helperDoc(): JsonElement = buildJsonObject { put("name", "Helper") }
}
