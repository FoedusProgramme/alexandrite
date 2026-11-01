package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.http.common.HttpResponseStream
import org.unitedsoftware.alexandrite.http.common.HttpTransport
import org.unitedsoftware.alexandrite.provider.common.EndpointCore
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings
import org.unitedsoftware.alexandrite.provider.common.modelException
import org.unitedsoftware.alexandrite.provider.common.pointedToResponses
import org.unitedsoftware.alexandrite.provider.common.turnContextMode
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.model.TurnContextMode
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind

/**
 * An endpoint that speaks Chat Completions in [flavor] at `{baseUrl}/chat/completions` through [engine], which
 * describes each model by the last listing it read.
 */
public class ChatEndpoint(
    override val id: EndpointId,
    private val settings: EndpointSettings,
    engine: HttpEngine,
    private val flavor: ChatFlavor,
    private val chat: ChatApiSettings = ChatApiSettings(),
) : ModelEndpoint,
    AutoCloseable {
    private val core = EndpointCore(id, settings, engine, flavor.listing, flavor.dialect, SENDS)

    override suspend fun models(): List<ModelInfo> = core.models()

    override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
        val info = core.admit(request)
        val built = ChatRequest(request, info, flavor, chat.promptCacheKey)
        val response = ChatResponse(request, flavor, built.warnings)
        try {
            core.post("/chat/completions", built.body.toString(), emptyMap(), { response.started }) { stream ->
                read(stream, response)
            }
        } catch (e: ModelException) {
            throw e.pointedToResponses()
        }
        response.finish().forEach { emit(it) }
    }

    override fun turnContextMode(model: String, options: ModelOptions, trust: Trust): TurnContextMode =
        settings.turnContextMode()

    override fun close() {
        core.close()
    }

    private suspend fun FlowCollector<ModelEvent>.read(stream: HttpResponseStream, response: ChatResponse) {
        when (stream.mediaType) {
            "text/event-stream" -> {
                var ended = false
                stream.events().takeWhile { event -> (event.data != DONE).also { ended = !it } }.collect { event ->
                    if (event.data.isNotBlank()) {
                        response.chunk(chunk(event.data, response.started)).forEach { emit(it) }
                    }
                }
                if (ended) stream.drain()
            }

            "application/json", null ->
                response.chunk(chunk(stream.text(HttpTransport.DEFAULT_LIMIT), false)).forEach { emit(it) }

            else -> throw core.unreadable(stream.mediaType, response.started)
        }
    }

    private fun chunk(text: String, outputStarted: Boolean): ChunkWire = try {
        JSON.decodeFromString(ChunkWire.serializer(), text)
    } catch (e: IllegalArgumentException) {
        throw modelException(
            ModelErrorKind.PROTOCOL,
            "Endpoint '$id' sent a chunk that is no JSON object.",
            outputStarted,
        )
    }

    private companion object {
        const val DONE = "[DONE]"
        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

/** The media kinds that the chat completions API carries here. */
private val SENDS = setOf(MediaKind.IMAGE)
