package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.sdk.config.Secret

/** One entry of a provider's `endpoints`: the [settings] that every provider reads and the provider's own [options]. */
public class EndpointConfig<out O>(public val settings: EndpointSettings, public val options: O)

/** The options of a provider whose endpoints take none of their own. */
@Serializable
public object NoOptions

/**
 * Reads an [EndpointConfig] from one JSON object that holds the keys of [EndpointSettings] beside the keys of
 * [options].
 */
public open class EndpointConfigSerializer<O>(
    serialName: String,
    private val options: KSerializer<O>,
    /** Null when every endpoint names its own. */
    private val baseUrl: String?,
    private val keyRequired: Boolean = false,
    private val turnContext: TurnContextSetting = TurnContextSetting.TRANSIENT,
    /** As in [EndpointSettings.wire]. */
    private val wire: WireHeaders? = null,
) : KSerializer<EndpointConfig<O>> {
    init {
        val own = options.descriptor
        val shared = List(own.elementsCount, own::getElementName).filter { it in SHARED_KEYS }
        require(shared.isEmpty()) { "The options of $serialName repeat the shared keys $shared" }
    }

    final override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName) {
        element(BASE_URL, String.serializer().descriptor, isOptional = baseUrl != null)
        element(API_KEY, Secret.serializer().nullable.descriptor, isOptional = !keyRequired)
        element(HEADERS, HEADERS_SERIALIZER.descriptor, isOptional = true)
        element(MODELS, MODELS_SERIALIZER.descriptor, isOptional = true)
        element(DISCOVER, Boolean.serializer().descriptor, isOptional = true)
        element(TURN_CONTEXT, TurnContextSetting.serializer().descriptor, isOptional = true)
        element(TIMEOUTS, TimeoutConfig.serializer().descriptor, isOptional = true)
        val own = options.descriptor
        for (index in 0 until own.elementsCount) {
            element(
                own.getElementName(index),
                own.getElementDescriptor(index),
                isOptional = own.isElementOptional(index),
            )
        }
    }

    final override fun deserialize(decoder: Decoder): EndpointConfig<O> {
        val json = (decoder as? JsonDecoder)?.json ?: throw SerializationException("An endpoint is read from JSON")
        var url = baseUrl
        var apiKey: Secret? = null
        var headers = emptyMap<String, Secret>()
        var models = emptyMap<String, ModelConfig>()
        var discover = true
        var context = turnContext
        var timeouts = TimeoutConfig()
        val own = mutableMapOf<String, JsonElement>()
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                when (val name = descriptor.getElementName(index)) {
                    BASE_URL -> url = decodeStringElement(descriptor, index)

                    API_KEY -> apiKey = decodeSerializableElement(descriptor, index, Secret.serializer().nullable)

                    HEADERS -> headers = decodeSerializableElement(descriptor, index, HEADERS_SERIALIZER)

                    MODELS -> models = decodeSerializableElement(descriptor, index, MODELS_SERIALIZER)

                    DISCOVER -> discover = decodeBooleanElement(descriptor, index)

                    TURN_CONTEXT ->
                        context = decodeSerializableElement(descriptor, index, TurnContextSetting.serializer())

                    TIMEOUTS -> timeouts = decodeSerializableElement(descriptor, index, TimeoutConfig.serializer())

                    else -> own[name] = decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
            }
        }
        val settings = EndpointSettings(
            baseUrl = url ?: throw missing(BASE_URL),
            apiKey = apiKey ?: if (keyRequired) throw missing(API_KEY) else null,
            headers = headers,
            models = models,
            discover = discover,
            turnContext = context,
            timeouts = timeouts,
            wire = wire,
        )
        return EndpointConfig(settings, json.decodeFromJsonElement(options, JsonObject(own)))
    }

    final override fun serialize(encoder: Encoder, value: EndpointConfig<O>) {
        val json = (encoder as? JsonEncoder)?.json ?: throw SerializationException("An endpoint is written as JSON")
        val own = json.encodeToJsonElement(options, value.options).jsonObject
        val settings = value.settings
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, settings.baseUrl)
            settings.apiKey?.let { encodeSerializableElement(descriptor, 1, Secret.serializer(), it) }
            encodeSerializableElement(descriptor, 2, HEADERS_SERIALIZER, settings.headers)
            encodeSerializableElement(descriptor, 3, MODELS_SERIALIZER, settings.models)
            encodeBooleanElement(descriptor, 4, settings.discover)
            encodeSerializableElement(descriptor, 5, TurnContextSetting.serializer(), settings.turnContext)
            encodeSerializableElement(descriptor, 6, TimeoutConfig.serializer(), settings.timeouts)
            for ((name, element) in own) {
                encodeSerializableElement(
                    descriptor,
                    descriptor.getElementIndex(name),
                    JsonElement.serializer(),
                    element,
                )
            }
        }
    }

    private fun missing(key: String) = SerializationException("$key is required for each endpoint")

    private companion object {
        const val BASE_URL = "baseUrl"
        const val API_KEY = "apiKey"
        const val HEADERS = "headers"
        const val MODELS = "models"
        const val DISCOVER = "discover"
        const val TURN_CONTEXT = "turnContext"
        const val TIMEOUTS = "timeouts"
        val SHARED_KEYS = setOf(BASE_URL, API_KEY, HEADERS, MODELS, DISCOVER, TURN_CONTEXT, TIMEOUTS)
        val HEADERS_SERIALIZER = MapSerializer(String.serializer(), Secret.serializer())
        val MODELS_SERIALIZER = MapSerializer(String.serializer(), ModelConfig.serializer())
    }
}
