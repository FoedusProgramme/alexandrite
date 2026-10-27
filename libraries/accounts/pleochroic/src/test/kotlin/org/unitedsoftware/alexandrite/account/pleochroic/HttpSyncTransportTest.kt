package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun transport() = HttpTransport(HttpTimeouts(5.seconds, 5.seconds, 5.seconds))

class HttpSyncTransportTest {
    @Test
    fun `get unwraps a config document's data field`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    header("etag", "\"rev-1\"")
                    body("""{"rev": "rev-1", "data": {"baseUrl": "x"}}""")
                },
                "/config/provider/openrouter%2Fwork",
            )
            transport().use { http ->
                val sync = HttpSyncTransport(http, server.baseUrl, "plc-key")

                val doc = sync.get(AREA_CONFIG, KIND_PROVIDER, "openrouter/work")

                assertEquals("rev-1", doc?.rev)
                assertEquals(buildJsonObject { put("baseUrl", "x") }, doc?.data)
                assertEquals("Bearer plc-key", server.requests.single().header("authorization"))
            }
        }
    }

    @Test
    fun `get falls back to the etag header for rev when the body names none`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    header("etag", "\"rev-7\"")
                    body("""{"data": {"x": 1}}""")
                },
                "/config/settings/app",
            )
            transport().use { http ->
                val doc = HttpSyncTransport(http, server.baseUrl, "plc-key").get(AREA_CONFIG, KIND_SETTINGS, "app")

                assertEquals("rev-7", doc?.rev)
            }
        }
    }

    @Test
    fun `get wraps a secret's bare value into the one-field document`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse { body("""{"rev": "rev-2", "value": "hunter2"}""") }, "/secrets/MY_TOKEN")
            transport().use { http ->
                val doc = HttpSyncTransport(http, server.baseUrl, "plc-key").get(AREA_SECRET, "", "MY_TOKEN")

                assertEquals(buildJsonObject { put("value", "hunter2") }, doc?.data)
            }
        }
    }

    @Test
    fun `get returns a skill's files document as is`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"rev": "rev-3", "files": [{"path": "SKILL.md", "encoding": "utf8", "content": "# hi"}]}""",
                    )
                },
                "/skills/global/my-skill",
            )
            transport().use { http ->
                val doc = HttpSyncTransport(
                    http,
                    server.baseUrl,
                    "plc-key",
                ).get(AREA_SKILL, SKILL_SCOPE_GLOBAL, "my-skill")

                assertEquals("rev-3", doc?.rev)
                assertTrue((doc?.data as kotlinx.serialization.json.JsonObject).containsKey("files"))
            }
        }
    }

    @Test
    fun `get returns null on a 404`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(404) { body("""{"error": {"code": "not_found"}}""") }, "/config/mcp/notes")
            transport().use { http ->
                assertNull(HttpSyncTransport(http, server.baseUrl, "plc-key").get(AREA_CONFIG, KIND_MCP, "notes"))
            }
        }
    }

    @Test
    fun `put sends If-None-Match star for a new document and reads back the new rev`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/config/mcp/notes")
            transport().use { http ->
                val meta = HttpSyncTransport(http, server.baseUrl, "plc-key")
                    .put(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("command", "x") }, null)

                assertEquals("rev-1", meta.rev)
                val request = server.requests.single()
                assertEquals("*", request.header("if-none-match"))
                assertEquals(
                    buildJsonObject { put("command", "x") },
                    request.json()["data"],
                )
            }
        }
    }

    @Test
    fun `put sends a quoted If-Match for an existing document`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse { body("""{"rev": "rev-2"}""") }, "/config/mcp/notes")
            transport().use { http ->
                HttpSyncTransport(http, server.baseUrl, "plc-key")
                    .put(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("command", "x") }, "rev-1")

                assertEquals("\"rev-1\"", server.requests.single().header("if-match"))
            }
        }
    }

    @Test
    fun `put wraps a secret as the one-field value document`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/secrets/MY_TOKEN")
            transport().use { http ->
                HttpSyncTransport(
                    http,
                    server.baseUrl,
                    "plc-key",
                ).put(AREA_SECRET, "", "MY_TOKEN", JsonPrimitive("hunter2"), null)

                assertEquals(JsonPrimitive("hunter2"), server.requests.single().json()["value"])
            }
        }
    }

    @Test
    fun `put sends a skill's files document unwrapped`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse { body("""{"rev": "rev-1"}""") }, "/skills/agent%3Awork/my-skill")
            val files = buildJsonObject {
                put("files", kotlinx.serialization.json.buildJsonArray {})
            }
            transport().use { http ->
                HttpSyncTransport(
                    http,
                    server.baseUrl,
                    "plc-key",
                ).put(AREA_SKILL, "agent:work", "my-skill", files, null)

                assertEquals(files, server.requests.single().json())
            }
        }
    }

    @Test
    fun `put maps a 409 conflict to SyncConflictException with the current rev`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse(409) {
                    body("""{"error": {"code": "conflict", "current": {"rev": "rev-9"}}}""")
                },
                "/config/mcp/notes",
            )
            transport().use { http ->
                val sync = HttpSyncTransport(http, server.baseUrl, "plc-key")
                try {
                    sync.put(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("x", 1) }, "rev-1")
                    throw AssertionError("Expected a SyncConflictException.")
                } catch (e: SyncConflictException) {
                    assertEquals("rev-9", e.current.rev)
                }
            }
        }
    }

    @Test
    fun `put maps any other error to SyncServiceException by its code`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse(403) { body("""{"error": {"code": "not_entitled", "message": "no sync"}}""") },
                "/config/mcp/notes",
            )
            transport().use { http ->
                val sync = HttpSyncTransport(http, server.baseUrl, "plc-key")
                try {
                    sync.put(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("x", 1) }, "rev-1")
                    throw AssertionError("Expected a SyncServiceException.")
                } catch (e: SyncServiceException) {
                    assertEquals("not_entitled" to "no sync", e.code to e.message)
                }
            }
        }
    }

    @Test
    fun `delete sends a quoted If-Match`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse {}, "/config/mcp/notes")
            transport().use { http ->
                HttpSyncTransport(http, server.baseUrl, "plc-key").delete(AREA_CONFIG, KIND_MCP, "notes", "rev-1")

                val request = server.requests.single()
                assertEquals("DELETE", request.method)
                assertEquals("\"rev-1\"", request.header("if-match"))
            }
        }
    }

    @Test
    fun `changes reads a secret's null kind as empty and follows the cursor to the end`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"changes": [{"seq": 1, "area": "secret", "kind": null, "id": "MY_TOKEN", "rev": "rev-1",
                            "deleted": false}], "cursor": 1, "more": true}""",
                    )
                },
                "/sync/changes",
            )
            server.enqueue(
                fakeResponse {
                    body(
                        """{"changes": [{"seq": 2, "area": "skill", "kind": "agent:work", "id": "my-skill",
                            "rev": "rev-2", "deleted": false}], "cursor": 2, "more": false}""",
                    )
                },
                "/sync/changes",
            )
            transport().use { http ->
                val page = HttpSyncTransport(http, server.baseUrl, "plc-key").changes(0)

                assertEquals(2, page.changes.size)
                assertEquals("" to "MY_TOKEN", page.changes[0].kind to page.changes[0].id)
                assertEquals("agent:work" to "my-skill", page.changes[1].kind to page.changes[1].id)
                assertEquals(2L, page.cursor)
            }
        }
    }

    @Test
    fun `changes reports a remote tombstone`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"changes": [{"seq": 1, "area": "config", "kind": "agent", "id": "helper", "rev": "rev-2",
                            "deleted": true}], "cursor": 1, "more": false}""",
                    )
                },
                "/sync/changes",
            )
            transport().use { http ->
                val page = HttpSyncTransport(http, server.baseUrl, "plc-key").changes(0)

                assertTrue(page.changes.single().deleted)
            }
        }
    }

    @Test
    fun `changes reads the numeric revs the service sends`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"changes": [{"seq": 4, "area": "config", "kind": "mcp", "id": "notes", "rev": 3,
                            "deleted": false, "at": "2026-10-27T00:00:00.000Z"}], "cursor": 4, "more": false}""",
                    )
                },
                "/sync/changes",
            )
            transport().use { http ->
                val page = HttpSyncTransport(http, server.baseUrl, "plc-key").changes(0)

                assertEquals("3", page.changes.single().rev)
                assertEquals(4L, page.cursor)
            }
        }
    }

    @Test
    fun `get reads a numeric rev from the body`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    header("etag", "\"7\"")
                    body("""{"kind": "mcp", "id": "notes", "rev": 7, "data": {"command": "notes"}}""")
                },
                "/config/mcp/notes",
            )
            transport().use { http ->
                val doc = HttpSyncTransport(http, server.baseUrl, "plc-key").get(AREA_CONFIG, KIND_MCP, "notes")

                assertEquals("7", doc?.rev)
            }
        }
    }

    @Test
    fun `put maps a 409 conflict's numeric current rev`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse(409) {
                    body("""{"error": {"code": "conflict", "message": "stale", "current": {"rev": 5}}}""")
                },
                "/config/mcp/notes",
            )
            transport().use { http ->
                val sync = HttpSyncTransport(http, server.baseUrl, "plc-key")
                try {
                    sync.put(AREA_CONFIG, KIND_MCP, "notes", buildJsonObject { put("x", 1) }, "4")
                    throw AssertionError("Expected a SyncConflictException.")
                } catch (e: SyncConflictException) {
                    assertEquals("5", e.current.rev)
                }
            }
        }
    }
}
