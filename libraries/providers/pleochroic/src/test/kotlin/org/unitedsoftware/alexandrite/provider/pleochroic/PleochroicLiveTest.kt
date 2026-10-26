package org.unitedsoftware.alexandrite.provider.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.events
import org.unitedsoftware.alexandrite.provider.common.read
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertTrue

/** Talks to Pleochroic with the `ALEXANDRITE_LIVE_PLEOCHROIC_*` variables, and skips without them. */
class PleochroicLiveTest {
    private val apiKey: String? = System.getenv("ALEXANDRITE_LIVE_PLEOCHROIC_API_KEY")
    private val model: String? = System.getenv("ALEXANDRITE_LIVE_PLEOCHROIC_MODEL")
    private val baseUrl: String? = System.getenv("ALEXANDRITE_LIVE_PLEOCHROIC_BASE_URL")
    private val turn = testTurn()

    private fun live(): ChatEndpoint {
        assumeTrue(
            apiKey != null && model != null && baseUrl != null,
            "No live Pleochroic key, model and base URL are configured.",
        )
        val config = EndpointSerializer.read(
            buildJsonObject {
                put("apiKey", apiKey)
                put("baseUrl", baseUrl)
            },
        )
        return ChatEndpoint(EndpointId("live"), config.settings, PLEOCHROIC_FLAVOR)
    }

    @Test
    fun `Pleochroic lists its models and streams a reply`() = runBlocking {
        live().use { endpoint ->
            val info = endpoint.models().first { it.id == model }
            println("Model: $info")
            val request = ModelRequest.builder(
                ModelRef(EndpointId("live"), model!!),
                listOf(testUserEntry(turn, "Say the word 'ready'.")),
                0,
                RequestIds(turn.conversation, turn.id, 0),
            ).options(ModelOptions.builder().maxOutputTokens(2000).build()).build()

            val events = endpoint.events(request)

            val completed = events.last() as ModelEvent.Completed
            println("Reply: ${completed.message.parts}, ${completed.finish}, ${completed.usage}")
            assertTrue(completed.message.parts.any { it is TextPart })
        }
    }
}
