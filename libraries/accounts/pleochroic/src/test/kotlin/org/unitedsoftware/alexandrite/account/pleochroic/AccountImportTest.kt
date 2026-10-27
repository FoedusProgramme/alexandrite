package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A read-only account: the change feed and documents a test sets, and every write recorded so none goes unseen. */
private class ImportFakeTransport : SyncTransport {
    val feed = mutableListOf<ChangeEntry>()
    val docs = mutableMapOf<String, Pair<String, JsonElement>>()
    val writes = mutableListOf<String>()

    fun hold(area: String, kind: String, id: String, rev: String, data: JsonElement) {
        feed += ChangeEntry(feed.size + 1L, area, kind, id, rev, deleted = false)
        docs[syncKey(area, kind, id)] = rev to data
    }

    override suspend fun get(area: String, kind: String, id: String): RemoteDoc? =
        docs[syncKey(area, kind, id)]?.let { (rev, data) -> RemoteDoc(rev, data) }

    override suspend fun put(area: String, kind: String, id: String, data: JsonElement, ifMatch: String?): RemoteMeta {
        writes += "put ${syncKey(area, kind, id)}"
        return RemoteMeta("x")
    }

    override suspend fun delete(area: String, kind: String, id: String, ifMatch: String) {
        writes += "delete ${syncKey(area, kind, id)}"
    }

    override suspend fun changes(since: Long): ChangePage =
        ChangePage(feed.filter { it.seq > since }, feed.size.toLong())
}

private class ImportFakeLocal(var items: List<LocalItem> = emptyList()) : SyncLocal {
    override suspend fun collect(): List<LocalItem> = items

    override suspend fun apply(changes: List<LocalChange>) = throw AssertionError("AccountImport writes nothing.")
}

class AccountImportTest {
    private val skill = buildJsonObject {
        put(
            "files",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", "SKILL.md")
                        put("encoding", "utf8")
                        put("content", "# notes")
                    },
                )
            },
        )
    }

    private fun mcp(vararg env: Pair<String, String>) = buildJsonObject {
        put("command", "notes-mcp")
        put("env", buildJsonObject { for ((name, value) in env) put(name, value) })
    }

    @Test
    fun `list finds the live skills and mcp servers and the account's secret names`() = runBlocking {
        val transport = ImportFakeTransport()
        transport.hold(AREA_SKILL, SKILL_SCOPE_GLOBAL, "notes", "1", skill)
        transport.hold(AREA_CONFIG, KIND_MCP, "notes", "2", mcp())
        transport.hold(AREA_CONFIG, KIND_MCP, "old", "3", mcp())
        transport.feed += ChangeEntry(4, AREA_CONFIG, KIND_MCP, "old", "4", deleted = true)
        transport.hold(AREA_CONFIG, KIND_AGENT, "main", "5", buildJsonObject { put("model", "x") })
        transport.hold(AREA_SECRET, "", "NOTES_TOKEN", "6", buildJsonObject { put("value", "t") })

        val listing = AccountImport(transport).list()

        assertEquals(
            listOf("skill:global:notes", "config:mcp:notes"),
            listing.items.map { it.key },
        )
        assertEquals(setOf("NOTES_TOKEN"), listing.secrets)
        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun `fetch brings an mcp server's secret along in the local form`() = runBlocking {
        val transport = ImportFakeTransport()
        transport.hold(AREA_CONFIG, KIND_MCP, "notes", "2", mcp("TOKEN" to wireSecretRef("NOTES_TOKEN")))
        transport.hold(AREA_SECRET, "", "NOTES_TOKEN", "6", buildJsonObject { put("value", "t") })
        val import = AccountImport(transport)
        val listing = import.list()

        val ready = assertIs<AccountFetch.Ready>(import.fetch(listing.items.single(), listing.secrets, emptyMap()))

        assertEquals(mcp("TOKEN" to localSecretRef("NOTES_TOKEN")), ready.data)
        assertEquals(listOf("NOTES_TOKEN" to true), ready.secrets.map { it.name to it.missing })
        assertEquals("t", ready.secrets.single().value)
        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun `fetch refuses a local env reference and a secret the account does not hold`() = runBlocking {
        val transport = ImportFakeTransport()
        transport.hold(AREA_CONFIG, KIND_MCP, "local", "1", mcp("TOKEN" to localSecretRef("TELEGRAM_TOKEN")))
        transport.hold(AREA_CONFIG, KIND_MCP, "foreign", "2", mcp("TOKEN" to wireSecretRef("OTHER")))
        val import = AccountImport(transport)
        val listing = import.list()

        for (item in listing.items) {
            val refused = assertIs<AccountFetch.Refused>(import.fetch(item, listing.secrets, emptyMap()))
            assertEquals(AccountFetch.Reason.SUSPICIOUS_REFERENCE, refused.reason)
        }
    }

    @Test
    fun `fetch refuses rather than replace a local secret of the same name with another value`() = runBlocking {
        val transport = ImportFakeTransport()
        transport.hold(AREA_CONFIG, KIND_MCP, "notes", "2", mcp("TOKEN" to wireSecretRef("NOTES_TOKEN")))
        transport.hold(AREA_SECRET, "", "NOTES_TOKEN", "6", buildJsonObject { put("value", "t") })
        val import = AccountImport(transport)
        val listing = import.list()
        val item = listing.items.single()

        val refused = assertIs<AccountFetch.Refused>(import.fetch(item, listing.secrets, mapOf("NOTES_TOKEN" to "u")))
        val same = assertIs<AccountFetch.Ready>(import.fetch(item, listing.secrets, mapOf("NOTES_TOKEN" to "t")))

        assertEquals(AccountFetch.Reason.SECRET_DIFFERS to "NOTES_TOKEN", refused.reason to refused.detail)
        assertEquals(false, same.secrets.single().missing)
    }

    @Test
    fun `importedRecords records only what landed exactly as imported`() = runBlocking {
        val transport = ImportFakeTransport()
        transport.hold(AREA_CONFIG, KIND_MCP, "notes", "2", mcp("TOKEN" to wireSecretRef("NOTES_TOKEN")))
        transport.hold(AREA_SECRET, "", "NOTES_TOKEN", "6", buildJsonObject { put("value", "t") })
        transport.hold(AREA_SKILL, SKILL_SCOPE_GLOBAL, "notes", "1", skill)
        val import = AccountImport(transport)
        val listing = import.list()
        val ready = listing.items.map { assertIs<AccountFetch.Ready>(import.fetch(it, listing.secrets, emptyMap())) }
        val mcpReady = ready.single { it.item.area == AREA_CONFIG }
        val local = ImportFakeLocal(
            listOf(
                LocalItem(AREA_CONFIG, KIND_MCP, "notes", mcpReady.data),
                LocalItem(AREA_SECRET, "", "NOTES_TOKEN", JsonPrimitive("t")),
                LocalItem(AREA_SKILL, SKILL_SCOPE_GLOBAL, "notes", buildJsonObject { put("files", "changed") }),
            ),
        )

        val records = importedRecords(local, ready)

        assertEquals(setOf("config:mcp:notes", "secret::NOTES_TOKEN"), records.keys)
        assertEquals("2", records.getValue("config:mcp:notes").remoteRev)
        assertEquals(contentHash(JsonPrimitive("t")), records.getValue("secret::NOTES_TOKEN").localHash)
    }

    @Test
    fun `importedRecords matches a skill's files whatever order the local read lists them in`() = runBlocking {
        fun file(path: String) = buildJsonObject {
            put("path", path)
            put("encoding", "utf8")
            put("content", path)
        }
        val remote = buildJsonObject {
            put(
                "files",
                buildJsonArray {
                    add(file("SKILL.md"))
                    add(file("a/b.md"))
                },
            )
        }
        val here = buildJsonObject {
            put(
                "files",
                buildJsonArray {
                    add(file("a/b.md"))
                    add(file("SKILL.md"))
                },
            )
        }
        val transport = ImportFakeTransport()
        transport.hold(AREA_SKILL, SKILL_SCOPE_GLOBAL, "notes", "1", remote)
        val import = AccountImport(transport)
        val ready = assertIs<AccountFetch.Ready>(import.fetch(import.list().items.single(), emptySet(), emptyMap()))

        val records =
            importedRecords(
                ImportFakeLocal(listOf(LocalItem(AREA_SKILL, SKILL_SCOPE_GLOBAL, "notes", here))),
                listOf(ready),
            )

        assertEquals(SyncRecord(contentHash(here), "1").localHash, records.getValue("skill:global:notes").localHash)
    }
}
