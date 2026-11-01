package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointConfigTest {
    @Serializable
    private class Extra(val level: Int, val flavor: String = "plain")

    @Serializable
    private class Clash(val headers: Boolean = false)

    private object Strict : EndpointConfigSerializer<Extra>(
        "test.Strict",
        Extra.serializer(),
        baseUrl = null,
        keyRequired = true,
        turnContext = TurnContextSetting.BAKE,
        wire = WireHeaders("x-api-key", setOf("x-version")),
    )

    private object Plain : EndpointConfigSerializer<NoOptions>(
        "test.Plain",
        NoOptions.serializer(),
        baseUrl = "https://api.example.com/v1",
    )

    private fun <O> EndpointConfigSerializer<O>.read(json: String): EndpointConfig<O> =
        Json.decodeFromString(this, json)

    private fun refused(expected: String, block: () -> Unit) {
        val message = assertFailsWith<Exception> { block() }.message.orEmpty()
        assertTrue(expected in message, message)
    }

    @Test
    fun `the shared keys fill the settings and the others the provider's options`() {
        val config = Strict.read(
            """
            {"baseUrl": "https://proxy.example.com", "apiKey": "k", "headers": {"X-Team": "core"},
             "models": {"m": {"contextWindow": 1000, "reasoningEfforts": ["low"], "price": {"input": 1, "output": 2}}},
             "discover": false, "turnContext": "transient",
             "timeouts": {"connectSeconds": 1, "firstByteSeconds": 2, "idleSeconds": 3},
             "level": 7, "flavor": "spicy"}
            """.trimIndent(),
        )

        val settings = config.settings
        assertEquals("https://proxy.example.com" to Secret("k"), settings.baseUrl to settings.apiKey)
        assertEquals(mapOf("X-Team" to Secret("core")), settings.headers)
        val model = settings.models.getValue("m")
        assertEquals(1000 to setOf(ReasoningEffort.LOW), model.contextWindow to model.reasoningEfforts)
        assertEquals(1.0 to 2.0, model.price?.input to model.price?.output)
        assertEquals(false to TurnContextSetting.TRANSIENT, settings.discover to settings.turnContext)
        val timeouts = settings.timeouts
        assertEquals(
            listOf(1L, 2L, 3L),
            listOf(timeouts.connectSeconds, timeouts.firstByteSeconds, timeouts.idleSeconds),
        )
        assertEquals(WireHeaders("x-api-key", setOf("x-version")), settings.wire)
        assertEquals(7 to "spicy", config.options.level to config.options.flavor)
    }

    @Test
    fun `a key left out takes the provider's default`() {
        val strict = Strict.read("""{"baseUrl": "https://a.example", "apiKey": "k", "level": 1}""")
        assertEquals(TurnContextSetting.BAKE to "plain", strict.settings.turnContext to strict.options.flavor)

        val plain = Plain.read("{}").settings
        assertEquals("https://api.example.com/v1", plain.baseUrl)
        assertNull(plain.apiKey)
        assertEquals(true to TurnContextSetting.TRANSIENT, plain.discover to plain.turnContext)
        assertEquals(emptyMap(), plain.headers)
        assertEquals(300L, plain.timeouts.firstByteSeconds)
    }

    @Test
    fun `a missing, unknown or malformed key is refused`() {
        refused("baseUrl is required") { Strict.read("""{"apiKey": "k", "level": 1}""") }
        refused("apiKey is required") { Strict.read("""{"baseUrl": "https://a.example", "level": 1}""") }
        refused("apiKey is required") {
            Strict.read("""{"baseUrl": "https://a.example", "apiKey": null, "level": 1}""")
        }
        refused("level") { Strict.read("""{"baseUrl": "https://a.example", "apiKey": "k"}""") }
        refused("promptCacheKey") { Plain.read("""{"promptCacheKey": true}""") }
        refused("baseUrl") { Plain.read("""{"baseUrl": null}""") }
        refused("baseUrl must be") { Plain.read("""{"baseUrl": "ftp://a"}""") }
        refused("may not set x-version") {
            Strict.read(
                """{"baseUrl": "https://a.example", "apiKey": "k", "headers": {"X-Version": "1"}, "level": 1}""",
            )
        }
    }

    @Test
    fun `the descriptor names every key, so the config finds the secrets in it`() {
        val descriptor = Strict.descriptor
        val names = List(descriptor.elementsCount, descriptor::getElementName)

        assertEquals(
            listOf("baseUrl", "apiKey", "headers", "models", "discover", "turnContext", "timeouts", "level", "flavor"),
            names,
        )
        val secret = Secret.serializer().descriptor.serialName
        assertEquals(secret, descriptor.getElementDescriptor(1).serialName.removeSuffix("?"))
        assertEquals(secret, descriptor.getElementDescriptor(2).getElementDescriptor(1).serialName)
        assertEquals(listOf(false, false), listOf(0, 1).map(descriptor::isElementOptional))
        assertEquals(listOf(true, true), listOf(0, 1).map(Plain.descriptor::isElementOptional))
    }

    @Test
    fun `options may not repeat a shared key`() {
        refused("repeat the shared keys [headers]") {
            object : EndpointConfigSerializer<Clash>("test.Clash", Clash.serializer(), baseUrl = null) {}
        }
    }

    @Test
    fun `an endpoint written as JSON reads back the same`() {
        val text = """{"baseUrl": "https://a.example", "apiKey": "k", "headers": {"X-Team": "core"}, "level": 3}"""
        val config = Strict.read(text)

        val again = Strict.read(Json.encodeToString(Strict, config))

        assertEquals(config.settings.apiKey to config.settings.headers, again.settings.apiKey to again.settings.headers)
        assertEquals(config.options.level, again.options.level)
    }
}
