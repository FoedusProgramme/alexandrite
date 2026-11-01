package org.unitedsoftware.alexandrite.provider.common.responses

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.unitedsoftware.alexandrite.internal.http.HttpResponseStream
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.provider.common.EndpointCore
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.ErrorDetail
import org.unitedsoftware.alexandrite.provider.common.turnContextMode
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.web.WebQuery
import org.unitedsoftware.alexandrite.sdk.web.WebSearchResult

/** What an endpoint of the Responses API does beyond its [EndpointSettings]. */
@Serializable
public class ResponsesSettings(
    /** Whether requests carry the agent's cache key. */
    public val promptCacheKey: Boolean = true,
    /** The `reasoning.summary` that requests to reasoning models ask for, null for none. */
    public val reasoningSummary: ReasoningSummary? = ReasoningSummary.AUTO,
    /** Whether the endpoint's models search the web with the backend's `web_search` tool. */
    public val nativeSearch: Boolean = false,
)

/** How much of its reasoning a model of the Responses API sums up. */
@Serializable(with = ReasoningSummarySerializer::class)
public enum class ReasoningSummary(internal val id: String) {
    AUTO("auto"),
    CONCISE("concise"),
    DETAILED("detailed"),
}

internal object ReasoningSummarySerializer : KSerializer<ReasoningSummary> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "org.unitedsoftware.alexandrite.provider.common.responses.ReasoningSummary",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: ReasoningSummary) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): ReasoningSummary {
        val id = decoder.decodeString()
        return requireNotNull(ReasoningSummary.entries.firstOrNull { it.id == id }) {
            "reasoningSummary must be one of ${ReasoningSummary.entries.joinToString { "\"${it.id}\"" }} or null"
        }
    }
}

/**
 * An endpoint that speaks the Responses API in [flavor] at `{baseUrl}/responses` through [engine], statelessly, which
 * describes each model by the last listing it read.
 */
public class ResponsesEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    engine: HttpEngine,
    private val flavor: ResponsesFlavor,
    private val responses: ResponsesSettings = ResponsesSettings(),
) : ModelEndpoint,
    AutoCloseable {
    private val core = EndpointCore(id, settings, engine, flavor.listing, flavor.dialect, SENDS)

    override suspend fun webSearch(model: String, query: WebQuery): List<WebSearchResult> {
        if (!responses.nativeSearch) return super.webSearch(model, query)
        return nativeSearch(core, model, query, ::classify)
    }

    override suspend fun models(): List<ModelInfo> = core.models(responses.nativeSearch)

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = core.admit(request)
        val built = ResponsesRequest(request, info, flavor, responses)
        val response = ResponsesResponse(request, flavor, built.warnings)
        core.post("/responses", built.body.toString(), emptyMap(), { response.started }, ::classify) { stream ->
            read(stream, response)
        }
        response.finish().forEach { emit(it) }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        settings.turnContextMode()

    override fun close() {
        core.close()
    }

    private fun classify(detail: ErrorDetail): ModelErrorKind? =
        flavor.errors.kind(detail.type, detail.code, detail.message)

    private suspend fun FlowCollector<ModelEvent>.read(stream: HttpResponseStream, response: ResponsesResponse) {
        when (stream.mediaType) {
            "text/event-stream" -> {
                stream.events().transformWhile { event ->
                    emit(event)
                    !response.done
                }.collect { event ->
                    if (event.data.isNotBlank() && event.data != DONE) {
                        val data = core.json(event.data, response.started)
                        response.event(data, event.type, ::classify).forEach { emit(it) }
                    }
                }
                if (response.done) stream.drain()
            }

            "application/json", null -> {
                val whole = core.json(stream.text(HttpTransport.DEFAULT_LIMIT), false)
                response.response(whole, ::classify).forEach { emit(it) }
            }

            else -> throw core.unreadable(stream.mediaType, response.started)
        }
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}

/** The media kinds that the Responses API carries here: images, and PDF files. */
private val SENDS = setOf(MediaKind.IMAGE, MediaKind.FILE)
