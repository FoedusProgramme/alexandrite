package org.unitedsoftware.alexandrite.channel.qqbot.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.channel.common.command.COMMAND_NAME
import org.unitedsoftware.alexandrite.channel.common.command.CommandWho
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/**
 * One bot of the QQ open platform, below `instances` of the plugin's config root.
 *
 * The platform gives every bot an AppID and an AppSecret, which the channel exchanges for an access token it refreshes
 * as the platform expires it. What the bot receives is chosen by [intents], a bit set of event groups the platform
 * documented: the group and single chat messages of the QQ chats are `GROUP_AND_C2C_EVENT` (1 << 25).
 */
@ConfigSection
@ChannelInstanceScoped
@Serializable
public class QqBotInstanceConfig(
    /** The AppID of the bot on the open platform. */
    @SerialName("appId") public val appId: String,
    /** The AppSecret of the bot, which buys its access tokens. */
    @SerialName("appSecret") public val appSecret: Secret,
    /** The base of the open platform's API, which a test points at a server of its own. */
    @SerialName("apiBase") public val apiBase: String = DEFAULT_API_BASE,
    /** The WebSocket address of the gateway, null to ask the platform for one. */
    @SerialName("gatewayUrl") public val gatewayUrl: String? = null,
    /** The event groups this connection subscribes to, as the platform numbers them. */
    public val intents: Long = DEFAULT_INTENTS,
    /** Whether a chat is told what a turn is doing while it works. */
    public val progress: Boolean = true,
    /** Whether a single chat is answered with the platform's streaming message, false to send one message instead. */
    public val streaming: Boolean = true,
    /**
     * The accounts that may operate this instance, by the open id a single chat names them by, which is the chat an
     * approval of theirs is shown in. A group's administrators are its platform `member_role`, not this list.
     */
    public val admins: Set<String> = emptySet(),
    /** Whether the bot publishes its slash commands as the single-chat menu and command panels when it opens. */
    @SerialName("syncCommands") public val syncCommands: Boolean = true,
    /**
     * The language the published menu and panels use, null for the host's language.
     *
     * The platform shows one menu and one panel to everyone, so this is one language for the whole instance rather
     * than the language of a chat.
     */
    @SerialName("syncLanguage") public val syncLanguage: String? = null,
    /** How long an approval request waits for an administrator, in minutes. */
    @SerialName("approvalMinutes") public val approvalMinutes: Long = DEFAULT_APPROVAL_MINUTES,
    /** How long reaching the platform may take. */
    @SerialName("connectTimeoutMillis") public val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT,
    /** How long the platform may take to answer a call before the call fails. */
    @SerialName("firstByteTimeoutMillis") public val firstByteTimeoutMillis: Long = DEFAULT_FIRST_BYTE_TIMEOUT,
    /** How long a call may stay silent before it is cut off. */
    @SerialName("idleTimeoutMillis") public val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT,
    /** How long to wait before connecting to the gateway again, in milliseconds. */
    @SerialName("reconnectIntervalMillis") public val reconnectIntervalMillis: Long = DEFAULT_RECONNECT,
    /**
     * Who may run each named command, over its default rule; the forms a command keeps for operators stay with
     * admins.
     */
    public val commands: Map<String, CommandWho> = emptyMap(),
) {
    init {
        require(appId.isNotBlank()) { "appId may not be blank." }
        require(appId.all { it.isLetterOrDigit() }) { "appId is the number the open platform gave, was '$appId'." }
        require(apiBase.startsWith("http://") || apiBase.startsWith("https://")) {
            "apiBase is an HTTP or HTTPS URL, was '$apiBase'."
        }
        require(gatewayUrl == null || gatewayUrl.startsWith("ws://") || gatewayUrl.startsWith("wss://")) {
            "gatewayUrl is a WS or WSS URL, was '$gatewayUrl'."
        }
        require(intents > 0) { "intents names at least one event group, was $intents." }
        require(connectTimeoutMillis > 0) { "A connect timeout is positive, was $connectTimeoutMillis." }
        require(firstByteTimeoutMillis > 0) { "A first byte timeout is positive, was $firstByteTimeoutMillis." }
        require(idleTimeoutMillis > 0) { "An idle timeout is positive, was $idleTimeoutMillis." }
        require(reconnectIntervalMillis > 0) { "A reconnect interval is positive, was $reconnectIntervalMillis." }
        require(approvalMinutes > 0) { "An approval wait is at least a minute, was $approvalMinutes." }
        syncLanguage?.let { language ->
            require(runCatching { LanguageTag.of(language) }.isSuccess) {
                "syncLanguage is a BCP 47 language tag, was '$language'."
            }
        }
        for (name in commands.keys) {
            require(COMMAND_NAME.matches(name)) {
                "commands.$name must name a command: lowercase letters, digits and '_', 32 characters at most"
            }
        }
    }

    public companion object {
        /** Where the open platform serves its API. */
        public const val DEFAULT_API_BASE: String = "https://api.bot.qq.com"

        /** The event groups of the single chat and group messages of the QQ chats. */
        public const val DEFAULT_INTENTS: Long = 1L shl 25

        public const val DEFAULT_CONNECT_TIMEOUT: Long = 10_000L

        public const val DEFAULT_FIRST_BYTE_TIMEOUT: Long = 30_000L

        public const val DEFAULT_IDLE_TIMEOUT: Long = 60_000L

        public const val DEFAULT_RECONNECT: Long = 3_000L

        public const val DEFAULT_APPROVAL_MINUTES: Long = 10L
    }
}

/** The config path of [key] in the config of [instance], of the instance itself when [key] is null. */
internal fun configPath(instance: ChannelInstanceId, key: String? = null): String =
    listOfNotNull("channels.qqbot.instances.${instance.name}", key).joinToString(".")
