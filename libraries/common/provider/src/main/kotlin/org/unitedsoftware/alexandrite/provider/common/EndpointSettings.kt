package org.unitedsoftware.alexandrite.provider.common

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.isHeaderValue
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.ModelPrice
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import java.math.BigDecimal
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.seconds

/**
 * What a wire API sets on every request itself: [auth], the header that carries the API key, and the [own] headers it
 * sets besides, neither of which an endpoint's headers may set.
 */
@Poko
public class WireHeaders(public val auth: String, public val own: Set<String> = emptySet()) {
    public companion object {
        /** The key as a bearer token in `authorization`, as the Chat Completions and Responses APIs take it. */
        public val BEARER: WireHeaders = WireHeaders(EndpointSettings.DEFAULT_AUTH_HEADER)
    }
}

/** One configured endpoint: where it is, how it signs in and what the operator says about its models. */
public class EndpointSettings(
    /** The URL that the API's paths follow. */
    public val baseUrl: String,
    public val apiKey: Secret? = null,
    /** Headers sent with every request. */
    public val headers: Map<String, Secret> = emptyMap(),
    /** Models the endpoint serves besides the ones it lists, and what overrides the listing for each. */
    public val models: Map<String, ModelConfig> = emptyMap(),
    /** Whether the endpoint asks the backend for its models. */
    public val discover: Boolean = true,
    public val turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    public val timeouts: TimeoutConfig = TimeoutConfig(),
    /**
     * What the endpoint's wire API sets on every request itself, the header that carries [apiKey] among it; null for
     * what the endpoint's wire API names, which the endpoint then checks [headers] against.
     */
    public val wire: WireHeaders? = null,
) {
    init {
        require(isHttpUrl(baseUrl)) { "baseUrl must be an http or https URL without user info, query or fragment" }
        val reserved = RESERVED_HEADERS + wire?.own.orEmpty().map { it.lowercase() }
        require(apiKey == null || isHeaderValue(apiKey.reveal())) {
            "apiKey holds a control character or one beyond Latin-1"
        }
        for ((name, value) in headers) {
            require(name.isNotEmpty() && name.all { it in TOKEN }) { "headers holds a malformed header name" }
            require(name.lowercase() !in reserved) {
                "headers may not set ${name.lowercase()}, which the provider sets itself"
            }
            require(isHeaderValue(value.reveal())) {
                "headers.$name holds a control character or one beyond Latin-1"
            }
        }
        val keyHeaders = wire?.auth?.let(::listOf) ?: WIRE_AUTH_HEADERS
        headers.keys.firstOrNull { name -> apiKey != null && keyHeaders.any { it.equals(name, ignoreCase = true) } }
            ?.let { throw IllegalArgumentException("headers may not set ${it.lowercase()} when apiKey is set") }
        for (model in models.keys) {
            require(model.isNotEmpty() && model.none(Char::isISOControl)) { "models holds an empty or malformed id" }
        }
    }

    internal companion object {
        /** The header that carries the API key unless the wire API names another. */
        const val DEFAULT_AUTH_HEADER = "authorization"

        /** The headers that the wire APIs carry the API key in. */
        private val WIRE_AUTH_HEADERS = listOf(DEFAULT_AUTH_HEADER, "x-api-key")

        private val RESERVED_HEADERS =
            setOf("accept", "connection", "content-length", "content-type", "expect", "host", "upgrade")

        private val TOKEN = ('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()

        private fun isHttpUrl(text: String): Boolean = try {
            val uri = URI(text)
            uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null
        } catch (e: URISyntaxException) {
            false
        }
    }
}

/** How an endpoint renders turn context. */
@Serializable(with = TurnContextSerializer::class)
public enum class TurnContextSetting(internal val id: String) {
    /** Into one request alone. */
    TRANSIENT("transient"),

    /** Into the turn's user entry. */
    BAKE("bake"),
}

/** The turn context mode of an endpoint whose models all render turn context alike. */
internal fun EndpointSettings.turnContextMode(): TurnContextMode =
    if (turnContext == TurnContextSetting.TRANSIENT) TurnContextMode.TRANSIENT else TurnContextMode.NOT_SUPPORTED

internal object TurnContextSerializer : KSerializer<TurnContextSetting> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "org.unitedsoftware.alexandrite.provider.common.TurnContextSetting",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: TurnContextSetting) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): TurnContextSetting {
        val id = decoder.decodeString()
        return requireNotNull(TurnContextSetting.entries.firstOrNull { it.id == id }) {
            "turnContext must be \"transient\" or \"bake\""
        }
    }
}

/** What the operator says about one model, null where the backend or the provider decides. */
@Serializable
public class ModelConfig(
    public val displayName: String? = null,
    /** Wins over the window that the backend lists. */
    public val contextWindow: Int? = null,
    /** Wins over the limit that the backend lists. */
    public val maxOutputTokens: Int? = null,
    public val nativeTools: Boolean? = null,
    public val parallelToolCalls: Boolean? = null,
    /** Media kinds such as `image`. */
    public val inputMedia: Set<String>? = null,
    public val reasoningEfforts: Set<ReasoningEffort>? = null,
    /** Wins over the price that the backend lists. */
    public val price: PriceConfig? = null,
) {
    init {
        require(contextWindow == null || contextWindow > 0) { "contextWindow must be positive" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        inputMedia?.forEach { kind ->
            require(MediaKind.entries.any { it.id == kind }) {
                "inputMedia holds '$kind', which is none of ${MediaKind.entries.joinToString()}"
            }
        }
        reasoningEfforts?.forEach { effort ->
            require(effort in ReasoningEffort.entries) {
                "reasoningEfforts holds '$effort', which is none of ${ReasoningEffort.entries.joinToString()}"
            }
        }
    }

    internal fun facts(id: String): ModelFacts = ModelFacts(
        id = id,
        displayName = displayName,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        nativeTools = nativeTools,
        parallelToolCalls = parallelToolCalls,
        inputMedia = inputMedia?.mapTo(mutableSetOf(), MediaKind::of),
        reasoningEfforts = reasoningEfforts,
        price = price?.price(),
    )
}

/** What a model costs, in US dollars per million tokens. */
@Serializable
public class PriceConfig(
    public val input: Double,
    public val output: Double,
    /** Prompt tokens read from the cache, at the [input] price when null. */
    public val cached: Double? = null,
) {
    init {
        for ((name, price) in listOf("input" to input, "output" to output, "cached" to cached)) {
            require(price == null || (price.isFinite() && price >= 0)) { "price.$name must be a number of at least 0" }
        }
    }

    internal fun price(): ModelPrice =
        ModelPrice(BigDecimal.valueOf(input), BigDecimal.valueOf(output), cached?.let(BigDecimal::valueOf))
}

/** How long an endpoint waits for its backend. */
@Serializable
public class TimeoutConfig(
    public val connectSeconds: Long = 10,
    /** From sending a request until the first byte of its response body. */
    public val firstByteSeconds: Long = 300,
    /** The longest pause within a response body. */
    public val idleSeconds: Long = 120,
) {
    init {
        require(connectSeconds > 0 && firstByteSeconds > 0 && idleSeconds > 0) { "every timeout must be positive" }
    }

    internal fun timeouts(): HttpTimeouts =
        HttpTimeouts(connectSeconds.seconds, firstByteSeconds.seconds, idleSeconds.seconds)
}
