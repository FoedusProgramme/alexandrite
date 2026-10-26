package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.auth.OneBotAuth
import org.unitedsoftware.alexandrite.channel.onebot.auth.Value
import org.unitedsoftware.alexandrite.channel.onebot.transport.OneBotHttpApi
import org.unitedsoftware.alexandrite.channel.onebot.transport.OneBotWire
import org.unitedsoftware.alexandrite.channel.onebot.transport.envelopeResult
import org.unitedsoftware.alexandrite.sdk.config.Secret
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The HTTP transport against a peer of this test, which reads the request line, the headers and the body and answers
 * with what the test told it to.
 */
class OneBotHttpTransportTest {
    private val peer = Peer()
    private val token = Value.of(Secret("s3cr3t-token"))
    private val client = OneBotHttpApi("http://127.0.0.1:${peer.port}", token, 5_000, 5_000, 5_000)

    @AfterTest
    fun close() {
        client.close()
        peer.close()
    }

    @Test
    fun `a successful call reads the data of the answer`() {
        peer.answer("""{"status":"ok","retcode":0,"data":{"user_id":10001000,"nickname":"someone"}}""")
        val result = runBlocking { client.call("get_login_info", JsonObject(emptyMap())) }
        val ok =
            assertIs<org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult.Ok<JsonObject>>(result)
        assertEquals("10001000", ok.data["user_id"]?.jsonPrimitive?.content)
        val request = peer.next()
        assertTrue(request.startsWith("POST /get_login_info"), "the call was $request")
        assertTrue(request.contains("Bearer s3cr3t-token"), "the token was not presented")
        assertTrue(request.contains("\"params\"") || request.endsWith("{}"), "the body was $request")
    }

    @Test
    fun `a call carries its token both ways the standard allows`() {
        peer.answer("""{"status":"ok","retcode":0,"data":{}}""")

        runBlocking { client.call("get_login_info", JsonObject(emptyMap())) }

        val request = peer.next()
        assertTrue(
            request.startsWith("POST /get_login_info?access_token=s3cr3t-token"),
            "the token is not in the query, so an implementation that reads only the query would refuse: $request",
        )
        assertTrue(
            request.contains("Authorization: Bearer s3cr3t-token"),
            "the token is not in the header, so one that reads only the header would refuse: $request",
        )
    }

    @Test
    fun `a refused call keeps the retcode of the implementation`() {
        peer.answer("""{"status":"failed","retcode":1004,"message":"too fast"}""")
        val result = runBlocking { client.call("send_private_msg", buildJsonObject { put("user_id", "1") }) }
        val failed =
            assertIs<org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult.Failed>(result)
        assertEquals(1004, failed.retcode)
        assertEquals("too fast", failed.message)
    }

    @Test
    fun `an implementation that is not there is unreachable, not a refusal`() {
        val absent = OneBotHttpApi("http://127.0.0.1:1", token, 1_000, 1_000, 1_000)
        try {
            val result = runBlocking { absent.call("get_status", JsonObject(emptyMap())) }
            assertIs<org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult.Unreachable>(result)
        } finally {
            absent.close()
        }
    }

    @Test
    fun `a missing token is reported as an authentication failure`() {
        peer.answer("""{"status":"failed","retcode":1401}""", status = 401)
        val result = runBlocking { client.call("get_status", JsonObject(emptyMap())) }
        val unreachable =
            assertIs<org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult.Unreachable>(result)
        assertEquals(
            org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotFailureKind.AUTHENTICATION,
            unreachable.failure.kind,
        )
    }

    @Test
    fun `an answer this version cannot read is malformed, and the token stays out of it`() {
        peer.answer("""{"hello":"world"}""")
        val result = runBlocking { client.call("get_status", JsonObject(emptyMap())) }
        val malformed =
            assertIs<org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult.Malformed>(result)
        assertTrue(!malformed.detail.contains("s3cr3t-token"), "the token leaked: ${malformed.detail}")
    }

    @Test
    fun `an answer that carries an array keeps it instead of an empty object`() {
        // The standard answers `get_friend_list` and `get_group_list` with an array, so an answer read as an object
        // alone became empty and every such call was reported as one this version cannot read.
        val answer = OneBotWire.parseToJsonElement(
            """{"status":"ok","retcode":0,"data":[{"user_id":10001000,"nickname":"someone"}]}""",
        ) as JsonObject

        val ok = assertIs<org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult.Ok<*>>(
            envelopeResult(answer),
        )
        assertEquals(1, assertIs<JsonArray>(ok.data).size)
    }

    @Test
    fun `an answer that carries an object keeps it`() {
        val answer = OneBotWire.parseToJsonElement("""{"status":"ok","retcode":0,"data":{"message_id":42}}""")
            as JsonObject

        val ok = assertIs<org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult.Ok<*>>(
            envelopeResult(answer),
        )
        assertEquals("42", (ok.data as? JsonObject)?.get("message_id")?.jsonPrimitive?.content)
    }

    /** A peer that answers one request with what the test set. */
    private class Peer {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val served = java.util.concurrent.LinkedBlockingQueue<String>()
        private val waiting = java.util.concurrent.LinkedBlockingQueue<String>()

        @Volatile
        private var status: Int = 200

        @Volatile
        private var answer: String = """{"status":"ok","retcode":0,"data":{}}"""

        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    socket.use { exchange(it) }
                }
            }
        }

        fun answer(json: String, status: Int = 200) {
            this.answer = json
            this.status = status
        }

        /** The request the peer answered last. */
        fun next(): String = waiting.poll() ?: served.poll() ?: error("the peer was called by nobody")

        private fun exchange(socket: Socket) {
            val input = BufferedInputStream(socket.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) return
                head.append(byte.toChar())
            }
            val length = head.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                if (count < 0) break
                read += count
            }
            val request = head.toString().trimEnd() + "\n" + String(body, StandardCharsets.UTF_8)
            served += request
            waiting += request
            val bytes = answer.toByteArray(StandardCharsets.UTF_8)
            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII)
            writer.write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }

        fun close() {
            runCatching { server.close() }
        }
    }
}
