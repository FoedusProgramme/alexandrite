package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.channel.common.approval.MAX_APPROVAL_MINUTES
import org.unitedsoftware.alexandrite.channel.common.approval.isApprovalMinutes
import org.unitedsoftware.alexandrite.channel.telegram.reply.StreamingMode
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import java.net.URI
import java.net.URISyntaxException

/** The config of one bot. */
@ChannelInstanceScoped
@ConfigSection
@Serializable
internal class TelegramInstanceConfig(
    val botToken: Secret,
    /** The Telegram user ids of this bot's operators. */
    val adminUserIds: List<Long> = emptyList(),
    /** The chats paired beside those whose codes were approved. */
    val allowedChats: List<Long> = emptyList(),
    val apiBaseUrl: String = "https://api.telegram.org",
    val polling: PollingConfig = PollingConfig(),
    val streaming: StreamingConfig = StreamingConfig(),
    /** Which messages of an allowed group the bot answers. */
    val groupMessages: GroupMessages = GroupMessages.ADDRESSED,
    /** How long an approval request waits for an admin's answer. */
    val approvalMinutes: Int = 10,
    /** How old a message that a stop cut off may be and still be answered after the restart, 0 for none. */
    val replayWindowMinutes: Int = 1440,
    /** Whether the bot publishes its command menu. */
    val commandMenu: Boolean = true,
    /**
     * Who may run each named command, over its default rule; the forms a command keeps for operators stay with
     * admins.
     */
    val commands: Map<String, Who> = emptyMap(),
    /** The Telegram user ids whose own messages this bot's agents trust. */
    val trustedUserIds: List<Long> = emptyList(),
) {
    init {
        val token = botToken.reveal()
        require(token.isNotBlank()) { "botToken is empty" }
        require(TOKEN.matches(token)) {
            "botToken must be a bot token as BotFather gives it: digits, ':' and at least 30 letters, digits, " +
                "'_' or '-'"
        }
        adminUserIds.forEachIndexed { index, id ->
            require(id > 0) { "adminUserIds[$index] must be a Telegram user id, which is positive" }
        }
        trustedUserIds.forEachIndexed { index, id ->
            require(id > 0) { "trustedUserIds[$index] must be a Telegram user id, which is positive" }
        }
        allowedChats.forEachIndexed { index, id ->
            require(id != 0L) { "allowedChats[$index] must be a Telegram chat id, which is not 0" }
        }
        require(isHttpUrl(apiBaseUrl)) {
            "apiBaseUrl must be an http or https URL without user info, query or fragment"
        }
        require(isApprovalMinutes(approvalMinutes.toLong())) {
            "approvalMinutes must lie between 1 and $MAX_APPROVAL_MINUTES"
        }
        require(replayWindowMinutes in 0..MAX_REPLAY_MINUTES) { "replayWindowMinutes must lie between 0 and 10080" }
        for (name in commands.keys) {
            require(COMMAND.matches(name)) {
                "commands.$name must name a command: lowercase letters, digits and '_', 32 characters at most"
            }
        }
    }

    /** [apiBaseUrl] without a trailing `/`. */
    val apiBase: String get() = apiBaseUrl.trimEnd('/')
}

/** Which messages of an allowed group a bot answers. */
@Serializable
internal enum class GroupMessages {
    /** Those that name it: a command for it, a mention of it, a reply to it. */
    @SerialName("addressed")
    ADDRESSED,

    /** Every one, but a command for another bot. */
    @SerialName("all")
    ALL,
}

@Serializable
internal class PollingConfig(
    /** How long one long poll waits for updates, 0 for short polls. */
    val timeoutSeconds: Int = 25,
    /** The updates one poll fetches at most. */
    val limit: Int = 100,
    /** The longest pause between two failed polls. */
    val retryMaxSeconds: Int = 30,
) {
    init {
        require(timeoutSeconds in 0..50) { "polling.timeoutSeconds must lie between 0 and 50" }
        require(limit in 1..100) { "polling.limit must lie between 1 and 100" }
        require(retryMaxSeconds in 1..300) { "polling.retryMaxSeconds must lie between 1 and 300" }
    }
}

@Serializable
internal class StreamingConfig(
    /** Whether replies are previewed while the model writes them. */
    val enabled: Boolean = true,
    /** The shortest pause between two preview edits in a direct chat. */
    val editIntervalMillis: Long = 1000,
    /** The shortest pause between two preview edits in a group. */
    val groupEditIntervalMillis: Long = 3000,
    /** How a chat sees a reply come, unless it chose otherwise with `/streaming`. */
    val mode: StreamingMode = StreamingMode.EDIT,
    /** Whether a reply quotes the message it answers, unless the chat chose otherwise. */
    val quote: Boolean = true,
) {
    init {
        require(editIntervalMillis in 0..MAX_INTERVAL) { "streaming.editIntervalMillis must lie between 0 and 60000" }
        require(groupEditIntervalMillis in 0..MAX_INTERVAL) {
            "streaming.groupEditIntervalMillis must lie between 0 and 60000"
        }
    }
}

/** The config path of [key] in the config of [instance], of the instance itself when [key] is null. */
internal fun configPath(instance: ChannelInstanceId, key: String? = null): String =
    listOfNotNull("channels.telegram.instances.${instance.name}", key).joinToString(".")

private val TOKEN = Regex("[0-9]+:[A-Za-z0-9_-]{30,}")

private val COMMAND = Regex("[a-z0-9_]{1,32}")

private const val MAX_INTERVAL = 60_000L

private const val MAX_REPLAY_MINUTES = 10_080

private fun isHttpUrl(text: String): Boolean = try {
    val uri = URI(text)
    uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null
} catch (e: URISyntaxException) {
    false
}
