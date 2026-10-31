package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.testToolContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTransport : SyncTransport {
    val puts = mutableListOf<String>()

    override suspend fun get(area: String, kind: String, id: String): RemoteDoc? = null

    override suspend fun put(area: String, kind: String, id: String, data: JsonElement, ifMatch: String?): RemoteMeta {
        puts += syncKey(area, kind, id)
        return RemoteMeta("rev-${puts.size}")
    }

    override suspend fun delete(area: String, kind: String, id: String, ifMatch: String) = Unit

    override suspend fun changes(since: Long): ChangePage = ChangePage(emptyList(), since)
}

private class FakeTransports(val transport: FakeTransport = FakeTransport()) : SyncTransports {
    override suspend fun <T> using(block: suspend (SyncTransport) -> T): T = block(transport)
}

private class FakeLocal(var items: List<LocalItem> = emptyList()) : SyncLocal {
    val applied = mutableListOf<LocalChange>()

    override suspend fun collect(): List<LocalItem> = items

    override suspend fun apply(changes: List<LocalChange>) {
        applied += changes
    }
}

class SyncToolTest {
    private val endpoint = LocalItem(
        AREA_CONFIG,
        KIND_PROVIDER,
        "openrouter/work",
        buildJsonObject { put("baseUrl", "https://openrouter.ai/api/v1") },
    )

    private val heldMcp = PendingChange(
        AREA_CONFIG,
        KIND_MCP,
        "notes",
        before = null,
        remoteRev = "7",
        deleted = false,
        remoteData = buildJsonObject { put("command", "mcp-notes") },
        reasons = listOf("an MCP server runs a command"),
    )

    private fun stateFile(dir: Path, state: SyncState?): SyncStateFile =
        SyncStateFile.inDataDirectory(dir).also { file -> state?.let(file::write) }

    private fun args(action: String, keys: List<String>? = null): JsonObject = buildJsonObject {
        put("action", action)
        keys?.let { put("keys", buildJsonArray { it.forEach { key -> add(JsonPrimitive(key)) } }) }
    }

    private fun cleared(review: CallReview, kind: ClearanceKind) =
        testToolContext(clearance = Clearance(kind, snapshot = review.snapshot))

    private fun text(result: ToolResult): String = result.content.joinToString("") { (it as TextPart).text }

    @Test
    fun `status is a read and says when sync is off`(@TempDir dir: Path) = runBlocking {
        val tool = SyncTool(FakeLocal(), stateFile(dir, null), FakeTransports())

        val review = tool.review(args("status"), testToolContext())
        val result = tool.execute(args("status"), testToolContext())

        assertEquals(ToolRisk.READ_ONLY, review.risk)
        assertFalse(result.isError)
        assertContains(text(result), "alexandrite sync enable")
    }

    @Test
    fun `status lists the held changes by key`(@TempDir dir: Path) = runBlocking {
        val state = SyncState(enabled = true, pending = mapOf(syncKey(AREA_CONFIG, KIND_MCP, "notes") to heldMcp))
        val tool = SyncTool(FakeLocal(), stateFile(dir, state), FakeTransports())

        val result = text(tool.execute(args("status"), testToolContext()))

        assertContains(result, "config:mcp:notes")
        assertContains(result, "an MCP server runs a command")
    }

    @Test
    fun `run is refused while sync is off`(@TempDir dir: Path) = runBlocking {
        val tool = SyncTool(FakeLocal(listOf(endpoint)), stateFile(dir, SyncState.OFF), FakeTransports())

        val review = tool.review(args("run"), testToolContext())

        assertNotNull(review.problem)
        assertContains(review.problem!!, "alexandrite sync enable")
    }

    @Test
    fun `run's review shows a dry run's counts and pushes nothing`(@TempDir dir: Path) = runBlocking {
        val transports = FakeTransports()
        val tool = SyncTool(FakeLocal(listOf(endpoint)), stateFile(dir, SyncState(enabled = true)), transports)

        val review = tool.review(args("run"), testToolContext())

        assertNull(review.problem)
        assertNull(review.risk)
        assertNull(review.person)
        assertContains((review.preview as ReviewPreview.Text).text, "push 1")
        assertTrue(transports.transport.puts.isEmpty())
    }

    @Test
    fun `run, once a mode clears it, syncs and records the state`(@TempDir dir: Path) = runBlocking {
        val transports = FakeTransports()
        val file = stateFile(dir, SyncState(enabled = true))
        val tool = SyncTool(FakeLocal(listOf(endpoint)), file, transports)
        val review = tool.review(args("run"), testToolContext())

        val result = tool.execute(args("run"), cleared(review, ClearanceKind.MODE))

        assertFalse(result.isError, text(result))
        assertEquals(listOf(endpoint.key), transports.transport.puts)
        assertEquals(setOf(endpoint.key), file.read().records.keys)
    }

    @Test
    fun `run is refused when sync ran since its review`(@TempDir dir: Path) = runBlocking {
        val file = stateFile(dir, SyncState(enabled = true))
        val tool = SyncTool(FakeLocal(listOf(endpoint)), file, FakeTransports())
        val review = tool.review(args("run"), testToolContext())
        file.write(SyncState(enabled = true, cursor = 5))

        val result = tool.execute(args("run"), cleared(review, ClearanceKind.MODE))

        assertTrue(result.isError)
        assertContains(text(result), "since this call was reviewed")
    }

    @Test
    fun `run is refused without a review`(@TempDir dir: Path) = runBlocking {
        val tool = SyncTool(FakeLocal(listOf(endpoint)), stateFile(dir, SyncState(enabled = true)), FakeTransports())

        val result = tool.execute(args("run"), testToolContext(clearance = Clearance(ClearanceKind.MODE)))

        assertTrue(result.isError)
    }

    @Test
    fun `in a tainted conversation only a person may allow run`(@TempDir dir: Path) = runBlocking {
        val transports = FakeTransports()
        val tool = SyncTool(FakeLocal(listOf(endpoint)), stateFile(dir, SyncState(enabled = true)), transports)
        val review = tool.review(args("run"), testToolContext().apply { tainted = true })
        assertNotNull(review.person)

        val byMode = tool.execute(args("run"), cleared(review, ClearanceKind.MODE).apply { tainted = true })
        assertTrue(byMode.isError)
        assertTrue(transports.transport.puts.isEmpty())

        val byPerson = tool.execute(args("run"), cleared(review, ClearanceKind.HUMAN).apply { tainted = true })
        assertFalse(byPerson.isError, text(byPerson))
        assertEquals(listOf(endpoint.key), transports.transport.puts)
    }

    @Test
    fun `apply always needs a person and shows what it takes in`(@TempDir dir: Path) = runBlocking {
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val local = FakeLocal()
        val tool =
            SyncTool(
                local,
                stateFile(dir, SyncState(enabled = true, pending = mapOf(key to heldMcp))),
                FakeTransports(),
            )

        val review = tool.review(args("apply", listOf(key)), testToolContext())
        assertNotNull(review.person)
        assertContains((review.preview as ReviewPreview.Text).text, "config:mcp:notes")

        val byMode = tool.execute(args("apply", listOf(key)), cleared(review, ClearanceKind.MODE))
        assertTrue(byMode.isError)
        assertTrue(local.applied.isEmpty())

        val byPerson = tool.execute(args("apply", listOf(key)), cleared(review, ClearanceKind.HUMAN))
        assertFalse(byPerson.isError, text(byPerson))
        assertEquals(listOf("notes"), local.applied.map { it.id })
    }

    @Test
    fun `apply names an unknown key`(@TempDir dir: Path) = runBlocking {
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val tool =
            SyncTool(
                FakeLocal(),
                stateFile(dir, SyncState(enabled = true, pending = mapOf(key to heldMcp))),
                FakeTransports(),
            )

        val review = tool.review(args("apply", listOf("config:mcp:other")), testToolContext())

        assertContains(review.problem!!, "config:mcp:other")
    }

    @Test
    fun `a secret the running app sees only as hidden is never synced`(@TempDir dir: Path) = runBlocking {
        val leaky = LocalItem(
            AREA_CONFIG,
            KIND_PROVIDER,
            "openrouter/work",
            buildJsonObject { put("apiKey", HostConfig.HIDDEN_SECRET) },
        )
        val transports = FakeTransports()
        val tool = SyncTool(FakeLocal(listOf(leaky)), stateFile(dir, SyncState(enabled = true)), transports)

        val review = tool.review(args("run"), testToolContext())

        assertContains(review.problem!!, "config:provider:openrouter/work")
        assertTrue(transports.transport.puts.isEmpty())
    }

    @Test
    fun `an unknown action is a problem of the review`(@TempDir dir: Path) = runBlocking {
        val tool = SyncTool(FakeLocal(), stateFile(dir, null), FakeTransports())

        val review = tool.review(args("enable"), testToolContext())

        assertContains(review.problem!!, "status, run, apply")
    }
}
