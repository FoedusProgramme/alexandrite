package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpResponseStream
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind

/** What the endpoints of every wire API share: the connection, the models and the checks of a request. */
internal class EndpointCore(
    val id: EndpointId,
    settings: EndpointSettings,
    engine: HttpEngine,
    listing: ModelListing,
    dialect: Dialect,
    /** The media kinds the wire API carries, which bound what any model of the endpoint is said to take. */
    sends: Set<MediaKind>,
    own: Map<String, String> = emptyMap(),
    /** What the wire API sets itself, unless [settings] names otherwise. */
    wire: WireHeaders = WireHeaders.BEARER,
) : AutoCloseable {
    init {
        if (settings.wire == null) {
            val reserved = wire.own.map { it.lowercase() }
            settings.headers.keys.firstOrNull { it.lowercase() in reserved }?.let {
                throw IllegalArgumentException(
                    "The headers of endpoint $id may not set ${it.lowercase()}, which the provider sets itself",
                )
            }
        }
    }

    private val connection = EndpointConnection(settings, engine, own, settings.wire ?: wire)
    private val catalog = ModelCatalog(id, settings, listing, dialect, sends, connection)

    /** The models, which search the web natively when [nativeSearch] holds. */
    suspend fun models(nativeSearch: Boolean = false): List<ModelInfo> = catalog.models().let { models ->
        if (nativeSearch) models.map { it.rebuild { webSearch(true) } } else models
    }

    fun describe(model: String): ModelInfo = catalog.describe(model)

    /** The model of [request], which this endpoint can answer. */
    fun admit(request: ModelRequest): ModelInfo {
        if (request.model.endpoint != id) {
            throw modelException(
                ModelErrorKind.INVALID_REQUEST,
                "Endpoint '$id' got a request for ${request.model}.",
                false,
            )
        }
        val info = describe(request.model.model)
        if (request.tools.isNotEmpty() && !info.nativeTools) {
            throw modelException(ModelErrorKind.UNSUPPORTED, "Model ${request.model} takes no tools.", false)
        }
        return info
    }

    /**
     * Posts [body] with the headers [extra] to [path] below the base URL and hands the response to [read], where a
     * failed exchange becomes a [ModelException] whose kind [classify] may refine and whose output [started] tells.
     */
    suspend fun post(
        path: String,
        body: String,
        extra: Map<String, String>,
        started: () -> Boolean,
        classify: (ErrorDetail) -> ModelErrorKind? = { null },
        read: suspend (HttpResponseStream) -> Unit,
    ) {
        val call = connection.call("POST", "${connection.base}$path", "text/event-stream", body, extra)
        try {
            connection.transport.open(call, read)
        } catch (e: HttpException) {
            throw e.toModelException(started(), classify)
        } catch (e: ModelException) {
            throw e.masked(call::mask)
        }
    }

    /** The JSON object that [path] answers to a non-streamed post of [body]. */
    suspend fun postJson(
        path: String,
        body: String,
        extra: Map<String, String>,
        classify: (ErrorDetail) -> ModelErrorKind? = { null },
    ): JsonObject {
        var answer: JsonObject? = null
        post(path, body, extra, { false }, classify) { response ->
            if (response.mediaType != null && response.mediaType != "application/json") {
                throw unreadable(response.mediaType, false)
            }
            answer = try {
                Json.parseToJsonElement(response.text(HttpTransport.DEFAULT_LIMIT)) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        }
        return answer ?: protocol("Endpoint '$id' answered with no JSON object.")
    }

    /** The failure of a response in [mediaType], which no endpoint reads. */
    fun unreadable(mediaType: String?, outputStarted: Boolean): ModelException =
        modelException(ModelErrorKind.PROTOCOL, "Endpoint '$id' answered with $mediaType.", outputStarted)

    /** The event [text], which throws a [ModelException] when it holds no JSON object. */
    fun json(text: String, outputStarted: Boolean): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        }
        return parsed ?: throw modelException(
            ModelErrorKind.PROTOCOL,
            "Endpoint '$id' sent an event that is no JSON object.",
            outputStarted,
        )
    }

    override fun close() {
        connection.close()
    }
}
