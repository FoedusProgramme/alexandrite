package org.unitedsoftware.alexandrite.provider.common.messages

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.internal.http.HttpResponseStream
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.provider.common.EndpointCore
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.ErrorDetail
import org.unitedsoftware.alexandrite.provider.common.WireHeaders
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

/** What an endpoint of the Messages API does beyond its [EndpointSettings]. */
@Serializable
public class MessagesSettings(
    /** Whether requests mark cache breakpoints. */
    public val promptCaching: Boolean = true,
    /** Beta features that every request asks for in `anthropic-beta`. */
    public val betas: List<String> = emptyList(),
    /** Whether the endpoint's models take turn-scoped system messages, which then carry trusted turn context. */
    public val turnScopedSystem: Boolean = false,
    /** Whether the endpoint's models search the web with the backend's own search tool ([ModelInfo.webSearch]). */
    public val nativeSearch: Boolean = false,
) {
    init {
        for (beta in betas) {
            require(beta.isNotEmpty() && beta.none { it == ',' || it.isWhitespace() || it.isISOControl() }) {
                "betas holds an empty or malformed name"
            }
        }
    }
}

/**
 * An endpoint that speaks the Messages API in [flavor] at `{baseUrl}/v1/messages` through [engine], which describes
 * each model by the last listing it read.
 */
public class MessagesEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    engine: HttpEngine,
    private val flavor: MessagesFlavor,
    private val messages: MessagesSettings = MessagesSettings(),
) : ModelEndpoint,
    AutoCloseable {
    private val core = EndpointCore(
        id,
        settings,
        engine,
        flavor.listing,
        flavor.dialect,
        SENDS,
        mapOf("anthropic-version" to flavor.version),
        WIRE,
    )

    override suspend fun webSearch(model: String, query: WebQuery): List<WebSearchResult> {
        if (!messages.nativeSearch) return super.webSearch(model, query)
        return nativeSearch(core, model, query, messages.betas, ::classify)
    }

    override suspend fun models(): List<ModelInfo> = core.models(messages.nativeSearch)

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = core.admit(request)
        val built = MessagesRequest(request, info, flavor, messages, settings.turnContext) { trust ->
            mode(info, request.options, trust)
        }
        val response = MessagesResponse(request, flavor, built.warnings, built.kept)
        val headers = built.betas.takeIf { it.isNotEmpty() }?.let { mapOf("anthropic-beta" to it.joinToString(",")) }
        core.post("/v1/messages", built.body.toString(), headers.orEmpty(), { response.started }, ::classify) {
            read(it, response)
        }
        response.finish().forEach { emit(it) }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        mode(describe(model), options, trust)

    override fun close() {
        core.close()
    }

    internal fun describe(model: String): ModelInfo = core.describe(model)

    private fun mode(info: ModelInfo, options: ModelOptions, trust: Trust): TurnContextMode {
        val effort = options.reasoning?.takeIf { it in info.reasoningEfforts }
        val thinks = flavor.reasoning.thinks(effort, info)
        return flavor.turnContext.mode(trust, settings.turnContext, messages.turnScopedSystem, thinks)
    }

    private fun classify(detail: ErrorDetail): ModelErrorKind? = flavor.errors.kind(detail.type, detail.message)

    private suspend fun FlowCollector<ModelEvent>.read(stream: HttpResponseStream, response: MessagesResponse) {
        when (stream.mediaType) {
            "text/event-stream" -> {
                stream.events().transformWhile { event ->
                    emit(event)
                    !response.done
                }.collect { event ->
                    if (event.data.isNotBlank()) {
                        val data = core.json(event.data, response.started)
                        response.event(data, event.type, ::classify).forEach { emit(it) }
                    }
                }
                if (response.done) stream.drain()
            }

            "application/json", null -> {
                val message = core.json(stream.text(HttpTransport.DEFAULT_LIMIT), false)
                response.message(message, ::classify).forEach { emit(it) }
            }

            else -> throw core.unreadable(stream.mediaType, response.started)
        }
    }

    public companion object {
        /** What the Messages API sets on every request itself: the key in `x-api-key`, its version and betas. */
        public val WIRE: WireHeaders = WireHeaders("x-api-key", setOf("anthropic-version", "anthropic-beta"))
    }
}

/** The media kinds that the Messages API carries here. */
private val SENDS = setOf(MediaKind.IMAGE)
