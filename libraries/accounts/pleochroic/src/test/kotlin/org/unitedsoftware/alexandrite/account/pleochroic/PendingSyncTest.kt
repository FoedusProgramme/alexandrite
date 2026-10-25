package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory [SyncTransport] a test scripts directly, no HTTP involved. */
private class PendingFakeTransport : SyncTransport {
    val docs = mutableMapOf<String, Pair<String, JsonElement>>()
    var nextRev = 1
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

    override suspend fun changes(since: Long): ChangePage = ChangePage(emptyList(), since)
}

/** An in-memory [SyncLocal]; [items] is mutable so a test can simulate a local edit made after staging. */
private class PendingFakeLocal(var items: List<LocalItem> = emptyList()) : SyncLocal {
    val applied = mutableListOf<LocalChange>()

    override suspend fun collect(): List<LocalItem> = items

    override suspend fun apply(changes: List<LocalChange>) {
        applied += changes
        for (change in changes) {
            items = items.filterNot { it.key == syncKey(change.area, change.kind, change.id) } +
                listOfNotNull(change.data?.let { LocalItem(change.area, change.kind, change.id, it) })
        }
    }
}

class PendingSyncTest {
    private fun mcpPending(id: String = "notes", data: JsonElement = buildJsonObject { put("command", "x") }) =
        PendingChange(
            AREA_CONFIG,
            KIND_MCP,
            id,
            before = null,
            remoteRev = "rev-1",
            deleted = false,
            remoteData = data,
            reasons = listOf("mcp_server"),
        )

    @Test
    fun `review shows each pending change alongside its current local content`() = runBlocking {
        val local =
            PendingFakeLocal(
                listOf(
                    LocalItem(
                        AREA_CONFIG,
                        KIND_MCP,
                        "notes",
                        buildJsonObject {
                            put("command", "old")
                        },
                    ),
                ),
            )
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val state = SyncState(pending = mapOf(key to mcpPending()))

        val reviews = PendingSync(local, PendingFakeTransport()).review(state)

        val review = reviews.single()
        assertEquals(buildJsonObject { put("command", "old") }, review.local)
        assertTrue(review.summary.contains("mcp_server"))
        assertEquals(setOf("command"), review.changedKeys)
    }

    @Test
    fun `review of a staged skill change lists its added, changed and removed files`() = runBlocking {
        fun skillDoc(path: String, content: String) = buildJsonObject {
            put(
                "files",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("path", path)
                            put("encoding", "utf8")
                            put("content", content)
                        },
                    )
                },
            )
        }
        val localDoc = skillDoc("SKILL.md", "old text")
        val remoteDoc = skillDoc("SKILL.md", "new text")
        val local = PendingFakeLocal(listOf(LocalItem(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill", localDoc)))
        val key = syncKey(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill")
        val change =
            PendingChange(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill", null, "rev-1", false, remoteDoc, listOf("skill"))
        val state = SyncState(pending = mapOf(key to change))

        val review = PendingSync(local, PendingFakeTransport()).review(state).single()

        assertEquals(1, review.skillFiles.size)
        val file = review.skillFiles.single()
        assertEquals("SKILL.md" to SkillFileStatus.CHANGED, file.path to file.status)
        assertTrue(file.textDiff.orEmpty().contains("- old text"))
        assertTrue(file.textDiff.orEmpty().contains("+ new text"))
        assertTrue(review.summary.contains("1 changed"))
        assertTrue(review.summary.contains("skill"))
    }

    @Test
    fun `apply writes a staged pull that nothing has touched since`() = runBlocking {
        val local = PendingFakeLocal()
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val state = SyncState(policy = SyncPolicy.MERGE, pending = mapOf(key to mcpPending()))

        val (report, newState) = PendingSync(local, PendingFakeTransport()).apply(state)

        assertEquals(1, report.applied)
        assertEquals(buildJsonObject { put("command", "x") }, local.applied.single().data)
        assertTrue(newState.pending.isEmpty())
        assertEquals("rev-1", newState.records[key]?.remoteRev)
    }

    @Test
    fun `apply only resolves the selected keys, leaving the rest pending`() = runBlocking {
        val local = PendingFakeLocal()
        val keyA = syncKey(AREA_CONFIG, KIND_MCP, "a")
        val keyB = syncKey(AREA_CONFIG, KIND_MCP, "b")
        val state = SyncState(pending = mapOf(keyA to mcpPending("a"), keyB to mcpPending("b")))

        val (report, newState) = PendingSync(local, PendingFakeTransport()).apply(state, setOf(keyA))

        assertEquals(1, report.applied)
        assertTrue(keyA !in newState.pending)
        assertTrue(keyB in newState.pending)
    }

    @Test
    fun `a local edit made after staging is not silently lost, it is merged at apply time`() = runBlocking {
        // Staged with no local item at all; by the time it is applied, a local edit has added conflicting content.
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val remoteDoc = buildJsonObject {
            put("command", "remote")
            put("keptRemote", "yes")
        }
        val change = PendingChange(
            AREA_CONFIG,
            KIND_MCP,
            "notes",
            before = null,
            remoteRev = "rev-1",
            deleted = false,
            remoteData = remoteDoc,
            reasons = listOf("mcp_server"),
        )
        val local = PendingFakeLocal(
            listOf(LocalItem(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("command", "local-edit") })),
        )
        val state = SyncState(policy = SyncPolicy.MERGE, pending = mapOf(key to change))

        val (report, newState) = PendingSync(local, PendingFakeTransport()).apply(state)

        // Under MERGE, the local edit's value for the shared key wins, the remote's extra key is kept.
        val merged = buildJsonObject {
            put("command", "local-edit")
            put("keptRemote", "yes")
        }
        assertEquals(merged, local.applied.single().data)
        assertTrue(report.applied + report.pushed >= 1)
        assertTrue(newState.pending.isEmpty())
    }

    @Test
    fun `a local edit matching what local policy already owns pushes back instead of overwriting it`() = runBlocking {
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val change = mcpPending()
        val local = PendingFakeLocal(
            listOf(LocalItem(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("command", "mine") })),
        )
        val state = SyncState(policy = SyncPolicy.LOCAL, pending = mapOf(key to change))
        val transport = PendingFakeTransport()

        val (report, newState) = PendingSync(local, transport).apply(state)

        assertEquals(1, report.pushed)
        assertTrue(local.applied.isEmpty())
        assertTrue(transport.puts.contains(Triple(AREA_CONFIG, KIND_MCP, "notes")))
        assertTrue(newState.pending.isEmpty())
    }

    @Test
    fun `discard does not apply the change, and the same remote revision is not staged again`() = runBlocking {
        val local = PendingFakeLocal()
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val state = SyncState(pending = mapOf(key to mcpPending()))

        val newState = PendingSync(local, PendingFakeTransport()).discard(state)

        assertTrue(local.applied.isEmpty())
        assertTrue(newState.pending.isEmpty())
        assertEquals("rev-1", newState.records[key]?.remoteRev)

        // A later run against the same (still unchanged) remote content now sees it as already synced.
        val transport = PendingFakeTransport()
        transport.docs[key] = "rev-1" to buildJsonObject { put("command", "x") }
        val (report, _) = SyncRunner(local, transport).run(newState.with(cursor = 0))
        assertEquals(0, report.staged)
        assertEquals(0, report.pulled)
    }

    @Test
    fun `discard of a deletion with no local item clears the record entirely`() = runBlocking {
        val local = PendingFakeLocal()
        val key = syncKey(AREA_CONFIG, KIND_MCP, "notes")
        val change =
            PendingChange(
                AREA_CONFIG,
                KIND_MCP,
                "notes",
                null,
                "rev-1",
                deleted = true,
                remoteData = null,
                reasons = listOf("mcp_server"),
            )
        val state = SyncState(pending = mapOf(key to change))

        val newState = PendingSync(local, PendingFakeTransport()).discard(state)

        assertNull(newState.records[key])
    }
}
