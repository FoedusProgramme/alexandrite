package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpFailureKind
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import java.net.URI

/** The models of the endpoint [id], each described by the config, the last listing [models] read and [listing]. */
internal class ModelCatalog(
    private val id: EndpointId,
    private val settings: EndpointSettings,
    private val listing: ModelListing,
    private val dialect: Dialect,
    private val sends: Set<MediaKind>,
    private val connection: EndpointConnection,
) {
    @Volatile
    private var listed: Map<String, ModelFacts> = emptyMap()

    suspend fun models(): List<ModelInfo> {
        val found = if (settings.discover) discover() else null
        found?.let { facts -> listed = facts.associateBy { it.id!! } }
        val ids = found.orEmpty().map { it.id!! } + settings.models.keys
        return ids.distinct().map(::describe)
    }

    fun describe(model: String): ModelInfo =
        modelInfo(model, dialect, settings.models[model]?.facts(model), listed[model], listing.defaults, sends)

    /** The models the backend lists, null when it has no listing or never listed one and the config names models. */
    private suspend fun discover(): List<ModelFacts>? {
        var failure: HttpException? = null
        val urls = try {
            read { listing.urls(connection.base) }
        } catch (e: HttpException) {
            failure = e
            emptyList()
        }
        for (url in urls) {
            try {
                return pages(url)
            } catch (e: HttpException) {
                failure = failure ?: e
                if (e.kind != HttpFailureKind.NOT_FOUND) break
            }
        }
        val error = failure?.toModelException(false) ?: return null
        if (listed.isNotEmpty() || settings.models.isEmpty()) throw error
        log.warn("Endpoint '{}' cannot list its models, so it describes the configured ones: {}", id, error.message)
        return null
    }

    private suspend fun pages(first: String): List<ModelFacts> {
        val models = mutableListOf<ModelFacts>()
        val origin = read { URI(first) }
        var next: String? = first
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val call = read { connection.call("GET", next, "application/json") }
            val page = page(connection.transport.send(call))
            models += read { listing.models(page).map(listing::facts) }.filter { facts ->
                facts.id?.let { id -> id.isNotEmpty() && id.none(Char::isISOControl) } == true
            }
            next = read { listing.next(page) }?.let { text ->
                val resolved = try {
                    origin.resolve(text)
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (resolved != null && sameOrigin(origin, resolved)) return@let resolved.toString()
                log.warn("Endpoint '{}' names its next page of models elsewhere or in no URL, so its listing ends", id)
                null
            }
        }
        return models.distinctBy { it.id }
    }

    private fun sameOrigin(origin: URI, other: URI): Boolean = origin.scheme.equals(other.scheme, ignoreCase = true) &&
        origin.host.equals(other.host, ignoreCase = true) &&
        port(origin) == port(other)

    private fun port(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> HTTPS_PORT
        else -> HTTP_PORT
    }

    /** What [block] returns, or an [HttpException] when it throws. */
    private inline fun <T> read(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw HttpException(HttpFailureKind.PROTOCOL, "Endpoint '$id' cannot read its listing of models.", cause = e)
    }

    private fun page(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw HttpException(HttpFailureKind.PROTOCOL, "Endpoint '$id' listed its models in no JSON object.")

    private companion object {
        const val MAX_PAGES = 20
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        val log = LoggerFactory.getLogger(ModelCatalog::class.java)
    }
}
