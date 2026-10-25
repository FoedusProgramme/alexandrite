package org.unitedsoftware.alexandrite.app.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncEngineTest {
    private val a = buildJsonObject { put("x", "a") }
    private val b = buildJsonObject { put("x", "b") }
    private fun engine(policy: SyncPolicy = SyncPolicy.MERGE) = SyncEngine(policy)

    @Test
    fun `nothing changed is a noop`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine().decide(before, a, RemoteState.Unchanged)

        assertEquals(SyncOutcome.Noop, decision.outcome)
        assertFalse(decision.conflict)
    }

    @Test
    fun `a brand new local item pushes`() {
        val decision = engine().decide(null, a, RemoteState.Unchanged)

        assertEquals(a, assertIs<SyncOutcome.Push>(decision.outcome).data)
        assertFalse(decision.conflict)
    }

    @Test
    fun `a brand new remote item pulls`() {
        val decision = engine().decide(null, null, RemoteState.Present("rev-1", a))

        assertEquals(a, assertIs<SyncOutcome.Pull>(decision.outcome).data)
        assertFalse(decision.conflict)
    }

    @Test
    fun `only the local side changed pushes it`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine().decide(before, b, RemoteState.Unchanged)

        assertEquals(b, assertIs<SyncOutcome.Push>(decision.outcome).data)
        assertFalse(decision.conflict)
    }

    @Test
    fun `only the local side deleted it deletes remote`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine().decide(before, null, RemoteState.Unchanged)

        assertEquals(SyncOutcome.DeleteRemote, decision.outcome)
    }

    @Test
    fun `only the remote side changed pulls it`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine().decide(before, a, RemoteState.Present("rev-2", b))

        assertEquals(b, assertIs<SyncOutcome.Pull>(decision.outcome).data)
        assertFalse(decision.conflict)
    }

    @Test
    fun `only the remote side deleted it deletes locally`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine().decide(before, a, RemoteState.Deleted("rev-2"))

        assertEquals(SyncOutcome.DeleteLocal, decision.outcome)
    }

    @Test
    fun `both sides changed is a conflict, local wins under the local policy`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine(SyncPolicy.LOCAL).decide(before, b, RemoteState.Present("rev-2", a))

        assertTrue(decision.conflict)
        assertEquals(b, assertIs<SyncOutcome.Push>(decision.outcome).data)
    }

    @Test
    fun `both sides changed is a conflict, remote wins under the remote policy`() {
        val before = SyncRecord(contentHash(a), "rev-1")
        val remoteDoc = buildJsonObject { put("x", "c") }

        val decision = engine(SyncPolicy.REMOTE).decide(before, b, RemoteState.Present("rev-2", remoteDoc))

        assertTrue(decision.conflict)
        assertEquals(remoteDoc, assertIs<SyncOutcome.Pull>(decision.outcome).data)
    }

    @Test
    fun `a merge conflict keeps the remote's extra keys but the local value of a shared key`() {
        val before = SyncRecord(contentHash(buildJsonObject { put("shared", "old") }), "rev-1")
        val local = buildJsonObject {
            put("shared", "local-value")
        }
        val remote = buildJsonObject {
            put("shared", "remote-value")
            put("remoteOnly", "kept")
        }

        val decision = engine(SyncPolicy.MERGE).decide(before, local, RemoteState.Present("rev-2", remote))

        assertTrue(decision.conflict)
        val merged = assertIs<JsonObject>(assertIs<SyncOutcome.Merged>(decision.outcome).data)
        assertEquals(JsonPrimitive("local-value"), merged["shared"])
        assertEquals(JsonPrimitive("kept"), merged["remoteOnly"])
    }

    @Test
    fun `a merge conflict with a deleted remote side falls back to pushing the local value`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine(SyncPolicy.MERGE).decide(before, b, RemoteState.Deleted("rev-2"))

        assertTrue(decision.conflict)
        assertEquals(b, assertIs<SyncOutcome.Push>(decision.outcome).data)
    }

    @Test
    fun `a merge conflict with a deleted local side falls back to deleting remote`() {
        val before = SyncRecord(contentHash(a), "rev-1")

        val decision = engine(SyncPolicy.MERGE).decide(before, null, RemoteState.Present("rev-2", b))

        assertTrue(decision.conflict)
        assertEquals(SyncOutcome.DeleteRemote, decision.outcome)
    }

    @Test
    fun `an item already recorded as deleted on both sides is a noop`() {
        val before = SyncRecord(null, "rev-2-tombstone")

        val decision = engine().decide(before, null, RemoteState.Deleted("rev-2-tombstone"))

        assertEquals(SyncOutcome.Noop, decision.outcome)
        assertFalse(decision.conflict)
    }

    @Test
    fun `contentHash ignores key order`() {
        val reordered = buildJsonObject {
            put("x", "a")
            put("y", "b")
        }
        val original = buildJsonObject {
            put("y", "b")
            put("x", "a")
        }

        assertEquals(contentHash(original), contentHash(reordered))
    }
}
