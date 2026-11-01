package org.unitedsoftware.alexandrite.provider.common

import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesEndpoint
import org.unitedsoftware.alexandrite.provider.common.messages.MessagesFlavor
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EndpointSettingsTest {
    private fun refused(expected: String, block: () -> Unit) {
        val message = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()
        assertTrue(expected in message, message)
    }

    @Test
    fun `a base URL is plain http or https`() {
        for (url in listOf("ftp://example.com", "https://user:pw@example.com", "https://example.com?a=b", "nope")) {
            refused("baseUrl must be") { EndpointSettings(url) }
        }
        EndpointSettings("http://127.0.0.1:1234/v1")
    }

    @Test
    fun `headers are well-formed and leave the provider's own headers alone`() {
        refused("malformed header name") {
            EndpointSettings("https://a.example", headers = mapOf("a b" to Secret("x")))
        }
        refused("may not set host") { EndpointSettings("https://a.example", headers = mapOf("Host" to Secret("x"))) }
        refused("authorization when apiKey") {
            EndpointSettings("https://a.example", Secret("k"), headers = mapOf("Authorization" to Secret("x")))
        }
        EndpointSettings("https://a.example", headers = mapOf("Authorization" to Secret("x")))
    }

    @Test
    fun `a key or a header value that no request can carry is refused without its value`() {
        refused("apiKey holds a control character") { EndpointSettings("https://a.example", Secret("sk-a\r\nb")) }
        refused("headers.X-Team holds a control character") {
            EndpointSettings("https://a.example", headers = mapOf("X-Team" to Secret("core\u200b")))
        }
        EndpointSettings("https://a.example", Secret("sk-\u00e9\tb"), mapOf("X-Team" to Secret("caf\u00e9")))
    }

    @Test
    fun `a wire API's own headers and auth header are the provider's`() {
        fun messages(headers: Map<String, Secret>, apiKey: Secret? = Secret("k")) = EndpointSettings(
            "https://a.example",
            apiKey,
            headers,
            wire = WireHeaders("x-api-key", setOf("anthropic-version")),
        )

        refused("may not set anthropic-version") { messages(mapOf("Anthropic-Version" to Secret("x"))) }
        refused("may not set x-api-key when apiKey") { messages(mapOf("X-Api-Key" to Secret("x"))) }
        messages(mapOf("X-Api-Key" to Secret("x")), apiKey = null)
        messages(mapOf("Authorization" to Secret("x")))
    }

    @Test
    fun `an endpoint keeps its wire API's own headers even when its settings name no wire API`() {
        val settings = EndpointSettings("https://a.example", headers = mapOf("Anthropic-Version" to Secret("x")))

        refused("may not set anthropic-version") {
            MessagesEndpoint(EndpointId("test"), settings, testHttpEngine(), MessagesFlavor.STANDARD)
        }
        ChatEndpoint(EndpointId("test"), settings, testHttpEngine(), ChatFlavor.STANDARD).close()
    }

    @Test
    fun `configured models and their facts are well-formed`() {
        refused("models holds") { EndpointSettings("https://a.example", models = mapOf("" to ModelConfig())) }
        refused("inputMedia holds 'smell'") { ModelConfig(inputMedia = setOf("smell")) }
        refused("reasoningEfforts holds 'huge'") { ModelConfig(reasoningEfforts = setOf(ReasoningEffort.of("huge"))) }
        refused("contextWindow must be positive") { ModelConfig(contextWindow = 0) }
        refused("every timeout must be positive") { TimeoutConfig(idleSeconds = 0) }
    }
}
