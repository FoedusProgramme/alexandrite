package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.channel.onebot.config.OneBotTransport
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * What a [ChannelSetup] needs from `init`'s shared run, so a channel plugin's setup stays decoupled from
 * [InitStep]'s own private state: the parts of it every channel's questions need (the model it already has, the
 * agent they wire to, where to add a config change, a secret or a summary row), not a channel's own business.
 */
internal interface ChannelSetupContext {
    val cli: Cli
    val interactive: Boolean
    val options: InitOptions
    val config: JsonObject
    val agent: String
    val agentConfig: JsonObject?
    val hasModel: Boolean
    fun change(view: ConfigChangeView)
    fun secret(variable: String, value: String)
    fun summarize(key: String, value: String)
}

/**
 * A channel plugin's own setup questions for `init`'s "channels" step: one object per channel built into this
 * binary, added to [CHANNEL_SETUPS] below, with no other change to the step itself.
 */
internal interface ChannelSetup {
    /** Lowercase, as a provider's [ProviderKind.id] is. */
    val id: String

    /** The channel's display name, for the step's chooser when more than one channel is built in. */
    fun title(cli: Cli): String

    /** Whether [cli]'s environment gives this channel enough to run without a terminal. */
    fun hasNonInteractiveInput(cli: Cli): Boolean

    /** Asks this channel's own questions (a token, admins, …) against [context], and wires it to the agent. */
    fun setUp(context: ChannelSetupContext): Int
}

/** Every channel plugin built into this binary, in the order `init`'s "channels" step offers them, by [id]. */
internal val CHANNEL_SETUPS: List<ChannelSetup> = listOf(OneBotChannelSetup, QqBotChannelSetup, TelegramChannelSetup)

/** The instance `init` sets up, of whichever channel. */
internal const val CHANNEL_INSTANCE = "main"

/** Where the OneBot plugin's instances sit in the config file. */
internal const val ONEBOT_INSTANCES = "channels.onebot.instances"

/** Where the QQ bot plugin's instances sit in the config file. */
internal const val QQBOT_INSTANCES = "channels.qqbot.instances"

/** The variable `init` takes a OneBot endpoint from. */
internal const val ONEBOT_ENDPOINT_VARIABLE = "ALEXANDRITE_ONEBOT_ENDPOINT"

/** The variable `init` takes a OneBot access token from, non-interactively. */
internal const val ONEBOT_ACCESS_TOKEN_VARIABLE = "ALEXANDRITE_ONEBOT_ACCESS_TOKEN"

/** The variable `init` takes a OneBot instance's admins from, non-interactively. */
internal const val ONEBOT_ADMIN_VARIABLE = "ALEXANDRITE_ONEBOT_ADMIN"

/** The variable `init` takes a QQ bot's AppID from. */
internal const val QQBOT_APP_ID_VARIABLE = "ALEXANDRITE_QQBOT_APPID"

/** The variable `init` takes a QQ bot's AppSecret from. */
internal const val QQBOT_APP_SECRET_VARIABLE = "ALEXANDRITE_QQBOT_APPSECRET"

/** The variable `init` takes a QQ bot instance's admins from, non-interactively. */
internal const val QQBOT_ADMIN_VARIABLE = "ALEXANDRITE_QQBOT_ADMIN"

/**
 * OneBot's own setup questions: the endpoint of the implementation to connect to, an optional access token, then
 * the admins that operate it. Unlike [TelegramChannelSetup], nothing here is checked against a live implementation
 * or claimed by a message sent back: an admin is simply named, by the id their messages carry.
 */
internal object OneBotChannelSetup : ChannelSetup {
    override val id: String = "onebot"

    override fun title(cli: Cli): String = cli.t("channel.onebot.title")

    override fun hasNonInteractiveInput(cli: Cli): Boolean =
        cli.environment[ONEBOT_ENDPOINT_VARIABLE]?.isNotEmpty() == true

    override fun setUp(context: ChannelSetupContext): Int {
        val cli = context.cli
        val ui = cli.ui
        val instance = CHANNEL_INSTANCE
        val path = "$ONEBOT_INSTANCES.$instance"
        val existing = context.config.at(path) as? JsonObject
        val endpoint: String
        val transport: OneBotTransport
        if (existing != null) {
            val found = existing["endpoint"].text?.let { it to oneBotTransport(it) }
            endpoint = found?.first ?: return cli.fail(cli.t("init.onebot.receiver_only"))
            transport = found.second ?: return cli.fail(cli.t("init.onebot.receiver_only"))
            ui.good(cli.t("init.onebot.found", "endpoint" to endpoint))
        } else {
            val given = cli.environment[ONEBOT_ENDPOINT_VARIABLE]
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { oneBotTransport(it) != null }
            if (!context.interactive && given == null) {
                ui.warn(cli.t("init.onebot.no_endpoint", "variable" to ONEBOT_ENDPOINT_VARIABLE))
                return ExitCode.OK
            }
            if (context.interactive && given == null) ui.say(cli.t("init.onebot.about"))
            val typed = given ?: ui.ask(
                cli.t("init.onebot.endpoint_prompt"),
                problem = { text -> cli.t("init.onebot.bad_endpoint").takeIf { oneBotTransport(text) == null } },
            ) ?: return cli.fail(cli.t("init.onebot.no_endpoint_given"))
            transport = oneBotTransport(typed) ?: return cli.fail(cli.t("init.onebot.bad_endpoint"))
            endpoint = typed
        }
        val accessToken = when {
            existing?.get("accessToken") != null -> null
            context.interactive -> ui.secret(cli.t("init.onebot.token_prompt"))?.trim()?.ifEmpty { null }
            else -> cli.environment[ONEBOT_ACCESS_TOKEN_VARIABLE]?.takeIf { it.isNotEmpty() }
        }
        context.change(ConfigChangeView("$path.transport", JsonPrimitive(transport.name.lowercase())))
        context.change(ConfigChangeView("$path.endpoint", JsonPrimitive(endpoint)))
        accessToken?.let {
            val variable = oneBotTokenVariable(instance)
            context.secret(variable, it)
            context.change(ConfigChangeView("$path.accessToken", JsonPrimitive(EnvReferences.of(variable))))
        }
        context.summarize("init.summary.bot", endpoint)
        val agentServes = context.agentConfig?.objectAt("channels")?.get("onebot:$instance") != null
        val servesChannel = when {
            agentServes -> true

            context.hasModel -> {
                context.change(
                    ConfigChangeView("$AGENTS.${context.agent}.channels.onebot:$instance", JsonObject(emptyMap())),
                )
                true
            }

            else -> false
        }
        if (servesChannel) {
            ui.good(cli.t("init.onebot.chosen", "endpoint" to endpoint, "agent" to context.agent))
        } else {
            ui.warn(cli.t("init.onebot.no_model", "agent" to context.agent))
        }
        val admins = existing?.get("admins")?.let { (it as? JsonArray)?.mapNotNull { admin -> admin.text } }
            ?: oneBotAdmins(context)
        if (admins.isEmpty()) {
            ui.warn(cli.t("init.onebot.no_admin"))
            return ExitCode.OK
        }
        context.change(ConfigChangeView("$path.admins", JsonArray(admins.map(::JsonPrimitive))))
        if (existing?.get("allowedChats") == null) {
            context.change(
                ConfigChangeView("$path.allowedChats", JsonArray(admins.map { JsonPrimitive("private:$it") })),
            )
        }
        context.summarize("init.summary.admin", admins.joinToString())
        if (servesChannel) {
            context.change(
                ConfigChangeView(
                    "$AGENTS.${context.agent}.channels.onebot:$instance.home",
                    JsonPrimitive("private:${admins.first()}"),
                ),
            )
        }
        ui.good(cli.t("init.admin.chosen", "admins" to admins.joinToString(), "agent" to context.agent))
        return ExitCode.OK
    }

    /** The admins typed at the prompt, or taken from [ONEBOT_ADMIN_VARIABLE] non-interactively; empty for none. */
    private fun oneBotAdmins(context: ChannelSetupContext): List<String> {
        val cli = context.cli
        val typed = if (context.interactive) {
            cli.ui.ask(
                cli.t("init.onebot.admin_prompt"),
                default = "",
                problem = { text ->
                    cli.t("init.onebot.bad_admin").takeIf { text.isNotEmpty() && !isOneBotAdmin(text) }
                },
            )
        } else {
            cli.environment[ONEBOT_ADMIN_VARIABLE]
        }
        return typed.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }

    private fun isOneBotAdmin(text: String): Boolean =
        text.split(',').map(String::trim).filter(String::isNotEmpty).all { it.all(Char::isDigit) }
}

/** The transport an OneBot [endpoint] names, by its scheme; null when it names none this wizard supports. */
private fun oneBotTransport(endpoint: String): OneBotTransport? {
    val scheme = try {
        URI(endpoint).scheme
    } catch (e: URISyntaxException) {
        null
    } ?: return null
    return when (scheme.lowercase()) {
        "ws", "wss" -> OneBotTransport.WS
        "http", "https" -> OneBotTransport.HTTP
        else -> null
    }
}

private fun oneBotTokenVariable(instance: String): String =
    "ALEXANDRITE_ONEBOT_${instance.uppercase().replace('-', '_')}_TOKEN"

/**
 * The QQ open platform's own setup questions: a bot's AppID and AppSecret, then the admins that operate it. Like
 * [OneBotChannelSetup] and unlike [TelegramChannelSetup], nothing here is checked against the platform or claimed
 * by a message sent back.
 */
internal object QqBotChannelSetup : ChannelSetup {
    override val id: String = "qqbot"

    override fun title(cli: Cli): String = cli.t("channel.qqbot.title")

    override fun hasNonInteractiveInput(cli: Cli): Boolean =
        cli.environment[QQBOT_APP_ID_VARIABLE]?.isNotEmpty() == true &&
            cli.environment[QQBOT_APP_SECRET_VARIABLE]?.isNotEmpty() == true

    override fun setUp(context: ChannelSetupContext): Int {
        val cli = context.cli
        val ui = cli.ui
        val instance = CHANNEL_INSTANCE
        val path = "$QQBOT_INSTANCES.$instance"
        val existing = context.config.at(path) as? JsonObject
        val appId: String
        val appSecret: String?
        if (existing != null) {
            appId = existing["appId"].text ?: return cli.fail(cli.t("init.qqbot.corrupt"))
            appSecret = null
            ui.good(cli.t("init.qqbot.found", "appId" to appId))
        } else {
            val givenId = cli.environment[QQBOT_APP_ID_VARIABLE]?.takeIf { it.isNotEmpty() }
            val givenSecret = cli.environment[QQBOT_APP_SECRET_VARIABLE]?.takeIf { it.isNotEmpty() }
            if (!context.interactive && (givenId == null || givenSecret == null)) {
                ui.warn(cli.t("init.qqbot.no_app_id", "variable" to QQBOT_APP_ID_VARIABLE))
                return ExitCode.OK
            }
            if (context.interactive && givenId == null) ui.say(cli.t("init.qqbot.about"))
            appId = givenId ?: ui.ask(cli.t("init.qqbot.app_id_prompt")) ?: return cli.fail(
                cli.t("init.qqbot.no_app_id_given"),
            )
            appSecret = givenSecret ?: ui.secret(cli.t("init.qqbot.app_secret_prompt"))?.trim()?.ifEmpty { null }
                ?: return cli.fail(cli.t("init.qqbot.no_app_secret_given"))
        }
        context.change(ConfigChangeView("$path.appId", JsonPrimitive(appId)))
        appSecret?.let {
            val variable = qqBotSecretVariable(instance)
            context.secret(variable, it)
            context.change(ConfigChangeView("$path.appSecret", JsonPrimitive(EnvReferences.of(variable))))
        }
        context.summarize("init.summary.bot", appId)
        val agentServes = context.agentConfig?.objectAt("channels")?.get("qqbot:$instance") != null
        val servesChannel = when {
            agentServes -> true

            context.hasModel -> {
                context.change(
                    ConfigChangeView("$AGENTS.${context.agent}.channels.qqbot:$instance", JsonObject(emptyMap())),
                )
                true
            }

            else -> false
        }
        if (servesChannel) {
            ui.good(cli.t("init.qqbot.chosen", "appId" to appId, "agent" to context.agent))
        } else {
            ui.warn(cli.t("init.qqbot.no_model", "agent" to context.agent))
        }
        val admins = existing?.get("admins")?.let { (it as? JsonArray)?.mapNotNull { admin -> admin.text } }
            ?: qqBotAdmins(context)
        if (admins.isEmpty()) {
            ui.warn(cli.t("init.qqbot.no_admin"))
            return ExitCode.OK
        }
        context.change(ConfigChangeView("$path.admins", JsonArray(admins.map(::JsonPrimitive))))
        context.summarize("init.summary.admin", admins.joinToString())
        if (servesChannel) {
            context.change(
                ConfigChangeView(
                    "$AGENTS.${context.agent}.channels.qqbot:$instance.home",
                    JsonPrimitive("private:${admins.first()}"),
                ),
            )
        }
        ui.good(cli.t("init.admin.chosen", "admins" to admins.joinToString(), "agent" to context.agent))
        return ExitCode.OK
    }

    /** The admins typed at the prompt, or taken from [QQBOT_ADMIN_VARIABLE] non-interactively; empty for none. */
    private fun qqBotAdmins(context: ChannelSetupContext): List<String> {
        val cli = context.cli
        val typed = if (context.interactive) {
            cli.ui.ask(cli.t("init.qqbot.admin_prompt"), default = "")
        } else {
            cli.environment[QQBOT_ADMIN_VARIABLE]
        }
        return typed.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}

private fun qqBotSecretVariable(instance: String): String =
    "ALEXANDRITE_QQBOT_${instance.uppercase().replace('-', '_')}_APPSECRET"

/** Telegram's own setup questions: a bot token (from the environment or typed, masked), then its admin, claimed
 * by a one-time code sent from the admin's own Telegram account. */
internal object TelegramChannelSetup : ChannelSetup {
    override val id: String = "telegram"

    override fun title(cli: Cli): String = cli.t("channel.telegram.title")

    override fun hasNonInteractiveInput(cli: Cli): Boolean =
        cli.environment[TELEGRAM_TOKEN_VARIABLE]?.isNotEmpty() == true

    override fun setUp(context: ChannelSetupContext): Int {
        val cli = context.cli
        val ui = cli.ui
        val instance = CHANNEL_INSTANCE
        val telegramApi = context.options.apiBaseUrl
            ?: (context.config.at("$TELEGRAM_INSTANCES.$instance.apiBaseUrl").text ?: TELEGRAM_API)
        val given = cli.environment[TELEGRAM_TOKEN_VARIABLE]?.takeIf { it.isNotEmpty() }
        val existingInstance = context.config.at("$TELEGRAM_INSTANCES.$instance") as? JsonObject
        var checked = existingInstance?.let { existingTelegramBot(cli, telegramApi, it) }
        if (checked == null) {
            if (!context.interactive && given == null) {
                ui.warn(cli.t("init.telegram.no_token", "variable" to TELEGRAM_TOKEN_VARIABLE))
                return ExitCode.OK
            }
            if (context.interactive && given == null) ui.say(cli.t("init.telegram.about"))
            for (attempt in 1..INIT_ATTEMPTS) {
                checked = askBot(cli, telegramApi, given.takeIf { attempt == 1 })
                if (checked != null || !context.interactive) break
            }
        }
        val (found, token) = checked ?: return ExitCode.FAILURE
        configuredBot(cli, context.config, token)?.takeIf { it != instance }?.let {
            return cli.fail(cli.t("channel.same_bot", "instance" to it))
        }
        context.summarize("init.summary.bot", described(cli, found))
        val variable = telegramVariable(instance)
        context.secret(variable, token)
        context.change(
            ConfigChangeView("$TELEGRAM_INSTANCES.$instance.botToken", JsonPrimitive(EnvReferences.of(variable))),
        )
        context.options.apiBaseUrl?.let {
            context.change(ConfigChangeView("$TELEGRAM_INSTANCES.$instance.apiBaseUrl", JsonPrimitive(it)))
        }
        val agentServes = context.agentConfig?.objectAt("channels")?.get("telegram:$instance") != null
        val servesChannel: Boolean
        if (agentServes) {
            servesChannel = true
            ui.good(cli.t("init.telegram.chosen", "bot" to described(cli, found), "agent" to context.agent))
        } else if (context.hasModel) {
            servesChannel = true
            context.change(
                ConfigChangeView("$AGENTS.${context.agent}.channels.telegram:$instance", JsonObject(emptyMap())),
            )
            ui.good(cli.t("init.telegram.chosen", "bot" to described(cli, found), "agent" to context.agent))
        } else {
            servesChannel = false
            ui.warn(cli.t("init.telegram.no_model", "agent" to context.agent))
        }
        return claimAdminFor(context, instance, telegramApi, found, token, servesChannel)
    }

    /** Claims the admin of the bot [found]/[token] just set up (or already configured), the same way the old,
     * separate "admin" step did. [servesChannel] is whether the agent already served `telegram:[instance]`, or
     * this same call just wired it up moments ago (not yet written, so [ChannelSetupContext.agentConfig] alone
     * would not show it). */
    private fun claimAdminFor(
        context: ChannelSetupContext,
        instance: String,
        telegramApi: String,
        found: BotCheck.Bot,
        token: String,
        servesChannel: Boolean,
    ): Int {
        val cli = context.cli
        val ui = cli.ui
        val admins = context.options.admins.ifEmpty {
            if (cli.status()?.instances?.any { it.id == "telegram:$instance" } == true) {
                return cli.fail(cli.t("claim.running"))
            }
            var claimant: Claimant? = null
            while (claimant == null) {
                claimant = claimAdmin(cli, telegramApi, token, found)
                if (claimant == null &&
                    !(context.interactive && cli.confirm(cli.t("init.admin.again"), default = true))
                ) {
                    return if (context.interactive) ExitCode.OK else ExitCode.FAILURE
                }
            }
            context.summarize("init.summary.admin", claimant.described)
            listOf(claimant.id)
        }
        val existing = context.config.at("$TELEGRAM_INSTANCES.$instance") as? JsonObject
        val root = "$TELEGRAM_INSTANCES.$instance"
        context.change(
            ConfigChangeView("$root.adminUserIds", telegramAdminIds(existing?.get("adminUserIds"), admins)),
        )
        if (servesChannel) {
            context.change(
                ConfigChangeView(
                    "$AGENTS.${context.agent}.channels.telegram:$instance.home",
                    JsonPrimitive("${admins.first()}"),
                ),
            )
        }
        ui.good(cli.t("init.admin.chosen", "admins" to admins.joinToString(), "agent" to context.agent))
        return ExitCode.OK
    }
}

/** The bot of the configured [instance] and its token, null after printing why there is none (or there simply is
 * none configured yet, which is not an error — the caller then asks for a new token). */
internal fun existingTelegramBot(cli: Cli, telegramApi: String, instance: JsonObject): Pair<BotCheck.Bot, String>? {
    val written = instance["botToken"].text ?: return null
    val variable = EnvReferences.whole(written)
    val token = if (variable == null) {
        written
    } else {
        try {
            withSecrets(cli.environment, cli.secrets)[variable]
        } catch (e: IOException) {
            null
        }
    } ?: return null
    val base = instance["apiBaseUrl"].text ?: telegramApi
    return when (val checked = cli.ui.busy({ cli.t("bot.checking") }) { telegramBot(cli, base, token) }) {
        is BotCheck.Bot -> checked to token

        is BotCheck.Refused -> {
            cli.fail(cli.texts(checked.reason))
            null
        }
    }
}

private fun telegramAdminIds(existing: JsonElement?, added: List<Long>): JsonArray {
    val kept = (existing as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
    return JsonArray((kept + added).distinct().map(::JsonPrimitive))
}
