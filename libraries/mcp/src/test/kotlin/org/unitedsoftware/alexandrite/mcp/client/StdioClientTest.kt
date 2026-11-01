package org.unitedsoftware.alexandrite.mcp.client

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.process.ProcessTrees
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StdioClientTest {
    private val stderr = Lines()
    private val invalid = Lines()

    private suspend fun connect(mode: String, options: McpClientOptions = FAST): McpClient {
        val connection = McpStdioConnection.start(fakeLaunch(mode), stderr::add, invalid::add)
        return McpClient.connect(connection, CLIENT, options)
    }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // Negotiation.

    @Test
    fun `a modern server is spoken to statelessly after server discover`() = realTime {
        val client = connect("modern")
        try {
            val server = client.server
            assertEquals(McpVersions.MODERN, server.protocolVersion)
            assertFalse(server.legacy)
            assertEquals(McpImplementation("fake-modern", "1.0"), server.implementation)
            assertEquals("Use the echo tool.", server.instructions)
            assertTrue(server.toolsListChanged)
            assertFalse(stderr.all.any { it.startsWith("initialize") })
        } finally {
            client.close()
        }
    }

    @Test
    fun `a legacy server gets the handshake and its pings are answered`() = realTime {
        val client = connect("legacy")
        try {
            assertEquals("2025-11-25", client.server.protocolVersion)
            assertTrue(client.server.legacy)
            assertEquals("fake-legacy", client.server.implementation?.name)
            stderr.await { it == "initialize asked 2025-11-25" }
            stderr.await { it == "pong" }
            assertEquals(8, client.listTools().tools.size)
            assertFalse(stderr.all.any { it.startsWith("unexpected meta") }, stderr.all.toString())
        } finally {
            client.close()
        }
    }

    @Test
    fun `an older answer to the handshake is accepted when the client knows it`() = realTime {
        val client = connect("legacy-old")
        try {
            assertEquals("2025-06-18", client.server.protocolVersion)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a server that does not answer server discover is taken for a legacy one`() = realTime {
        val client = connect("silent-legacy", McpClientOptions(probeTimeout = 1.seconds))
        try {
            assertEquals("2025-11-25", client.server.protocolVersion)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a modern server without a common version fails without a handshake`() = realTime {
        val failure = assertFailsWith<McpProtocolException> { connect("unsupported") }
        assertContains(failure.message!!, "2027-01-01")
        assertFalse(stderr.all.any { it.startsWith("initialize") })
    }

    @Test
    fun `a handshake answered with an unknown version fails and ends the process`() = realTime {
        val failure = assertFailsWith<McpProtocolException> { connect("ancient") }
        assertContains(failure.message!!, "2024-11-05")
        stderr.await { it == "stdin closed" }
    }

    @Test
    fun `a server that crashes fails the connect with its exit code`() = realTime {
        val failure = assertFailsWith<McpConnectionException> { connect("crash") }
        assertContains(failure.message!!, "code 3")
        stderr.await { it == "crashing" }
    }

    @Test
    fun `output that is no message is handed on and skipped`() = realTime {
        val client = connect("noisy")
        try {
            invalid.await { it == "this is no JSON" }
            assertEquals(McpVersions.MODERN, client.server.protocolVersion)
        } finally {
            client.close()
        }
    }

    // Tools.

    @Test
    fun `the tool list is read page by page and an empty cursor is a page`() = realTime {
        val client = connect("modern")
        try {
            val list = client.listTools()
            assertEquals(
                listOf("echo", "kinds", "fail", "ask", "env", "hang", "change", "exit"),
                list.tools.map { it.name },
            )
            assertEquals(60.seconds, list.ttl)
            assertEquals(emptyList(), list.rejected)
            val echo = list.tools.first()
            assertEquals("The echo tool", echo.description)
            assertEquals(true, echo.annotations?.readOnlyHint)
            assertEquals("object", (echo.inputSchema["type"] as JsonPrimitive).content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a call returns every kind of content, structured content and errors`() = realTime {
        val client = connect("modern")
        try {
            assertEquals(
                McpCallOutcome.Completed(listOf(McpContent.Text("hi")), null, isError = false),
                client.callTool("echo", args("text" to "hi"), 10.seconds),
            )
            val kinds = assertIs<McpCallOutcome.Completed>(client.callTool("kinds", JsonObject(emptyMap()), 10.seconds))
            assertEquals(
                listOf(
                    McpContent.Text("hello"),
                    McpContent.Image("aW1n", "image/png"),
                    McpContent.Audio("YXVk", "audio/wav"),
                    McpContent.ResourceLink("file:///a.txt", "a.txt", null, "A file", "text/plain"),
                    McpContent.Resource("file:///b.txt", "text/plain", "bee", null),
                    McpContent.Unknown("hologram"),
                ),
                kinds.content,
            )
            assertEquals(buildJsonObject { put("answer", 42) }, kinds.structuredContent)
            val failed = assertIs<McpCallOutcome.Completed>(client.callTool("fail", JsonObject(emptyMap()), 10.seconds))
            assertTrue(failed.isError)
            val error =
                assertFailsWith<McpRpcException> { client.callTool("nothing", JsonObject(emptyMap()), 10.seconds) }
            assertEquals(-32602, error.code)
            assertContains(error.rpcMessage, "Unknown tool")
        } finally {
            client.close()
        }
    }

    @Test
    fun `a call that needs input comes back as such`() = realTime {
        val client = connect("modern")
        try {
            val outcome =
                assertIs<McpCallOutcome.NeedsInput>(client.callTool("ask", JsonObject(emptyMap()), 10.seconds))
            assertEquals("opaque-state", outcome.requestState)
            assertEquals(setOf("login"), outcome.inputRequests?.keys)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a legacy result without a result type is complete`() = realTime {
        val client = connect("legacy")
        try {
            assertEquals(
                McpCallOutcome.Completed(listOf(McpContent.Text("hi")), null, isError = false),
                client.callTool("echo", args("text" to "hi"), 10.seconds),
            )
        } finally {
            client.close()
        }
    }

    // Cancellation.

    @Test
    fun `a call that times out is cancelled on the server`() = realTime {
        val client = connect("modern")
        try {
            val failure = assertFailsWith<McpTimeoutException> {
                client.callTool("hang", JsonObject(emptyMap()), 1.seconds)
            }
            assertEquals("tools/call", failure.method)
            val hung = stderr.await { it.startsWith("hanging on ") }.removePrefix("hanging on ")
            stderr.await { it.startsWith("cancelled $hung:") }
            assertEquals(
                McpCallOutcome.Completed(listOf(McpContent.Text("ok")), null, false),
                client.callTool("echo", args("text" to "ok"), 10.seconds),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancelling the caller cancels the request on the server`() = realTime {
        val client = connect("modern")
        try {
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                client.callTool("hang", JsonObject(emptyMap()), 60.seconds)
            }
            val hung = stderr.await { it.startsWith("hanging on ") }.removePrefix("hanging on ")
            call.cancelAndJoin()
            assertContains(stderr.await { it.startsWith("cancelled $hung:") }, "cancelled the request")
        } finally {
            client.close()
        }
    }

    // Change notifications.

    @Test
    fun `a modern server's change reaches the client through its subscription`() = realTime {
        val client = connect("modern")
        try {
            val changed = async(start = CoroutineStart.UNDISPATCHED) { client.toolsChanged.first() }
            stderr.await { it == "subscribed" }
            client.callTool("change", JsonObject(emptyMap()), 10.seconds)
            withTimeout(10.seconds) { changed.await() }
            assertTrue(client.listTools().tools.any { it.name == "added" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `a legacy server's change notification reaches the client`() = realTime {
        val client = connect("legacy")
        try {
            val changed = async(start = CoroutineStart.UNDISPATCHED) { client.toolsChanged.first() }
            client.callTool("change", JsonObject(emptyMap()), 10.seconds)
            withTimeout(10.seconds) { changed.await() }
        } finally {
            client.close()
        }
    }

    // The process.

    @Test
    fun `a server that exits mid-call fails the call and ends the client`() = realTime {
        val client = connect("modern")
        try {
            val failure = assertFailsWith<McpConnectionException> {
                client.callTool("exit", JsonObject(emptyMap()), 10.seconds)
            }
            assertContains(failure.message!!, "code 4")
            assertContains(withTimeout(10.seconds) { client.ended.await() }.message!!, "code 4")
        } finally {
            client.close()
        }
    }

    @Test
    fun `close closes the server's input and it exits`() = realTime {
        val connection = McpStdioConnection.start(fakeLaunch("modern"), stderr::add)
        val client = McpClient.connect(connection, CLIENT, FAST)
        val pid = stderr.await { it.startsWith("pid ") }.removePrefix("pid ").toLong()
        client.close()
        stderr.await { it == "stdin closed" }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFailsWith<McpConnectionException> { client.listTools() }
    }

    @Test
    fun `close ends a server that ignores its closed input, with the processes it started`() = realTime {
        val connection = McpStdioConnection.start(fakeLaunch("stubborn"), stderr::add, stopGrace = 500.milliseconds)
        val client = McpClient.connect(connection, CLIENT, FAST)
        val pid = stderr.await { it.startsWith("pid ") }.removePrefix("pid ").toLong()
        val child = stderr.await { it.startsWith("child ") }.removePrefix("child ").toLong()
        assertTrue(ProcessHandle.of(child).map { it.isAlive }.orElse(false))
        client.close()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertFalse(ProcessHandle.of(child).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `without ProcessHandle, close ends the server itself and leaves the processes it started`() = realTime {
        val launch = fakeLaunch("stubborn").run { StdioLaunch(command, args, environment, trees = ProcessTrees.direct) }
        val connection = McpStdioConnection.start(launch, stderr::add, stopGrace = 500.milliseconds)
        val client = McpClient.connect(connection, CLIENT, FAST)
        val pid = stderr.await { it.startsWith("pid ") }.removePrefix("pid ").toLong()
        val child = ProcessHandle.of(stderr.await { it.startsWith("child ") }.removePrefix("child ").toLong())
        try {
            client.close()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertTrue(child.map { it.isAlive }.orElse(false))
        } finally {
            child.ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `the server gets the minimal environment and its own variables only`() = realTime {
        val host = mapOf(
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
            "HOME" to "/tmp/fake-home",
            "LANG" to "C.UTF-8",
            "PROVIDER_API_KEY" to "secret",
            "SHELL" to "/bin/zsh",
        )
        val connection = McpStdioConnection.start(fakeLaunch("modern", host, mapOf("LOG_LEVEL" to "warn")), stderr::add)
        val client = McpClient.connect(connection, CLIENT, FAST)
        try {
            val outcome = assertIs<McpCallOutcome.Completed>(client.callTool("env", JsonObject(emptyMap()), 10.seconds))
            val seen = (outcome.structuredContent as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            val ours = seen - MACOS_PROCESS_VARIABLE
            assertEquals(host - "PROVIDER_API_KEY" - "SHELL" + ("LOG_LEVEL" to "warn"), ours)
        } finally {
            client.close()
        }
    }
}

/** What macOS sets in every process itself. */
private const val MACOS_PROCESS_VARIABLE = "__CF_USER_TEXT_ENCODING"
