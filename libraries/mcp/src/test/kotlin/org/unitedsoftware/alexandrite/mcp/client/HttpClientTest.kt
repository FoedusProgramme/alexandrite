package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HttpClientTest {
    private val transport = HttpTransport(
        HttpTimeouts(connect = 5.seconds, firstByte = 10.seconds, idle = 10.seconds),
        testHttpEngine(),
    )
    private val servers = mutableListOf<FakeHttpServer>()

    @AfterTest
    fun closeAll() {
        servers.forEach { it.close() }
        transport.close()
    }

    private fun server(legacy: Boolean = false, sse: Boolean = false, token: String? = null) =
        FakeHttpServer(legacy, sse, token).also { servers += it }

    private suspend fun connect(server: FakeHttpServer, headers: Map<String, String> = mapOf("X-Api-Key" to "key-1")) =
        McpClient.connect(McpHttpConnection(server.url, headers, transport), CLIENT, FAST)

    private fun text(value: String) = McpCallOutcome.Completed(listOf(McpContent.Text(value)), null, isError = false)

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // The modern revision.

    @Test
    fun `a modern server gets the standard headers on every request`() = realTime {
        val server = server()
        val client = connect(server)
        try {
            assertFalse(client.server.legacy)
            assertEquals("fake-http", client.server.implementation?.name)
            assertEquals(text("hi"), client.callTool("echo", args("text" to "hi"), 10.seconds))
            val discover = server.received.first { it.rpcMethod == McpClient.DISCOVER }
            assertEquals("2026-07-28", discover.header("MCP-Protocol-Version"))
            assertEquals("server/discover", discover.header("Mcp-Method"))
            assertEquals("application/json, text/event-stream", discover.header("Accept"))
            assertEquals("key-1", discover.header("X-Api-Key"))
            val call = server.received.first { it.rpcMethod == McpClient.TOOLS_CALL }
            assertEquals("echo", call.header("Mcp-Name"))
            assertNull(call.header("Mcp-Session-Id"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `answers come as JSON or as event streams with notifications before them`() = realTime {
        for (sse in listOf(false, true)) {
            val client = connect(server(sse = sse))
            try {
                val list = client.listTools()
                assertEquals(listOf("echo", "héllo", "hang", "change", "regional"), list.tools.map { it.name })
                assertEquals(5.seconds, list.ttl)
                assertEquals(listOf("broken"), list.rejected.map { it.name })
                assertContains(list.rejected.single().reason, "not statically reachable")
                assertEquals(text("héllo"), client.callTool("héllo", JsonObject(emptyMap()), 10.seconds))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `a name that is no plain header value is sent encoded`() = realTime {
        val server = server()
        val client = connect(server)
        try {
            client.callTool("héllo", JsonObject(emptyMap()), 10.seconds)
            assertEquals(
                "=?base64?aMOpbGxv?=",
                server.received.last {
                    it.rpcMethod == McpClient.TOOLS_CALL
                }.header("Mcp-Name"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `parameters marked for headers are mirrored into them`() = realTime {
        val client = connect(server())
        try {
            client.listTools()
            assertEquals(text("us-west1"), client.callTool("regional", args("region" to "us-west1"), 10.seconds))
            assertEquals(
                text("=?base64?WsO8cmljaA==?="),
                client.callTool("regional", args("region" to "Zürich"), 10.seconds),
            )
            assertEquals(text("none"), client.callTool("regional", JsonObject(emptyMap()), 10.seconds))
        } finally {
            client.close()
        }
    }

    @Test
    fun `a timed out call abandons its stream without a cancellation message`() = realTime {
        val server = server()
        val client = connect(server)
        try {
            assertFailsWith<McpTimeoutException> { client.callTool("hang", JsonObject(emptyMap()), 1.seconds) }
            val hung = server.events.await { it.startsWith("hanging on ") }.removePrefix("hanging on ")
            server.events.await { it == "abandoned $hung" }
            assertTrue(server.received.none { it.rpcMethod == "notifications/cancelled" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `a change reaches the client through its subscription, also after the stream breaks`() = realTime {
        val server = server()
        val client = connect(server)
        try {
            server.events.await { it == "listen true" }
            val changed = async(start = CoroutineStart.UNDISPATCHED) { client.toolsChanged.first() }
            client.callTool("change", JsonObject(emptyMap()), 10.seconds)
            withTimeout(10.seconds) { changed.await() }

            val again = async(start = CoroutineStart.UNDISPATCHED) { client.toolsChanged.first() }
            server.dropStreams()
            withTimeout(10.seconds) { again.await() }
            assertEquals(2, server.received.count { it.rpcMethod == McpClient.SUBSCRIBE })
        } finally {
            client.close()
        }
    }

    @Test
    fun `a server that wants a token refuses with its challenge`() = realTime {
        val server = server(token = "right")
        val failure = assertFailsWith<McpUnauthorized> { connect(server, mapOf("Authorization" to "Bearer wrong")) }
        assertEquals("Bearer resource_metadata=\"https://example.test/meta\"", failure.wwwAuthenticate)
        val client = connect(server, mapOf("Authorization" to "Bearer right"))
        client.close()
    }

    // The legacy revisions.

    @Test
    fun `a legacy server is spoken to in a session after the fallback`() = realTime {
        val server = server(legacy = true)
        val client = connect(server)
        try {
            assertTrue(client.server.legacy)
            assertEquals("2025-11-25", client.server.protocolVersion)
            server.events.await { it == "initialize null" }
            server.events.await { it == "initialized" }
            assertEquals(text("hi"), client.callTool("echo", args("text" to "hi"), 10.seconds))
            val call = server.received.last { it.rpcMethod == McpClient.TOOLS_CALL }
            assertEquals("session-1", call.header("Mcp-Session-Id"))
            assertEquals("2025-11-25", call.header("MCP-Protocol-Version"))
            assertEquals(6, client.listTools().tools.size)
            server.events.await { it == "pong" }
            assertFalse("unexpected meta" in server.events.all)
        } finally {
            client.close()
        }
        server.events.await { it == "deleted session-1" }
    }

    @Test
    fun `a legacy server's change comes on its GET stream`() = realTime {
        val server = server(legacy = true, sse = true)
        val client = connect(server)
        try {
            server.events.await { it == "pong" }
            val changed = async(start = CoroutineStart.UNDISPATCHED) { client.toolsChanged.first() }
            client.callTool("change", JsonObject(emptyMap()), 10.seconds)
            withTimeout(10.seconds) { changed.await() }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a legacy call that times out is cancelled with a message`() = realTime {
        val server = server(legacy = true)
        val client = connect(server)
        try {
            assertFailsWith<McpTimeoutException> { client.callTool("hang", JsonObject(emptyMap()), 1.seconds) }
            val hung = server.events.await { it.startsWith("hanging on ") }.removePrefix("hanging on ")
            server.events.await { it == "cancelled $hung" }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a legacy response stream that breaks off is resumed from its last event`() = realTime {
        val server = server(legacy = true)
        val client = connect(server)
        try {
            assertEquals(text("resumed"), client.callTool("resume", JsonObject(emptyMap()), 10.seconds))
            server.events.await { it == "resumed" }
        } finally {
            client.close()
        }
    }

    @Test
    fun `an expired session is started again and the request sent again`() = realTime {
        val server = server(legacy = true)
        val client = connect(server)
        try {
            server.expire()
            assertEquals(text("again"), client.callTool("echo", args("text" to "again"), 10.seconds))
            assertEquals(
                "session-2",
                server.received.last {
                    it.rpcMethod == McpClient.TOOLS_CALL
                }.header("Mcp-Session-Id"),
            )
            assertEquals(2, server.received.count { it.rpcMethod == McpHttpConnection.INITIALIZE })
        } finally {
            client.close()
        }
    }
}
