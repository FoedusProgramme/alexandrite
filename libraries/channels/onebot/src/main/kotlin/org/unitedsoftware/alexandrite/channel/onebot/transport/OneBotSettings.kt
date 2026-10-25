package org.unitedsoftware.alexandrite.channel.onebot.transport

import org.unitedsoftware.alexandrite.channel.onebot.auth.Value
import org.unitedsoftware.alexandrite.channel.onebot.config.OneBotConfig
import org.unitedsoftware.alexandrite.channel.onebot.config.OneBotInstanceConfig

/**
 * What one transport of one instance needs, gathered from the plugin's settings and the instance's own.
 *
 * A transport takes this alone, so that it never reads a setting its own instance cannot hold.
 */
internal class OneBotSettings(
    /** The URL of the transport that calls its implementation, null for the ones that listen. */
    val endpoint: String?,
    val listenHost: String,
    val listenPort: Int,
    val path: String,
    val token: Value?,
    val secret: Value?,
    val selfId: String?,
    val connectTimeoutMillis: Long,
    val firstByteTimeoutMillis: Long,
    val idleTimeoutMillis: Long,
    val reconnectIntervalMillis: Long,
    val eventCapacity: Int,
) {
    /** The endpoint of a transport that calls its implementation, which one that listens does not hold. */
    fun requireEndpoint(): String = requireNotNull(endpoint) { "This instance listens for its implementation." }

    companion object {
        /** The settings of [instance] below [plugin]. */
        fun of(plugin: OneBotConfig, instance: OneBotInstanceConfig): OneBotSettings = OneBotSettings(
            endpoint = instance.endpoint,
            listenHost = instance.listenHost,
            listenPort = instance.listenPort,
            path = instance.path,
            token = Value.orNull(instance.accessToken),
            secret = Value.orNull(instance.secret),
            selfId = instance.selfId,
            connectTimeoutMillis = instance.connectTimeoutMillis,
            firstByteTimeoutMillis = instance.firstByteTimeoutMillis,
            idleTimeoutMillis = instance.idleTimeoutMillis,
            reconnectIntervalMillis = instance.reconnectIntervalMillis,
            eventCapacity = plugin.eventQueueCapacity,
        )
    }
}
