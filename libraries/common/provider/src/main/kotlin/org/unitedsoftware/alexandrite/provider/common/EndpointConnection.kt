package org.unitedsoftware.alexandrite.provider.common

import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import java.net.URI

/**
 * The HTTP side of one endpoint, which sends through [engine] the API key of [settings] in [wire]'s header and [own]
 * with every request.
 */
internal class EndpointConnection(
    settings: EndpointSettings,
    engine: HttpEngine,
    own: Map<String, String>,
    wire: WireHeaders,
) : AutoCloseable {
    val base: String = settings.baseUrl.trimEnd('/')
    private val own = auth(settings, wire.auth) + own
    private val headers = settings.headers.entries.associate { it.key.lowercase() to it.value.reveal() }
    private val secrets = listOfNotNull(settings.apiKey?.reveal()) + headers.values

    val transport: HttpTransport = HttpTransport(settings.timeouts.timeouts(), engine)

    fun call(
        method: String,
        url: String,
        accept: String,
        body: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpCall {
        val all = buildMap {
            putAll(headers)
            put("accept", accept)
            put("content-type", "application/json")
            putAll(own)
            putAll(extra)
        }
        return HttpCall(method, URI(url), all, body, secrets)
    }

    override fun close() {
        transport.close()
    }
}

/** The API key of [settings] in [header], after `Bearer` in `authorization`. */
private fun auth(settings: EndpointSettings, header: String): Map<String, String> {
    val key = settings.apiKey?.reveal() ?: return emptyMap()
    val name = header.lowercase()
    return mapOf(name to if (name == EndpointSettings.DEFAULT_AUTH_HEADER) "Bearer $key" else key)
}
