package org.unitedsoftware.alexandrite.provider.common

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_API_KEY
import org.unitedsoftware.alexandrite.provider.common.chat.FIXTURE_TIMEOUTS
import org.unitedsoftware.alexandrite.provider.common.chat.chunk
import org.unitedsoftware.alexandrite.provider.common.chat.chunks
import org.unitedsoftware.alexandrite.provider.common.chat.with
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesFlavor
import org.unitedsoftware.alexandrite.provider.common.messages.messageStart
import org.unitedsoftware.alexandrite.provider.common.messages.messagesError
import org.unitedsoftware.alexandrite.provider.common.messages.sse
import org.unitedsoftware.alexandrite.provider.common.messages.textEvents
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecretMaskingTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val echo = "Key $FIXTURE_API_KEY with team $TEAM is refused."

    @AfterTest
    fun close() {
        server.close()
    }

    private fun settings(baseUrl: String, wire: WireHeaders = WireHeaders.BEARER) = EndpointSettings(
        baseUrl = baseUrl,
        apiKey = Secret(FIXTURE_API_KEY),
        headers = mapOf("X-Team" to Secret(TEAM)),
        models = mapOf("m" to ModelConfig()),
        discover = false,
        timeouts = FIXTURE_TIMEOUTS,
        wire = wire,
    )

    private fun chat() =
        ChatEndpoint(EndpointId("test"), settings("${server.baseUrl}/v1"), JdkHttpEngine(), ChatFlavor.STANDARD)

    private fun messages() = MessagesEndpoint(
        EndpointId("test"),
        settings(server.baseUrl, MessagesEndpoint.WIRE),
        JdkHttpEngine(),
        MessagesFlavor.STANDARD,
    )

    private fun request() = ModelRequest.builder(
        ModelRef(EndpointId("test"), "m"),
        listOf(testUserEntry(turn)),
        0,
        RequestIds(turn.conversation, turn.id, 0),
    ).build()

    private fun <E> E.failure(response: FakeResponse): ModelException where E : ModelEndpoint, E : AutoCloseable {
        server.enqueue(response)
        return use { endpoint ->
            assertFailsWith<ModelException> { runBlocking { endpoint.stream(request()).toList() } }
        }
    }

    private fun assertMasked(failure: ModelException) {
        val error: ModelError = failure.error
        val texts = listOfNotNull(error.message, error.rawType, failure.message) +
            generateSequence(failure.cause) { it.cause }.map { it.toString() }
        for (secret in listOf(FIXTURE_API_KEY, TEAM)) assertTrue(texts.none { secret in it }, "$texts")
        assertTrue("Key *** with team *** is refused." in error.message, error.message)
    }

    @Test
    fun `the configured headers go out with every request beside the key`() {
        server.enqueue(chunks(chunk { with("content", "Hi.") }, chunk("stop")))
        server.enqueue(sse(*textEvents(listOf("Hi."))))

        runBlocking {
            chat().use { it.stream(request()).toList() }
            messages().use { it.stream(request()).toList() }
        }

        val (chat, messages) = server.requests
        assertEquals(
            listOf(TEAM, "Bearer $FIXTURE_API_KEY"),
            listOf(chat.header("x-team"), chat.header("authorization")),
        )
        assertEquals(listOf(TEAM, FIXTURE_API_KEY), listOf(messages.header("x-team"), messages.header("x-api-key")))
        assertEquals(null, messages.header("authorization"))
        server.assertFinished()
    }

    @Test
    fun `settings without an auth header send the key in the wire API's own and keep its headers`() {
        fun plain(baseUrl: String) = EndpointSettings(
            baseUrl = baseUrl,
            apiKey = Secret(FIXTURE_API_KEY),
            models = mapOf("m" to ModelConfig()),
            discover = false,
            timeouts = FIXTURE_TIMEOUTS,
        )
        server.enqueue(chunks(chunk { with("content", "Hi.") }, chunk("stop")))
        server.enqueue(sse(*textEvents(listOf("Hi."))))

        runBlocking {
            ChatEndpoint(EndpointId("test"), plain("${server.baseUrl}/v1"), JdkHttpEngine(), ChatFlavor.STANDARD).use {
                it.stream(request()).toList()
            }
            MessagesEndpoint(EndpointId("test"), plain(server.baseUrl), JdkHttpEngine(), MessagesFlavor.STANDARD).use {
                it.stream(request()).toList()
            }
        }

        val (chat, messages) = server.requests
        assertEquals("Bearer $FIXTURE_API_KEY", chat.header("authorization"))
        assertEquals(
            listOf(FIXTURE_API_KEY, null, MessagesFlavor.STANDARD.version),
            listOf(
                messages.header("x-api-key"),
                messages.header("authorization"),
                messages.header("anthropic-version"),
            ),
        )
    }

    @Test
    fun `a status error that echoes the key or a configured header is masked`() {
        val body = """{"error":{"message":"$echo","type":"bad_$TEAM"}}"""

        val chat = chat().failure(FakeResponse.builder(400).body(body).build())
        val messages = messages().failure(FakeResponse.builder(400).body(messagesError("bad_$TEAM", echo)).build())

        assertMasked(chat)
        assertMasked(messages)
        assertEquals("bad_***", messages.error.rawType)
    }

    @Test
    fun `an error inside a stream that echoes the key or a configured header is masked`() {
        val error = Json.parseToJsonElement("""{"error":{"message":"$echo","type":"bad_$TEAM"}}""").jsonObject
        val messagesEvent = Json.parseToJsonElement(messagesError("bad_$TEAM", echo)).jsonObject

        val failures = listOf(
            chat().failure(chunks(chunk { with("content", "Hel") }, error, done = false)),
            chat().failure(FakeResponse.builder(200).body(error.toString()).build()),
            messages().failure(sse(messageStart(), messagesEvent)),
            messages().failure(FakeResponse.builder(200).body(messagesEvent.toString()).build()),
        )

        failures.forEach(::assertMasked)
        assertEquals(List(4) { "bad_***" }, failures.map { it.error.rawType })
        assertEquals(listOf(true, false, false, false), failures.map { it.error.outputStarted })
    }

    private companion object {
        const val TEAM = "team-secret-4711"
    }
}
