package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.unitedsoftware.alexandrite.account.pleochroic.PLEOCHROIC_PORTAL_URL
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.tui.Choice
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import java.io.IOException
import java.nio.file.Files

/** The steps of `init`, each named by [id]. */
internal enum class InitStep(val id: String) {
    MODEL("model"),
    TELEGRAM("telegram"),
    ADMIN("admin"),
    LANGUAGE("language"),
    SERVICE("service"),
}

internal fun requireStep(id: String): InitStep =
    InitStep.entries.firstOrNull { it.id == id } ?: throw UsageException("usage.init_step", "step" to id)

/** What `init` was asked for on its command line. */
internal class InitOptions(
    /** Whether it may ask the person at the terminal. */
    val interactive: Boolean,
    val steps: Set<InitStep>,
    val provider: ProviderKind? = null,
    val endpoint: String? = null,
    val baseUrl: String? = null,
    /** A self-hosted standalone-service's address and token, for the Pleochroic provider only. */
    val serviceUrl: String? = null,
    val serviceToken: String? = null,
    /** The portal a Pleochroic device login signs in through; only a test overrides it. */
    val portalBaseUrl: String = PLEOCHROIC_PORTAL_URL,
    /** The models of the endpoint, the first the agent's default. */
    val models: List<String> = emptyList(),
    val agent: String? = null,
    val admins: List<Long> = emptyList(),
    val language: String? = null,
    val zone: String? = null,
    /** The Bot API's address, Telegram's own when null. */
    val apiBaseUrl: String? = null,
    /** Whether an existing config file may be changed without asking. */
    val force: Boolean = false,
)

/** The variables `init` takes the provider's key and the bot token from. */
internal const val API_KEY_VARIABLE = "ALEXANDRITE_API_KEY"
internal const val TELEGRAM_TOKEN_VARIABLE = "ALEXANDRITE_TELEGRAM_TOKEN"

/** The Telegram instance `init` sets up. */
private const val INSTANCE = "main"

/** How often a step asks again for an answer it cannot use. */
private const val ATTEMPTS = 3

/** Sets Alexandrite up step by step, and returns the exit code. */
internal fun init(cli: Cli, options: InitOptions): Int = cli.onTerminal { Setup(cli, options).run() }

/** One run of `init`: what its steps found, the changes they make and the secrets they save. */
private class Setup(private val cli: Cli, private val options: InitOptions) {
    private val interactive = options.interactive && cli.machine.interactive
    private val ui: Ui get() = cli.ui
    private val changes = mutableListOf<ConfigChangeView>()
    private val secrets = linkedMapOf<String, String>()
    private val skipped = mutableListOf<InitStep>()
    private var config = JsonObject(emptyMap())
    private lateinit var agent: String

    /** The rows of the summary, by the key of their label. */
    private val summary = linkedMapOf<String, String>()

    private val telegramApi: String
        get() = options.apiBaseUrl ?: (config.at("$TELEGRAM_INSTANCES.$INSTANCE.apiBaseUrl").text ?: TELEGRAM_API)

    /** The bot this run set up, and its token. */
    private var bot: Pair<BotCheck.Bot, String>? = null

    fun run(): Int {
        if (ui.rich) ui.intro(cli.t("init.title"))
        if (options.interactive && !cli.machine.interactive) ui.say(cli.t("init.no_terminal"))
        ui.say(cli.t("init.welcome", "file" to cli.locations.configFile))
        val exists = Files.exists(cli.locations.configFile)
        var steps = options.steps
        if (exists) {
            config = cli.rawConfig() ?: return ExitCode.CONFIG
            if (steps.any { it != InitStep.SERVICE } && !options.force) {
                if (!interactive) return cli.fail(cli.t("init.existing_force", "file" to cli.locations.configFile))
                if (!cli.confirm(cli.t("init.existing", "file" to cli.locations.configFile))) {
                    ui.say(cli.t("init.existing_kept"))
                    steps = steps.filterTo(mutableSetOf()) { it == InitStep.SERVICE }
                }
            }
        }
        agent = agentId() ?: return ExitCode.USAGE
        for (step in listOf(InitStep.MODEL, InitStep.TELEGRAM, InitStep.ADMIN, InitStep.LANGUAGE)) {
            if (step !in steps) continue
            val code = when (step) {
                InitStep.MODEL -> model()
                InitStep.TELEGRAM -> telegram()
                InitStep.ADMIN -> admin()
                else -> language()
            }
            if (code != ExitCode.OK) return code
        }
        if (changes.isNotEmpty()) {
            val code = write(exists)
            if (code != ExitCode.OK) return code
        }
        if (InitStep.SERVICE in steps) {
            val code = service()
            if (code != ExitCode.OK) return code
        }
        for (step in skipped) {
            ui.warn(cli.t("init.redo", "step" to Text("init.name.${step.id}"), "how" to Text("init.redo.${step.id}")))
        }
        summary["init.summary.next"] = if ("init.summary.service" in summary) {
            "alexandrite status · alexandrite logs -f · alexandrite doctor"
        } else {
            "alexandrite run · alexandrite doctor"
        }
        ui.summary(cli.t("init.summary.title"), summary.map { (key, value) -> cli.t(key) to value })
        ui.outro(cli.t("init.done"))
        return ExitCode.OK
    }

    /** The agent the steps set up: the one named, the only one, or `main`; null after printing that it is unclear. */
    private fun agentId(): String? {
        val agents = agents(config).map { it.id }
        options.agent?.let { return it }
        if (agents.size <= 1) return agents.singleOrNull() ?: "main"
        if ("main" in agents) return "main"
        cli.fail(cli.t("init.name_agent", "agents" to agents.joinToString()), ExitCode.USAGE)
        return null
    }

    private val agentConfig: JsonObject? get() = config.at("$AGENTS.$agent") as? JsonObject

    /** Whether the step [step] goes ahead: always without a terminal, else when the person agrees. */
    private fun begins(step: InitStep): Boolean {
        ui.heading(cli.t("init.step.${step.id}"))
        if (!interactive || cli.confirm(cli.t("init.ask.${step.id}"), default = true)) return true
        skip(step)
        return false
    }

    private fun skip(step: InitStep) {
        skipped += step
    }

    /** The text of [plain], or of [rich] when the prompts draw on the terminal. */
    private fun prompt(plain: String, rich: String, vararg args: Pair<String, Any?>): String =
        cli.t(if (ui.rich) rich else plain, *args)

    // Model.

    private fun model(): Int {
        if (!interactive && options.provider == null) {
            ui.warn(cli.t("init.model.no_provider"))
            skip(InitStep.MODEL)
            return ExitCode.OK
        }
        if (!begins(InitStep.MODEL)) return ExitCode.OK
        val kind = options.provider ?: chooseProvider() ?: return cli.fail(cli.t("init.no_answer"))
        if (kind.id == "pleochroic") return pleochroicModel(kind)
        val endpoint = options.endpoint ?: kind.endpoint
        val base = baseUrl(kind) ?: return ExitCode.FAILURE
        val variable = if (endpoint == kind.endpoint) kind.keyVariable else keyVariable(endpoint)
        var key: String? = null
        var listed: List<String>? = null
        for (attempt in 1..ATTEMPTS) {
            key = key(kind, variable) ?: return ExitCode.FAILURE
            val given = key.ifEmpty { null }
            val listing =
                ui.busy({ cli.t("init.model.checking", "base" to base) }) { listModels(cli, kind, base, given) }
            if (listing is Listing.Models) {
                listed = listing.ids
                val text = if (given == null) "init.model.listed_keyless" else "init.model.listed"
                ui.good(cli.t(text, "base" to base, "count" to listing.ids.size))
                break
            }
            if (listing is Listing.Unchecked) {
                cli.fail(cli.texts(listing.reason))
                if (interactive && cli.confirm(cli.t("init.model.keep_key"), default = true)) break
            } else {
                cli.fail(cli.texts((listing as Listing.Refused).reason))
            }
            if (!interactive || attempt == ATTEMPTS) return ExitCode.FAILURE
        }
        val models = chooseModels(listed) ?: return ExitCode.FAILURE
        val root = "providers.${kind.id}.endpoints.$endpoint"
        if (key.isNullOrEmpty()) {
            if (config.at("$root.apiKey") != null) changes += ConfigChangeView("$root.apiKey", remove = true)
        } else {
            secrets[variable] = key
            changes += ConfigChangeView("$root.apiKey", JsonPrimitive(EnvReferences.of(variable)))
        }
        if (base != kind.defaultUrl) {
            changes += ConfigChangeView("$root.baseUrl", JsonPrimitive(base))
        } else if (config.at("$root.baseUrl") != null) {
            changes += ConfigChangeView("$root.baseUrl", remove = true)
        }
        summary["init.summary.provider"] = "${title(kind)} · $base"
        finishModelStep(endpoint, models.map { "$endpoint/$it" })
        return ExitCode.OK
    }

    /**
     * The Pleochroic provider's model step: signs in ([pleochroicSignIn], a device login or, with
     * `--service-url`/`--service-token`, a self-hosted standalone-service) in place of the generic step's key and
     * base URL prompts, then continues exactly as the other providers do (the model pick against `GET /v1/models`,
     * [finishModelStep]'s agent/Telegram wiring), and turns on `tools.web`'s backend where the account is entitled
     * for it ([webBackendChanges], the same as `alexandrite login pleochroic`).
     */
    private fun pleochroicModel(kind: ProviderKind): Int {
        val signedIn = pleochroicSignIn(cli, options.serviceUrl, options.serviceToken, null, options.portalBaseUrl)
            ?: return ExitCode.FAILURE
        val base = signedIn.apiBase
        val key = signedIn.key
        val listing = ui.busy({ cli.t("init.model.checking", "base" to base) }) { listModels(cli, kind, base, key) }
        val listed = when (listing) {
            is Listing.Models -> {
                ui.good(cli.t("init.model.listed", "base" to base, "count" to listing.ids.size))
                listing.ids
            }

            is Listing.Refused -> return cli.fail(cli.texts(listing.reason))

            is Listing.Unchecked -> null.also { cli.fail(cli.texts(listing.reason)) }
        }
        val models = chooseModels(listed) ?: return ExitCode.FAILURE
        val endpoint = PLEOCHROIC_ENDPOINT
        val root = "providers.${kind.id}.endpoints.$endpoint"
        secrets[kind.keyVariable] = key
        changes += ConfigChangeView("$root.apiKey", JsonPrimitive(EnvReferences.of(kind.keyVariable)))
        changes += ConfigChangeView("$root.baseUrl", JsonPrimitive(base))
        changes += webBackendChanges(config, signedIn.status, base, key)
        summary["init.summary.provider"] = pleochroicSummary(kind, signedIn)
        finishModelStep(endpoint, models.map { "$endpoint/$it" })
        return ExitCode.OK
    }

    private fun pleochroicSummary(kind: ProviderKind, signedIn: PleochroicSignIn): String {
        val account = signedIn.account.label ?: cli.t("pleochroic.unnamed")
        val plan = signedIn.account.plan?.label
        return "${title(kind)} · $account" + (plan?.let { " ($it)" } ?: "")
    }

    /** What every provider's model step finishes with, once it has [endpoint] and [named] (its chosen models,
     * `<endpoint>/<model>`): the agent's default description and models, the summary line, and, when a Telegram
     * instance is already set up but serves no agent yet, wiring this agent to it. */
    private fun finishModelStep(endpoint: String, named: List<String>) {
        if (agentConfig == null) {
            changes += ConfigChangeView("$AGENTS.$agent.description", JsonPrimitive(cli.t("init.agent")))
        }
        changes += defaultModels(agent, agentConfig, named)
        ui.good(cli.t("init.model.chosen", "agent" to agent, "models" to named.joinToString()))
        summary["init.summary.models"] = cli.t("init.summary.models_value", "models" to named.joinToString())
        val instance = config.at("$TELEGRAM_INSTANCES.$INSTANCE") as? JsonObject
        val served = agents(config).any { it.channels["telegram:$INSTANCE"] != null }
        if (instance != null && !served) {
            val home = (instance["adminUserIds"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
            changes += ConfigChangeView(
                "$AGENTS.$agent.channels.telegram:$INSTANCE",
                JsonObject(listOfNotNull(home?.let { "home" to JsonPrimitive(it) }).toMap()),
            )
        }
    }

    /** Whether the agent has a model, or gets one in this run. */
    private val hasModel: Boolean
        get() = agentConfig?.let { AgentEntry(agent, it).models }.orEmpty().isNotEmpty() ||
            changes.any { it.path == "$AGENTS.$agent.model" || it.path == "$AGENTS.$agent.models" }

    private fun title(kind: ProviderKind): String = cli.t("provider.${kind.id}")

    private fun chooseProvider(): ProviderKind? = ui.choose(
        prompt("init.model.provider", "init.model.provider_title"),
        PROVIDER_KINDS.map { Choice(title(it), it, key = it.id) },
    ) { cli.t("init.model.no_such_provider", "answer" to it) }

    /** The endpoint's address, asked for when the provider has none or lets it be moved; null after printing why. */
    private fun baseUrl(kind: ProviderKind): String? {
        options.baseUrl?.let { return it.trimEnd('/') }
        if (!interactive || kind.id == "deepseek" || kind.id == "openrouter" || kind.id == "openai-responses") {
            return kind.defaultUrl ?: run {
                cli.fail(cli.t("init.model.needs_base_url", "provider" to kind.id))
                null
            }
        }
        val answer = ui.ask(cli.t("init.model.base_url"), kind.defaultUrl) {
            val web = it.startsWith("http://") || it.startsWith("https://")
            if (web) null else cli.t("init.model.no_url", "answer" to it)
        } ?: return null.also { cli.fail(cli.t("init.no_answer")) }
        return answer.trimEnd('/')
    }

    /** The key of the endpoint, empty for none; null after printing that a needed one is missing. */
    private fun key(kind: ProviderKind, variable: String): String? {
        val environment = cli.environment[API_KEY_VARIABLE] ?: cli.environment[variable]
        if (!interactive) {
            if (environment.isNullOrEmpty() && kind.keyRequired) {
                cli.fail(
                    cli.t(
                        "init.model.needs_key",
                        "provider" to kind.id,
                        "variables" to "$API_KEY_VARIABLE, $variable",
                    ),
                )
                return null
            }
            return environment.orEmpty()
        }
        val prompt = if (kind.keyRequired) {
            prompt("init.model.key", "init.model.key_title", "provider" to title(kind))
        } else {
            prompt("init.model.key_optional", "init.model.key_optional_title", "provider" to title(kind))
        }
        repeat(ATTEMPTS) {
            val typed = ui.secret(prompt)?.trim() ?: return null.also { cli.fail(cli.t("init.no_answer")) }
            val key = typed.ifEmpty { environment.orEmpty() }
            if (key.isNotEmpty() || !kind.keyRequired) return key
            ui.warn(cli.t("init.model.key_needed"))
        }
        cli.fail(cli.t("init.no_answer"))
        return null
    }

    /**
     * The models the person picks from [listed] (null when the endpoint gave none), or that the options name; null
     * after printing why there are none.
     */
    private fun chooseModels(listed: List<String>?): List<String>? {
        if (options.models.isNotEmpty()) return options.models.distinct().also { warnUnlisted(it, listed) }
        if (!interactive) {
            cli.fail(cli.t("init.model.name_model", "listed" to sample(listed.orEmpty())))
            return null
        }
        val own = agentConfig?.let { AgentEntry(agent, it).models }.orEmpty()
        if (own.isNotEmpty()) ui.say(cli.t("init.model.own", "agent" to agent, "models" to own.joinToString()))
        val picked = if (listed.isNullOrEmpty()) {
            val prompt = if (listed == null) "init.model.type_unlisted" else "init.model.type"
            ui.ask(cli.t(prompt)) { if (it.isBlank()) cli.t("init.no_answer") else null }
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()
        } else {
            ui.pick(prompt("init.model.pick", "init.model.pick_title"), listed, cli.t("init.model.pick_hint"))
                ?.also { warnUnlisted(it, listed) }
        }
        return picked ?: null.also { cli.fail(cli.t("init.no_answer")) }
    }

    /** Warns of the [models] that a non-empty [listed] lacks. */
    private fun warnUnlisted(models: List<String>, listed: List<String>?) {
        val unknown = models.filter { !listed.isNullOrEmpty() && it !in listed }
        if (unknown.isEmpty()) return
        ui.warn(
            cli.t("init.model.not_listed", "models" to unknown.joinToString(), "listed" to sample(listed.orEmpty())),
        )
    }

    private fun sample(listed: List<String>): String = if (listed.isEmpty()) {
        cli.t("init.model.none_listed")
    } else {
        listed.take(10).joinToString() +
            if (listed.size > 10) ", …" else ""
    }

    // Telegram.

    private fun telegram(): Int {
        val given = cli.environment[TELEGRAM_TOKEN_VARIABLE]?.takeIf { it.isNotEmpty() }
        if (!interactive && given == null) {
            ui.warn(cli.t("init.telegram.no_token", "variable" to TELEGRAM_TOKEN_VARIABLE))
            skip(InitStep.TELEGRAM)
            return ExitCode.OK
        }
        if (!begins(InitStep.TELEGRAM)) return ExitCode.OK
        if (interactive && given == null) ui.say(cli.t("init.telegram.about"))
        var checked: Pair<BotCheck.Bot, String>? = null
        for (attempt in 1..ATTEMPTS) {
            checked = askBot(cli, telegramApi, given.takeIf { attempt == 1 })
            if (checked != null || !interactive) break
        }
        val (found, token) = checked ?: return ExitCode.FAILURE
        configuredBot(cli, config, token)?.takeIf { it != INSTANCE }?.let {
            return cli.fail(cli.t("channel.same_bot", "instance" to it))
        }
        bot = checked
        summary["init.summary.bot"] = described(cli, found)
        val variable = telegramVariable(INSTANCE)
        secrets[variable] = token
        changes += ConfigChangeView("$TELEGRAM_INSTANCES.$INSTANCE.botToken", JsonPrimitive(EnvReferences.of(variable)))
        options.apiBaseUrl?.let {
            changes += ConfigChangeView("$TELEGRAM_INSTANCES.$INSTANCE.apiBaseUrl", JsonPrimitive(it))
        }
        if (agentConfig?.objectAt("channels")?.get("telegram:$INSTANCE") != null) {
            ui.good(cli.t("init.telegram.chosen", "bot" to described(cli, found), "agent" to agent))
        } else if (hasModel) {
            changes += ConfigChangeView("$AGENTS.$agent.channels.telegram:$INSTANCE", JsonObject(emptyMap()))
            ui.good(cli.t("init.telegram.chosen", "bot" to described(cli, found), "agent" to agent))
        } else {
            ui.warn(cli.t("init.telegram.no_model", "agent" to agent))
        }
        return ExitCode.OK
    }

    // Admin.

    private fun admin(): Int {
        val instance = config.at("$TELEGRAM_INSTANCES.$INSTANCE") as? JsonObject
        val current = bot ?: instance?.let { existingBot(it) }
        if (current == null) {
            ui.warn(cli.t("init.admin.no_bot"))
            skip(InitStep.ADMIN)
            return ExitCode.OK
        }
        if (!begins(InitStep.ADMIN)) return ExitCode.OK
        val admins = options.admins.ifEmpty {
            if (cli.status()?.instances?.any { it.id == "telegram:$INSTANCE" } == true) {
                return cli.fail(cli.t("claim.running"))
            }
            var claimant: Claimant? = null
            while (claimant == null) {
                claimant = claimAdmin(cli, telegramApi, current.second, current.first)
                if (claimant == null && !(interactive && cli.confirm(cli.t("init.admin.again"), default = true))) {
                    if (!interactive) return ExitCode.FAILURE
                    skip(InitStep.ADMIN)
                    return ExitCode.OK
                }
            }
            summary["init.summary.admin"] = claimant.described
            listOf(claimant.id)
        }
        summary.putIfAbsent("init.summary.admin", admins.joinToString())
        val root = "$TELEGRAM_INSTANCES.$INSTANCE"
        changes += ConfigChangeView("$root.adminUserIds", ids((instance?.get("adminUserIds") as? JsonArray), admins))
        val serves = agentConfig?.objectAt("channels")?.get("telegram:$INSTANCE") != null ||
            changes.any { it.path == "$AGENTS.$agent.channels.telegram:$INSTANCE" }
        if (serves) {
            changes +=
                ConfigChangeView("$AGENTS.$agent.channels.telegram:$INSTANCE.home", JsonPrimitive("${admins.first()}"))
        }
        ui.good(cli.t("init.admin.chosen", "admins" to admins.joinToString(), "agent" to agent))
        return ExitCode.OK
    }

    /** The bot of the configured [instance] and its token, null after printing why there is none. */
    private fun existingBot(instance: JsonObject): Pair<BotCheck.Bot, String>? {
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
        return when (val checked = ui.busy({ cli.t("bot.checking") }) { telegramBot(cli, base, token) }) {
            is BotCheck.Bot -> checked to token

            is BotCheck.Refused -> {
                cli.fail(cli.texts(checked.reason))
                null
            }
        }
    }

    private fun ids(existing: JsonArray?, added: List<Long>): JsonArray {
        val kept = existing?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
        return JsonArray((kept + added).distinct().map(::JsonPrimitive))
    }

    // Language and zone.

    private fun language(): Int {
        if (!begins(InitStep.LANGUAGE)) return ExitCode.OK
        val language = answered(options.language ?: systemLanguage(), "init.language.prompt") { text ->
            try {
                LanguageTag.of(text).value
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: return ExitCode.FAILURE
        val zone =
            answered(options.zone ?: cli.zone.id, "init.zone.prompt") { zoneOrNull(it)?.id } ?: return ExitCode.FAILURE
        changes += ConfigChangeView("app.language", JsonPrimitive(language))
        changes += ConfigChangeView("app.zone", JsonPrimitive(zone))
        ui.good(cli.t("init.language.chosen", "language" to language, "zone" to zone))
        summary["init.summary.language"] = "$language · $zone"
        return ExitCode.OK
    }

    /** [default], or what the person answers to [prompt], as [read] takes it; null after printing why it does not. */
    private fun answered(default: String, prompt: String, read: (String) -> String?): String? {
        if (!interactive) {
            return read(default) ?: run {
                cli.fail(cli.t("init.invalid", "answer" to default))
                null
            }
        }
        val answer = ui.ask(cli.t(prompt), read(default)) {
            if (read(it) == null) cli.t("init.invalid", "answer" to it) else null
        } ?: return null.also { cli.fail(cli.t("init.no_answer")) }
        return read(answer)
    }

    /** The language the environment's locale names, else the system's, English when neither names one. */
    private fun systemLanguage(): String {
        val posix = listOf("LC_ALL", "LC_MESSAGES", "LANG").firstNotNullOfOrNull {
            cli.environment[it]?.takeIf(String::isNotEmpty)
        }
            ?.substringBefore('.')?.substringBefore('@')
        val tag = when {
            posix == null -> cli.locale.toLanguageTag()
            posix == "C" || posix == "POSIX" -> "en"
            else -> posix
        }
        return try {
            LanguageTag.of(tag).value.takeIf { it != "und" } ?: "en"
        } catch (e: IllegalArgumentException) {
            "en"
        }
    }

    // Writing.

    private fun write(exists: Boolean): Int {
        ui.heading(cli.t("init.step.write"))
        val file = cli.locations.configFile
        if (!exists) {
            try {
                Files.createDirectories(file.toAbsolutePath().parent)
                Files.writeString(file, "{}\n")
            } catch (e: IOException) {
                return cli.fail(cli.t("init.cannot_create", "file" to file, "reason" to (e.message ?: e)))
            }
        }
        val secretsExisted = Files.exists(cli.secrets)
        val before = try {
            readSecrets(cli.secrets)
        } catch (e: IOException) {
            return undo(
                exists,
                cli.fail(cli.t("secret.unreadable", "file" to cli.secrets, "reason" to (e.message ?: e))),
            )
        }
        if (secrets.isNotEmpty()) {
            try {
                writeSecrets(cli.secrets, before + secrets)
            } catch (e: IOException) {
                return undo(
                    exists,
                    cli.fail(
                        cli.t(
                            "secret.unwritable",
                            "file" to cli.secrets,
                            "reason" to (e.message ?: e),
                        ),
                    ),
                )
            }
        }
        val written = writeChanges(cli, changes)
        if (written == null) {
            if (secrets.isNotEmpty()) restore(before, secretsExisted)
            return undo(exists, ExitCode.FAILURE)
        }
        ui.good(cli.t("init.written", "file" to written.file))
        summary["init.summary.config"] = written.file
        if (secrets.isNotEmpty()) {
            ui.good(cli.t("init.secrets", "names" to secrets.keys.joinToString(), "file" to cli.secrets))
            summary["init.summary.secrets"] = "${cli.secrets}"
        }
        return ExitCode.OK
    }

    private fun restore(secrets: Map<String, String>, existed: Boolean) {
        try {
            if (existed) writeSecrets(cli.secrets, secrets) else Files.deleteIfExists(cli.secrets)
        } catch (e: IOException) {
            cli.fail(cli.t("secret.unwritable", "file" to cli.secrets, "reason" to (e.message ?: e)))
        }
    }

    /** Takes the config file this run created away again, and returns [code]. */
    private fun undo(existed: Boolean, code: Int): Int {
        if (!existed) Files.deleteIfExists(cli.locations.configFile)
        return code
    }

    // Service.

    private fun service(): Int {
        if (!begins(InitStep.SERVICE)) return ExitCode.OK
        if (!Files.exists(cli.locations.configFile)) return cli.fail(cli.t("init.service.no_config"))
        val launch = serviceLaunch(cli) ?: run {
            ui.warn(cli.t("init.service.no_launcher"))
            skip(InitStep.SERVICE)
            return ExitCode.OK
        }
        val service = platformService(cli) ?: run {
            ui.warn(cli.t("init.service.no_systemd", "command" to launch.command.joinToString(" ")))
            skip(InitStep.SERVICE)
            return ExitCode.OK
        }
        val before = installedService(cli)
        val running = cli.status()
        if (running != null && before == null) {
            ui.warn(cli.t("init.service.foreground", "app" to described(cli, running)))
        }
        try {
            if (service.install(launch) != 0) {
                return cli.fail(cli.t("init.service.not_installed", "service" to service.description))
            }
        } catch (e: IOException) {
            return cli.fail(cli.t("init.service.cannot_write", "reason" to (e.message ?: e)))
        }
        ui.good(cli.t("init.service.installed", "service" to service.description))
        summary["init.summary.service"] = service.description
        if (running != null && before == null) return ExitCode.OK
        val code = if (running != null) service.restart() else service.start()
        if (code != 0) return cli.fail(cli.t("service.cannot_start", "service" to service.description))
        val status = ui.busy({ cli.t("init.service.waiting") }) { awaitReady(cli, running?.pid) }
        val ready = ready(cli, status)
        if (status != null) {
            summary["init.summary.service"] =
                cli.t("service.state", "service" to service.description, "state" to described(cli, status))
        }
        ui.say(cli.t("init.service.logs", "logs" to service.logsHint))
        return ready
    }
}

internal fun keyVariable(endpoint: String): String = "ALEXANDRITE_${endpoint.uppercase().replace('-', '_')}_API_KEY"

/**
 * What the service runs: the launcher of the install script, else the start script of the distribution this command
 * runs from, with this command's config file and directories; null when there is neither.
 */
internal fun serviceLaunch(cli: Cli): Launch? {
    val name = if (cli.platform == Platform.WINDOWS) "alexandrite.cmd" else "alexandrite"
    val installed = installRoot(cli).resolve("bin").resolve(name)
    val launcher = installed.takeIf(Files::isRegularFile)
        ?: cli.install?.resolve("bin")?.resolve(name)?.takeIf(Files::isRegularFile)
        ?: return null
    val locations = cli.locations
    val command = listOf(
        "${launcher.toAbsolutePath()}",
        "run",
        "--config",
        "${locations.configFile.toAbsolutePath()}",
        "--data-dir",
        "${locations.dataDir.toAbsolutePath()}",
        "--cache-dir",
        "${locations.cacheDir.toAbsolutePath()}",
    )
    return Launch(command, serviceLog(locations).toAbsolutePath())
}
