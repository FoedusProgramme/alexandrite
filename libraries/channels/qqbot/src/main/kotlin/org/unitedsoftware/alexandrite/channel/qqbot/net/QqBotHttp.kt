package org.unitedsoftware.alexandrite.channel.qqbot.net

import org.unitedsoftware.alexandrite.channel.qqbot.config.QqBotInstanceConfig
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpResponseStream
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/** The HTTP calls of one bot, over the transport the internal module keeps. */
@ChannelInstanceScoped
internal class QqBotHttp(private val config: QqBotInstanceConfig) : AutoCloseable {
    private val transport = HttpTransport(
        HttpTimeouts(
            connect = config.connectTimeoutMillis.milliseconds,
            firstByte = config.firstByteTimeoutMillis.milliseconds,
            idle = config.idleTimeoutMillis.milliseconds,
        ),
    )

    /** The body of the answer to [call] as it came, which a caller reads as JSON. */
    suspend fun send(call: HttpCall): String = transport.send(call)

    /** Runs [block] on the response to [call], for a caller that reads the body piece by piece. */
    suspend fun <T> open(call: HttpCall, block: suspend (HttpResponseStream) -> T): T = transport.open(call, block)

    /** The address of [path] below the API base of the instance. */
    fun uri(path: String): URI = URI(config.apiBase.trimEnd('/') + path)

    override fun close() = transport.close()
}
