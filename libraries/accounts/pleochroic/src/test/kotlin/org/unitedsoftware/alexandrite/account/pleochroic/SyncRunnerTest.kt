package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** An in-memory [SyncLocal] a test scripts directly: [items] is what [collect] answers, [applied] what [apply] got. */
private class FakeSyncLocal(private val items: List<LocalItem> = emptyList()) : SyncLocal {
    val applied = mutableListOf<LocalChange>()

    override suspend fun collect(): List<LocalItem> = items

    override suspend fun apply(changes: List<LocalChange>) {
        applied += changes
    }
}

class SyncRunnerTest {
    @Test
    fun `a new local provider endpoint pushes, and the state records its rev`() = runBlocking {
        val doc = buildJsonObject { put("baseUrl", "https://openrouter.ai/api/v1") }
        val local = FakeSyncLocal(listOf(LocalItem(AREA_CONFIG, KIND_PROVIDER, "openrouter/work", doc)))
        val transport = FakeSyncTransport()

        val (report, state) = SyncRunner(local, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

        assertEquals(1, report.pushed)
        assertEquals(0, report.pulled)
        assertTrue(transport.puts.contains(Triple(AREA_CONFIG, KIND_PROVIDER, "openrouter/work")))
        assertEquals("rev-1", state.records[syncKey(AREA_CONFIG, KIND_PROVIDER, "openrouter/work")]?.remoteRev)
    }

    @Test
    fun `a brand new remote mcp server pulls`() = runBlocking {
        val local = FakeSyncLocal()
        val transport = FakeSyncTransport()
        val serverDoc = buildJsonObject { put("command", "mcp-server") }
        transport.docs[syncKey(AREA_CONFIG, KIND_MCP, "notes")] = "rev-9" to serverDoc
        transport.changesSince0 =
            ChangePage(listOf(ChangeEntry(1, AREA_CONFIG, KIND_MCP, "notes", "rev-9", deleted = false)), 1)

        val (report, state) = SyncRunner(local, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

        assertEquals(1, report.pulled)
        val applied = local.applied.single()
        assertEquals(AREA_CONFIG to KIND_MCP to "notes", applied.area to applied.kind to applied.id)
        assertEquals(serverDoc, applied.data)
        assertEquals(1L, state.cursor)
    }

    @Test
    fun `a remote tombstone deletes a tracked agent locally`() = runBlocking {
        val helperDoc = buildJsonObject { put("name", "Helper") }
        val local = FakeSyncLocal(listOf(LocalItem(AREA_CONFIG, KIND_AGENT, "helper", helperDoc)))
        val before = SyncState(
            enabled = true,
            policy = SyncPolicy.MERGE,
            records = mapOf(syncKey(AREA_CONFIG, KIND_AGENT, "helper") to SyncRecord(contentHash(helperDoc), "rev-5")),
        )
        val transport = FakeSyncTransport()
        transport.changesSince0 =
            ChangePage(listOf(ChangeEntry(2, AREA_CONFIG, KIND_AGENT, "helper", "rev-6", deleted = true)), 2)

        val (report, state) = SyncRunner(local, transport).run(before)

        assertEquals(1, report.deletedLocal)
        val applied = local.applied.single()
        assertEquals(AREA_CONFIG to KIND_AGENT to "helper", applied.area to applied.kind to applied.id)
        assertEquals(null, applied.data)
        assertTrue(state.records.isEmpty())
    }

    @Test
    fun `a secret new only locally pushes its value as the service's one-field document`() = runBlocking {
        val local =
            FakeSyncLocal(
                listOf(LocalItem(AREA_SECRET, "", "MY_TOKEN", kotlinx.serialization.json.JsonPrimitive("hunter2"))),
            )
        val transport = FakeSyncTransport()

        val (report, _) = SyncRunner(local, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

        assertEquals(1, report.pushed)
        assertTrue(transport.puts.contains(Triple(AREA_SECRET, "", "MY_TOKEN")))
    }

    /**
     * Regression test for a bug where [SyncRunner]'s key parsing used a fixed 3-way split, which mis-parsed a
     * skill's `kind` (its scope, e.g. `agent:work`) because it itself holds a colon: `"skill:agent:work:myskill"`
     * split into `("skill", "agent", "work:myskill")` instead of `("skill", "agent:work", "myskill")`. Checks what
     * the transport actually receives, not just the bookkeeping key, since the key itself round-trips unaffected by
     * the bug.
     */
    @Test
    fun `a new local skill of a per-agent scope pushes with its scope and name intact`() = runBlocking {
        val scope = agentSkillScope("work")
        val files = buildJsonObject { put("files", kotlinx.serialization.json.buildJsonArray {}) }
        val local = FakeSyncLocal(listOf(LocalItem(AREA_SKILL, scope, "myskill", files)))
        val transport = FakeSyncTransport()

        val (report, state) = SyncRunner(local, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

        assertEquals(1, report.pushed)
        assertTrue(transport.puts.contains(Triple(AREA_SKILL, scope, "myskill")))
        assertEquals("rev-1", state.records[syncKey(AREA_SKILL, scope, "myskill")]?.remoteRev)
    }

    /** The pull-side counterpart: a brand new remote skill of a per-agent scope lands with its scope and name intact. */
    @Test
    fun `a brand new remote skill of a per-agent scope pulls with its scope and name intact`() = runBlocking {
        val scope = agentSkillScope("work")
        val local = FakeSyncLocal()
        val transport = FakeSyncTransport()
        val files = buildJsonObject { put("files", kotlinx.serialization.json.buildJsonArray {}) }
        transport.docs[syncKey(AREA_SKILL, scope, "myskill")] = "rev-1" to files
        transport.changesSince0 =
            ChangePage(listOf(ChangeEntry(1, AREA_SKILL, scope, "myskill", "rev-1", deleted = false)), 1)

        val (report, _) = SyncRunner(local, transport).run(SyncState(enabled = true, policy = SyncPolicy.MERGE))

        assertEquals(1, report.pulled)
        val applied = local.applied.single()
        assertEquals(AREA_SKILL to scope to "myskill", applied.area to applied.kind to applied.id)
    }

    @Test
    fun `both sides changed merges the document and pushes the merged result`() = runBlocking {
        val before = SyncRecord(contentHash(buildJsonObject { put("shared", "old") }), "rev-1")
        val local =
            FakeSyncLocal(listOf(LocalItem(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("shared", "local") })))
        val transport = FakeSyncTransport()
        val remoteDoc = buildJsonObject {
            put("shared", "remote")
            put("extra", "kept")
        }
        transport.docs[syncKey(AREA_CONFIG, KIND_MCP, "notes")] = "rev-2" to remoteDoc
        transport.changesSince0 =
            ChangePage(listOf(ChangeEntry(1, AREA_CONFIG, KIND_MCP, "notes", "rev-2", deleted = false)), 1)
        val state = SyncState(
            enabled = true,
            policy = SyncPolicy.MERGE,
            records = mapOf(syncKey(AREA_CONFIG, KIND_MCP, "notes") to before),
        )

        val (report, newState) = SyncRunner(local, transport).run(state)

        assertEquals(1, report.merged)
        assertEquals(1, report.conflicts)
        val merged = buildJsonObject {
            put("shared", "local")
            put("extra", "kept")
        }
        assertEquals(merged, transport.docs[syncKey(AREA_CONFIG, KIND_MCP, "notes")]?.second)
        assertEquals(merged, local.applied.single().data)
        val record = newState.records[syncKey(AREA_CONFIG, KIND_MCP, "notes")]
        assertEquals(
            contentHash(merged) to transport.docs[syncKey(AREA_CONFIG, KIND_MCP, "notes")]?.first,
            record?.localHash to record?.remoteRev,
        )
    }
}
