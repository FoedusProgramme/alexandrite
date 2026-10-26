package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import java.io.IOException

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
 * binary. Today only [TelegramChannelSetup]; a second channel (OneBot/QQ, once its branch is merged) adds its own
 * object to [CHANNEL_SETUPS] below, with no other change to the step itself.
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

/** Every channel plugin built into this binary, in the order `init`'s "channels" step offers them. */
internal val CHANNEL_SETUPS: List<ChannelSetup> = listOf(TelegramChannelSetup)

/** The Telegram instance `init` sets up. */
internal const val CHANNEL_INSTANCE = "main"

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
