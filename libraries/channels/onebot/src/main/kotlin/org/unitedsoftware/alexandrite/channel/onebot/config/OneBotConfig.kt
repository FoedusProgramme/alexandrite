package org.unitedsoftware.alexandrite.channel.onebot.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.channel.common.command.COMMAND_NAME
import org.unitedsoftware.alexandrite.channel.common.command.CommandWho
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/** How an instance talks to its OneBot implementation. */
public enum class OneBotTransport {
    /** The implementation serves the API on the endpoint, and the client calls it. */
    HTTP,

    /** The implementation serves the API on the endpoint and reports events to the receiver. */
    HTTP_POST,

    /** The implementation serves the API and pushes events on the endpoint. */
    WS,

    /** The implementation connects to the receiver for the API and the events. */
    WS_REVERSE,
    ;

    /** Whether the client listens for the implementation, or calls it. */
    public val isReceiver: Boolean get() = this == HTTP_POST || this == WS_REVERSE

    public companion object {
        /** The transport [name] spells, null when it names none. */
        public fun of(name: String): OneBotTransport? = when (name.lowercase()) {
            "http" -> HTTP
            "http_post" -> HTTP_POST
            "ws", "websocket" -> WS
            "ws_reverse", "ws-reverse" -> WS_REVERSE
            else -> null
        }
    }
}

/**
 * Where the settings of the plugin sit below its config root, apart from the root its channel instances use.
 *
 * The paths of the two sections of this plugin have to differ, because a channel plugin's instances read the root of
 * their own config under `instances` and a section at the root would collide with them.
 */
internal const val ONEBOT_SETTINGS_PATH: String = "settings"

/**
 * The settings of the OneBot plugin itself, below its config root.
 *
 * What describes one connection lives in [OneBotInstanceConfig] instead, because a connection is an instance of the
 * channel and each instance has its own.
 */
@ConfigSection(path = ONEBOT_SETTINGS_PATH)
@Serializable
public class OneBotConfig(
    /** How many events wait for the plugin to read them before the newest are dropped. */
    @SerialName("eventQueueCapacity") public val eventQueueCapacity: Int = DEFAULT_EVENT_QUEUE,
) {
    init {
        require(eventQueueCapacity > 0) { "An event queue holds at least one event, was $eventQueueCapacity." }
    }

    public companion object {
        public const val DEFAULT_EVENT_QUEUE: Int = 1024
    }
}

/**
 * One connection to an OneBot implementation, below `instances` of the plugin's config root.
 *
 * A transport is either what the client calls or what it listens on. [HTTP] and [WS] use [endpoint]; [HTTP_POST]
 * and [WS_REVERSE] use [listenHost] and [listenPort].
 */
@ConfigSection
@ChannelInstanceScoped
@Serializable
public class OneBotInstanceConfig(
    /** How this instance reaches its implementation. */
    public val transport: String,
    /** The URL of [OneBotTransport.HTTP] and [OneBotTransport.WS], such as `http://127.0.0.1:3000`. */
    public val endpoint: String? = null,
    /** The address [OneBotTransport.HTTP_POST] and [OneBotTransport.WS_REVERSE] listen on. */
    @SerialName("listenHost") public val listenHost: String = DEFAULT_HOST,
    /** The port [OneBotTransport.HTTP_POST] and [OneBotTransport.WS_REVERSE] listen on. */
    @SerialName("listenPort") public val listenPort: Int = 0,
    /** The path [OneBotTransport.HTTP_POST] accepts reports on. */
    public val path: String = "/",
    /** The token the implementation checks, null when it checks none. */
    @SerialName("accessToken") public val accessToken: Secret? = null,
    /** The key the implementation signs its reports with, null when it signs none. */
    public val secret: Secret? = null,
    /** The account the implementation reports for, null when the plugin does not check it. */
    @SerialName("selfId") public val selfId: String? = null,
    /** How long reaching the implementation may take. */
    @SerialName("connectTimeoutMillis") public val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT,
    /** How long the first byte of an answer may take. */
    @SerialName("firstByteTimeoutMillis") public val firstByteTimeoutMillis: Long = DEFAULT_FIRST_BYTE_TIMEOUT,
    /** How long a call may stay silent before it is cut off. */
    @SerialName("idleTimeoutMillis") public val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT,
    /** How long to wait before connecting again, in milliseconds. */
    @SerialName("reconnectIntervalMillis") public val reconnectIntervalMillis: Long = DEFAULT_RECONNECT,
    /** The accounts that may operate this instance, which the channel marks as admins. */
    public val admins: Set<String> = emptySet(),
    /** The chats this instance serves, by their key, such as `group:987654` or `private:12345678`. */
    public val allowedChats: List<String> = emptyList(),
    /** How long an approval request waits for an admin's answer, in minutes. */
    @SerialName("approvalMinutes") public val approvalMinutes: Int = DEFAULT_APPROVAL_MINUTES,
    /** Whether this instance tells a chat what a turn is doing while it works. */
    public val progress: Boolean = true,
    /** Whether this instance shows what the model says while a turn works on tools. */
    public val streaming: Boolean = true,
    /**
     * Who may run each named command, over its default rule; the forms a command keeps for operators stay with
     * admins.
     */
    public val commands: Map<String, CommandWho> = emptyMap(),
) {
    /** How this instance reaches its implementation. */
    public val kind: OneBotTransport = OneBotTransport.of(transport) ?: throw IllegalArgumentException(
        "transport must be one of http, http_post, ws, ws_reverse, was '$transport'",
    )

    init {
        require(connectTimeoutMillis > 0) { "A connect timeout is positive, was $connectTimeoutMillis." }
        require(firstByteTimeoutMillis > 0) { "A first byte timeout is positive, was $firstByteTimeoutMillis." }
        require(idleTimeoutMillis > 0) { "An idle timeout is positive, was $idleTimeoutMillis." }
        require(reconnectIntervalMillis > 0) { "A reconnect interval is positive, was $reconnectIntervalMillis." }
        require(approvalMinutes > 0) { "An approval waits at least a minute, was $approvalMinutes." }
        if (kind.isReceiver) {
            // Port 0 lets the operating system pick one, which a test and a host that only needs to bind use; the
            // caller reads the port it took from the transport.
            require(listenPort in 0..MAX_PORT) {
                "transport '$transport' listens on a port between 0 and $MAX_PORT, was $listenPort"
            }
            require(path.startsWith("/")) { "A path starts with '/', was '$path'." }
            require(endpoint == null) {
                "transport '$transport' listens for its implementation and takes no endpoint, was '$endpoint'"
            }
        } else {
            require(endpoint != null) { "transport '$transport' needs the endpoint of its implementation" }
            val uri = try {
                java.net.URI(endpoint)
            } catch (e: java.net.URISyntaxException) {
                throw IllegalArgumentException("endpoint '$endpoint' is no URL: ${e.message}", e)
            }
            val schemes = if (kind == OneBotTransport.WS) setOf("ws", "wss") else setOf("http", "https")
            require(uri.scheme in schemes) {
                "transport '$transport' takes an endpoint of ${schemes.joinToString(" or ")}, was '$endpoint'"
            }
            require(uri.host != null) { "endpoint '$endpoint' names no host" }
        }
        if (selfId != null) {
            require(selfId.isNotEmpty() && selfId.all(Char::isDigit)) {
                "selfId is the number the implementation reports, was '$selfId'"
            }
        }
        for (admin in admins) {
            require(admin.isNotEmpty() && admin.all(Char::isDigit)) {
                "admins holds the numbers of the accounts that operate this instance, was '$admin'"
            }
        }
        for (chat in allowedChats) {
            require(ALLOWED_CHAT.matches(chat)) {
                "allowedChats holds chats as private:<number> or group:<number>, was '$chat'"
            }
        }
        for (name in commands.keys) {
            require(COMMAND_NAME.matches(name)) {
                "commands.$name must name a command: lowercase letters, digits and '_', 32 characters at most"
            }
        }
    }

    public companion object {
        public const val DEFAULT_HOST: String = "127.0.0.1"

        public const val DEFAULT_CONNECT_TIMEOUT: Long = 10_000L

        public const val DEFAULT_FIRST_BYTE_TIMEOUT: Long = 30_000L

        public const val DEFAULT_IDLE_TIMEOUT: Long = 60_000L

        public const val DEFAULT_RECONNECT: Long = 3_000L

        public const val DEFAULT_APPROVAL_MINUTES: Int = 10

        public const val MAX_PORT: Int = 65_535

        /** The shape of a chat key in [OneBotInstanceConfig.allowedChats]. */
        private val ALLOWED_CHAT: Regex = Regex("(private|group):[0-9]+")
    }
}

/** The config path of [key] in the config of [instance], of the instance itself when [key] is null. */
internal fun configPath(instance: ChannelInstanceId, key: String? = null): String =
    listOfNotNull("channels.onebot.instances.${instance.name}", key).joinToString(".")
