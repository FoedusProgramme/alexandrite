package org.unitedsoftware.alexandrite.provider.pleochroic

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.chat.chatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.chunk
import org.unitedsoftware.alexandrite.provider.common.chat.chunks
import org.unitedsoftware.alexandrite.provider.common.chat.with
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PleochroicFlavorTest {
    @Test
    fun `the listing reads each model's capabilities in the OpenRouter shape`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse { body(PleochroicFixture().listing().toString()) }, "/v1/models")

            val models = chatEndpoint(server, PLEOCHROIC_FLAVOR).use { it.models() }.associateBy { it.id }

            val chat = models.getValue("chat-model")
            assertTrue(chat.nativeTools && chat.parallelToolCalls)
            assertEquals(128000 to 32000, chat.contextWindow to chat.maxOutputTokens)
            assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
            assertEquals(ReasoningEffort.entries.toSet() - ReasoningEffort.MAX, chat.reasoningEfforts)

            val plain = models.getValue("plain-model")
            assertTrue(!plain.nativeTools)
            assertEquals(emptySet(), plain.reasoningEfforts)
        }
    }

    @Test
    fun `a reasoning effort is asked for with the flat field, and thinking arrives as reasoning_content`() =
        runBlocking {
            FakeModelServer().use { server ->
                server.enqueue(
                    chunks(
                        chunk { with("reasoning_content", "Thinking it over.") },
                        chunk { with("content", "Done.") },
                        chunk("stop"),
                    ),
                )
                val endpoint = chatEndpoint(
                    server,
                    PLEOCHROIC_FLAVOR,
                    models = mapOf("chat-model" to ModelConfig(reasoningEfforts = setOf(ReasoningEffort.HIGH))),
                    discover = false,
                )
                val turn = testTurn()
                val request = ModelRequest.builder(
                    ModelRef(endpoint.id, "chat-model"),
                    listOf(testUserEntry(turn)),
                    0,
                    RequestIds(turn.conversation, turn.id, 0),
                ).options(ModelOptions.builder().reasoning(ReasoningEffort.HIGH).build()).build()

                val events = endpoint.stream(request).toList()

                assertEquals("high", server.requests.single().json()["reasoning_effort"]?.jsonPrimitive?.content)
                val completed = events.filterIsInstance<ModelEvent.Completed>().single()
                assertEquals(FinishKind.END_TURN, completed.finish.kind)
                val reasoning = completed.message.parts.filterIsInstance<ReasoningPart>().single()
                assertEquals("Thinking it over.", reasoning.text)
            }
        }
}
