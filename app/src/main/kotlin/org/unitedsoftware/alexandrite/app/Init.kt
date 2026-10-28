package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.unitedsoftware.alexandrite.account.pleochroic.AREA_SKILL
import org.unitedsoftware.alexandrite.account.pleochroic.AccountFetch
import org.unitedsoftware.alexandrite.account.pleochroic.AccountImport
import org.unitedsoftware.alexandrite.account.pleochroic.AccountItem
import org.unitedsoftware.alexandrite.account.pleochroic.HostConfigSyncLocal
import org.unitedsoftware.alexandrite.account.pleochroic.HttpSyncTransport
import org.unitedsoftware.alexandrite.account.pleochroic.LocalChange
import org.unitedsoftware.alexandrite.account.pleochroic.PLEOCHROIC_PORTAL_URL
import org.unitedsoftware.alexandrite.account.pleochroic.SKILL_SCOPE_GLOBAL
import org.unitedsoftware.alexandrite.account.pleochroic.SyncPolicy
import org.unitedsoftware.alexandrite.account.pleochroic.SyncServiceException
import org.unitedsoftware.alexandrite.account.pleochroic.configPath
import org.unitedsoftware.alexandrite.account.pleochroic.importedRecords
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.tui.Choice
import org.unitedsoftware.alexandrite.discovery.DiscoveredMcpServer
import org.unitedsoftware.alexandrite.discovery.DiscoveredSkill
import org.unitedsoftware.alexandrite.discovery.discoverAll
import org.unitedsoftware.alexandrite.discovery.importSkill
import org.unitedsoftware.alexandrite.discovery.secretArgs
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.time.zoneOrNull
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** The steps of `init`, each named by [id], in the order [Setup.run] offers them. */
internal enum class InitStep(val id: String) {
    LANGUAGE("language"),
    MODEL("model"),
    CHANNELS("channels"),
    ACCOUNT("account"),
    IMPORT("import"),
    SEARCH("search"),
    BROWSER("browser"),
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
    /** A self-hosted standalone-service's address and token: the Pleochroic provider choice of the model step, or
     * the account step's own self-hosted choice, either reaches it. */
    val serviceUrl: String? = null,
    val serviceToken: String? = null,
    /** The portal a Pleochroic device login signs in through; only a test overrides it. */
    val portalBaseUrl: String = PLEOCHROIC_PORTAL_URL,
    /** Asks the account step for a device login non-interactively, the same login `--service-url` bypasses. */
    val pleochroicAccount: Boolean = false,
    /** The models of the endpoint, the first the agent's default. */
    val models: List<String> = emptyList(),
    val agent: String? = null,
    val admins: List<Long> = emptyList(),
    val language: String? = null,
    val zone: String? = null,
    /** The Bot API's address, Telegram's own when null. */
    val apiBaseUrl: String? = null,
    val searchBackend: String? = null,
    val fetchBackend: String? = null,
    /** The browser backend for `browser.use`: `pleochroic` once signed in, or `none`. */
    val browserBackend: String? = null,
    /** The address of a self-hosted SearXNG instance, for the search step's `searxng` choice. */
    val searxngUrl: String? = null,
    /** Whether an existing config file may be changed without asking. */
    val force: Boolean = false,
)

/** The variables `init` takes the provider's key and the bot token from. */
internal const val API_KEY_VARIABLE = "ALEXANDRITE_API_KEY"
internal const val TELEGRAM_TOKEN_VARIABLE = "ALEXANDRITE_TELEGRAM_TOKEN"

/** How often a step asks again for an answer it cannot use. */
internal const val INIT_ATTEMPTS = 3

/** Sets Alexandrite up step by step, and returns the exit code. */
internal fun init(cli: Cli, options: InitOptions): Int = cli.onTerminal { Setup(cli, options).run() }

/** One run of `init`: what its steps found, the changes they make and the secrets they save. */
private class Setup(override val cli: Cli, override val options: InitOptions) : ChannelSetupContext {
    override val interactive = options.interactive && cli.machine.interactive
    private val ui: Ui get() = cli.ui
    private val changes = mutableListOf<ConfigChangeView>()
    private val secrets = linkedMapOf<String, String>()
    private val skipped = mutableListOf<InitStep>()
    override var config = JsonObject(emptyMap())
    override lateinit var agent: String

    /** The rows of the summary, by the key of their label. */
    private val summary = linkedMapOf<String, String>()

    override fun change(view: ConfigChangeView) {
        changes += view
    }

    override fun secret(variable: String, value: String) {
        secrets[variable] = value
    }

    override fun summarize(key: String, value: String) {
        summary[key] = value
    }

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
        val asked = listOf(
            InitStep.LANGUAGE,
            InitStep.MODEL,
            InitStep.CHANNELS,
            InitStep.ACCOUNT,
            InitStep.IMPORT,
            InitStep.SEARCH,
            InitStep.BROWSER,
        )
        for (step in asked) {
            if (step !in steps) continue
            val code = when (step) {
                InitStep.LANGUAGE -> language()
                InitStep.MODEL -> model()
                InitStep.CHANNELS -> channels()
                InitStep.ACCOUNT -> account()
                InitStep.IMPORT -> import()
                InitStep.SEARCH -> webBackends()
                else -> browser()
            }
            if (code != ExitCode.OK) return code
        }
        if (changes.isNotEmpty()) {
            val code = write(exists)
            if (code != ExitCode.OK) return code
        }
        finishPleochroic()
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

    override val agentConfig: JsonObject? get() = config.at("$AGENTS.$agent") as? JsonObject

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
        var modalities: Map<String, Set<String>> = emptyMap()
        for (attempt in 1..INIT_ATTEMPTS) {
            key = key(kind, variable) ?: return ExitCode.FAILURE
            val given = key.ifEmpty { null }
            val listing =
                ui.busy({ cli.t("init.model.checking", "base" to base) }) { listModels(cli, kind, base, given) }
            if (listing is Listing.Models) {
                listed = listing.ids
                modalities = listing.inputModalities
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
            if (!interactive || attempt == INIT_ATTEMPTS) return ExitCode.FAILURE
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
        finishModelStep(endpoint, models.map { "$endpoint/$it" }, modalities)
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
        val modalities = (listing as? Listing.Models)?.inputModalities.orEmpty()
        finishModelStep(endpoint, models.map { "$endpoint/$it" }, modalities)
        return ExitCode.OK
    }

    private fun pleochroicSummary(kind: ProviderKind, signedIn: PleochroicSignIn): String {
        val account = signedIn.account.label ?: cli.t("pleochroic.unnamed")
        val plan = signedIn.account.plan?.label
        return "${title(kind)} · $account" + (plan?.let { " ($it)" } ?: "")
    }

    /** What every provider's model step finishes with, once it has [endpoint] and [named] (its chosen models,
     * `<endpoint>/<model>`): the agent's default description and models, a side-model offer for each media kind
     * the default model's [inputModalities] say it cannot take ([offerSideModels]), the summary line, and, when a
     * Telegram instance is already set up but serves no agent yet, wiring this agent to it. */
    private fun finishModelStep(endpoint: String, named: List<String>, inputModalities: Map<String, Set<String>>) {
        if (agentConfig == null) {
            changes += ConfigChangeView("$AGENTS.$agent.description", JsonPrimitive(cli.t("init.agent")))
        }
        changes += defaultModels(agent, agentConfig, named)
        ui.good(cli.t("init.model.chosen", "agent" to agent, "models" to named.joinToString()))
        offerSideModels(endpoint, named.firstOrNull(), inputModalities)
        summary["init.summary.models"] = cli.t("init.summary.models_value", "models" to named.joinToString())
        val instance = config.at("$TELEGRAM_INSTANCES.$CHANNEL_INSTANCE") as? JsonObject
        val served = agents(config).any { it.channels["telegram:$CHANNEL_INSTANCE"] != null }
        if (instance != null && !served) {
            val home = (instance["adminUserIds"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
            changes += ConfigChangeView(
                "$AGENTS.$agent.channels.telegram:$CHANNEL_INSTANCE",
                JsonObject(listOfNotNull(home?.let { "home" to JsonPrimitive(it) }).toMap()),
            )
        }
    }

    /**
     * Asks, for each media kind the default model [named] (bare, `<endpoint>/<model>` already stripped by the
     * caller — null when there is no default to check) cannot take, according to [inputModalities] (empty when the
     * listing gave none at all, for most providers — then nothing is asked, there being nothing to reason from),
     * whether to set a side model for it ([RolesConfig]'s `image`, `audio` and `video` roles; `file` is left out,
     * no listing this version reads names a "file" input modality to check against). Skippable at each question,
     * and asked only at a terminal.
     */
    private fun offerSideModels(endpoint: String, named: String?, inputModalities: Map<String, Set<String>>) {
        if (!interactive || named == null || inputModalities.isEmpty()) return
        val bare = named.substringAfter('/')
        val takes = inputModalities[bare] ?: return
        for (kind in listOf("image", "audio", "video")) {
            if (kind in takes) continue
            val capable = inputModalities.filterValues { kind in it }.keys.filter { it != bare }.sorted()
            if (capable.isEmpty()) continue
            val kindName = cli.t("init.model.role.$kind")
            if (!cli.confirm(cli.t("init.model.role_ask", "kind" to kindName), default = false)) continue
            val picked = ui.choose(
                prompt("init.model.role_pick", "init.model.role_pick_title", "kind" to kindName),
                capable.map { Choice(it, it, key = it) },
            ) { cli.t("init.model.no_such_provider", "answer" to it) } ?: continue
            changes += ConfigChangeView("$AGENTS.$agent.roles.$kind", JsonPrimitive("$endpoint/$picked"))
            ui.good(cli.t("init.model.role_set", "kind" to kindName, "model" to picked))
        }
    }

    override val hasModel: Boolean
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
        repeat(INIT_ATTEMPTS) {
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

    // Channels.

    /** Every channel plugin built into this binary whose questions this run asks: every one with usable
     * non-interactive input when the run is not interactive (nothing to pick from at a terminal), every one of
     * [CHANNEL_SETUPS] outright when there is exactly one, else the ones picked from all of them. */
    private fun chooseChannels(): List<ChannelSetup> {
        if (CHANNEL_SETUPS.size <= 1) return CHANNEL_SETUPS
        if (!interactive) return CHANNEL_SETUPS.filter { it.hasNonInteractiveInput(cli) }
        val picked = ui.pick(
            prompt("init.channels.pick", "init.channels.pick_title"),
            CHANNEL_SETUPS.map { it.title(cli) },
            cli.t("init.model.pick_hint"),
        ).orEmpty()
        return CHANNEL_SETUPS.filter { it.title(cli) in picked }
    }

    private fun channels(): Int {
        if (CHANNEL_SETUPS.isEmpty()) {
            ui.warn(cli.t("init.channels.none"))
            skip(InitStep.CHANNELS)
            return ExitCode.OK
        }
        if (!interactive && CHANNEL_SETUPS.none { it.hasNonInteractiveInput(cli) }) {
            ui.warn(cli.t("init.telegram.no_token", "variable" to TELEGRAM_TOKEN_VARIABLE))
            skip(InitStep.CHANNELS)
            return ExitCode.OK
        }
        if (!begins(InitStep.CHANNELS)) return ExitCode.OK
        for (setup in chooseChannels()) {
            val code = setup.setUp(this)
            if (code != ExitCode.OK) return code
        }
        return ExitCode.OK
    }

    // Pleochroic account.

    /** The signed-in Pleochroic account's `apiBase` and key: this run's own pending sign-in (steps "model" or
     * "account", not yet written to the config file) first, else what a previous run already wrote there. Reads
     * no file beyond the secrets one (already open for this run), so it never prints the "config file is missing"
     * a fresh install would otherwise get from [Cli.rawConfig]. */
    private fun pleochroicCredentials(): Pair<String, String>? {
        val pendingBase = changes.firstOrNull { it.path == "$PLEOCHROIC_PROVIDER.baseUrl" }?.value as? JsonPrimitive
        val pendingKey = changes.firstOrNull { it.path == "$PLEOCHROIC_PROVIDER.apiKey" }?.value as? JsonPrimitive
        if (pendingBase != null && pendingKey != null) {
            val variable = EnvReferences.whole(pendingKey.content)
            val key = if (variable == null) pendingKey.content else secrets[variable]
            return key?.let { pendingBase.content to it }
        }
        val endpoint = config.at(PLEOCHROIC_PROVIDER) as? JsonObject ?: return null
        val baseUrl = endpoint["baseUrl"].text ?: return null
        val rawKey = endpoint["apiKey"].text ?: return null
        val variable = EnvReferences.whole(rawKey)
        val key = if (variable == null) {
            rawKey
        } else {
            try {
                withSecrets(cli.environment, cli.secrets)[variable]
            } catch (e: IOException) {
                null
            }
        }
        return key?.let { baseUrl to it }
    }

    /**
     * The three choices for a Pleochroic account: sign in by device login, sign in to a self-hosted
     * `standalone-service` (`--service-url`/`--service-token`), or not at all. When already signed in (the model
     * step's Pleochroic provider, or an earlier run) there is no sign-in to ask for, only whether to turn sync on.
     */
    private fun account(): Int {
        if (pleochroicCredentials() != null) {
            ui.say(cli.t("init.account.already"))
            askSync()
            return ExitCode.OK
        }
        val wantsSelfHosted = options.serviceUrl != null && options.serviceToken != null
        if (!interactive && !wantsSelfHosted && !options.pleochroicAccount) {
            skip(InitStep.ACCOUNT)
            return ExitCode.OK
        }
        if (!begins(InitStep.ACCOUNT)) return ExitCode.OK
        var serviceUrl = options.serviceUrl
        var serviceToken = options.serviceToken
        if (!wantsSelfHosted && !options.pleochroicAccount) {
            if (!interactive) return ExitCode.OK
            val choice = ui.choose(
                prompt("init.account.choice", "init.account.choice_title"),
                listOf(
                    Choice(cli.t("init.account.choice.sign_in"), "sign-in", key = "sign-in"),
                    Choice(cli.t("init.account.choice.self_hosted"), "self-hosted", key = "self-hosted"),
                    Choice(cli.t("init.account.choice.none"), "none", key = "none"),
                ),
            ) { cli.t("init.model.no_such_provider", "answer" to it) } ?: return cli.fail(cli.t("init.no_answer"))
            when (choice) {
                "none" -> return ExitCode.OK

                "self-hosted" -> {
                    serviceUrl = ui.ask(cli.t("init.account.self_hosted_url"))
                        ?: return cli.fail(cli.t("init.no_answer"))
                    serviceToken = ui.secret(cli.t("init.account.self_hosted_token"))
                        ?: return cli.fail(cli.t("init.no_answer"))
                }
            }
        }
        val signedIn = pleochroicSignIn(cli, serviceUrl, serviceToken, null, options.portalBaseUrl)
            ?: return ExitCode.FAILURE
        changes += ConfigChangeView("$PLEOCHROIC_PROVIDER.baseUrl", JsonPrimitive(signedIn.apiBase))
        changes += ConfigChangeView("$PLEOCHROIC_PROVIDER.apiKey", JsonPrimitive(signedIn.key))
        changes += webBackendChanges(config, signedIn.status, signedIn.apiBase, signedIn.key)
        ui.good(cli.t("pleochroic.logged_in", "account" to (signedIn.account.label ?: cli.t("pleochroic.unnamed"))))
        summary["init.summary.account"] = signedIn.account.label ?: cli.t("pleochroic.unnamed")
        askSync()
        return ExitCode.OK
    }

    /** Asks whether to turn on two-way sync with the account, off unless the person says so: it uploads this
     * machine's config and secrets, so it is never on by default. Not asked when it is already on. */
    private fun askSync() {
        if (!interactive || readSyncState(cli).enabled) return
        enableSync = cli.confirm(cli.t("init.account.sync"), default = false)
        if (!enableSync) ui.say(cli.t("init.account.sync_off"))
    }

    // Import skills and MCP servers.

    private fun import(): Int {
        if (!interactive) {
            skip(InitStep.IMPORT)
            return ExitCode.OK
        }
        if (!begins(InitStep.IMPORT)) return ExitCode.OK
        val takenSkills = findSkillDirectoriesQuiet(globalSkillsRoot()).toSet()
        val takenMcp = (config.at("mcp.servers") as? JsonObject)?.keys.orEmpty()
        val localItems = mutableListOf<ImportItem>()
        var duplicates = 0
        for (source in discoverAll(cli.home)) {
            for (skill in source.skills) {
                if (skill.name in takenSkills) {
                    duplicates++
                    continue
                }
                localItems += ImportItem.LocalSkill("${source.label} · ${skill.name}", skill)
            }
            for (mcpServer in source.mcpServers) {
                if (mcpServer.name in takenMcp) {
                    duplicates++
                    continue
                }
                localItems += ImportItem.LocalMcp("${source.label} MCP · ${mcpServer.name}", mcpServer)
            }
        }
        val (pleochroicItems, pleochroicDuplicates) = pleochroicImportItems(takenSkills, takenMcp)
        duplicates += pleochroicDuplicates
        val items = localItems + pleochroicItems
        if (items.isEmpty()) {
            ui.say(
                if (duplicates >
                    0
                ) {
                    cli.t("init.import.only_duplicates", "count" to duplicates)
                } else {
                    cli.t("init.import.none")
                },
            )
            return ExitCode.OK
        }
        if (duplicates > 0) ui.warn(cli.t("init.import.duplicates", "count" to duplicates))
        val picked = ui.pick(cli.t("init.import.pick"), items.map { it.label }, cli.t("init.model.pick_hint"))
            .orEmpty()
        val chosen = items.filter { it.label in picked }
        if (chosen.isEmpty()) return ExitCode.OK
        var skillsRoot: java.nio.file.Path? = null
        val importedNames = mutableSetOf<String>()
        val accountItems = mutableListOf<AccountItem>()
        for (item in chosen) {
            when (item) {
                is ImportItem.LocalSkill -> {
                    val root = skillsRoot ?: globalSkillsRoot().also { skillsRoot = it }
                    importSkill(item.skill, root, takenSkills + importedNames)?.let { importedNames += it }
                }

                is ImportItem.LocalMcp -> importMcp(item.server)

                is ImportItem.Pleochroic -> accountItems += item.item
            }
        }
        val refused = if (accountItems.isEmpty()) 0 else applyPleochroicImport(accountItems)
        val imported = chosen.size - refused
        if (imported == 0) return ExitCode.OK
        ui.good(cli.t("init.import.done", "count" to imported))
        summary["init.summary.import"] = cli.t("init.summary.import_value", "count" to imported)
        return ExitCode.OK
    }

    private sealed interface ImportItem {
        val label: String

        class LocalSkill(override val label: String, val skill: DiscoveredSkill) : ImportItem

        class LocalMcp(override val label: String, val server: DiscoveredMcpServer) : ImportItem

        class Pleochroic(override val label: String, val item: AccountItem) : ImportItem
    }

    private fun globalSkillsRoot(): java.nio.file.Path =
        cli.locations.configFile.toAbsolutePath().parent.resolve("skills")

    private fun findSkillDirectoriesQuiet(root: java.nio.file.Path): List<String> = try {
        if (!Files.isDirectory(root)) {
            emptyList()
        } else {
            Files.newDirectoryStream(root).use { stream -> stream.map { it.fileName.toString() } }
        }
    } catch (e: IOException) {
        emptyList()
    }

    /** Writes [server] at `mcp.servers.<name>`: `command`/`cwd`/`url` literally, `env` and `headers` as given
     * (`mcp.servers.<name>.env`/`headers`'s values are `Secret`-typed, so [writeChanges] moves each of them into
     * the secrets file on its own — the same way `tools.web.braveApiKey` and the Pleochroic provider's own
     * `apiKey` already do), and an argument [secretArgs] finds embedded in [DiscoveredMcpServer.args] (a `List`,
     * which [writeChanges] does not itself look inside) moved by hand, the one case this step still manages. */
    private fun importMcp(server: DiscoveredMcpServer) {
        val root = "mcp.servers.${server.name}"
        server.command?.let { changes += ConfigChangeView("$root.command", JsonPrimitive(it)) }
        server.cwd?.let { changes += ConfigChangeView("$root.cwd", JsonPrimitive(it)) }
        server.url?.let { changes += ConfigChangeView("$root.url", JsonPrimitive(it)) }
        if (server.args.isNotEmpty()) {
            val embedded = secretArgs(server)
            val args = server.args.mapIndexed { index, value ->
                embedded[index]?.let { secretValue ->
                    val variable = mcpSecretVariable(server.name, "ARG_$index")
                    secrets[variable] = secretValue
                    EnvReferences.of(variable)
                } ?: value
            }
            changes += ConfigChangeView("$root.args", JsonArray(args.map(::JsonPrimitive)))
        }
        if (server.env.isNotEmpty()) {
            changes += ConfigChangeView("$root.env", JsonObject(server.env.mapValues { JsonPrimitive(it.value) }))
        }
        if (server.headers.isNotEmpty()) {
            changes +=
                ConfigChangeView("$root.headers", JsonObject(server.headers.mapValues { JsonPrimitive(it.value) }))
        }
    }

    private fun mcpSecretVariable(serverName: String, key: String): String {
        fun clean(text: String) = text.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        return "ALEXANDRITE_MCP_${clean(serverName)}_${clean(key)}"
    }

    /** The account's own secret names, as the import step's listing of it found them. */
    private var accountSecrets: Set<String> = emptySet()

    /** What the import step brought in from the Pleochroic account, recorded in the sync state once written. */
    private val accountImported = mutableListOf<AccountFetch.Ready>()

    /** Whether the account step's answer turns sync on, once the config file is written. */
    private var enableSync = false

    /** The Pleochroic account's own skills and MCP servers that are not yet set up here ([ImportItem.Pleochroic]),
     * and how many of its own were already present. Only reads the account ([AccountImport]), so it is as safe with
     * sync off as on; empty when not signed in, and empty with a warning when the account cannot be read. */
    private fun pleochroicImportItems(takenSkills: Set<String>, takenMcp: Set<String>): Pair<List<ImportItem>, Int> {
        val (apiBase, key) = pleochroicCredentials() ?: return emptyList<ImportItem>() to 0
        val listing = try {
            pleochroicHttp().use { runBlocking { AccountImport(HttpSyncTransport(it, apiBase, key)).list() } }
        } catch (e: SyncServiceException) {
            ui.warn(cli.t("init.import.pleochroic_unreachable", "reason" to (e.message ?: e.code)))
            return emptyList<ImportItem>() to 0
        }
        accountSecrets = listing.secrets
        val present = runBlocking { syncLocal().collect() }.mapTo(mutableSetOf()) { it.key }
        val pending = readSyncState(cli).pending.keys
        val items = mutableListOf<ImportItem>()
        var duplicates = 0
        var held = 0
        for (item in listing.items) {
            // A change a sync already held back for review is decided there, with its diff and reasons.
            if (item.key in pending) {
                held++
                continue
            }
            val taken = item.key in present || when (item.area) {
                AREA_SKILL -> item.kind == SKILL_SCOPE_GLOBAL && item.id in takenSkills
                else -> item.id in takenMcp
            }
            if (taken) {
                duplicates++
                continue
            }
            val label = when {
                item.area != AREA_SKILL -> "${cli.t("provider.pleochroic")} MCP · ${item.id}"
                item.kind == SKILL_SCOPE_GLOBAL -> "${cli.t("provider.pleochroic")} · ${item.id}"
                else -> "${cli.t("provider.pleochroic")} · ${item.id} (${item.kind})"
            }
            items += ImportItem.Pleochroic(label, item)
        }
        if (held > 0) ui.say(cli.t("sync.pending_hint", "count" to held))
        return items to duplicates
    }

    /** Fetches [items] from the account and sets each up: an MCP server and the secrets it names as this run's
     * changes, a skill's files once those are written ([finishPleochroic]). Returns how many were not imported. */
    private fun applyPleochroicImport(items: List<AccountItem>): Int {
        val (apiBase, key) = pleochroicCredentials() ?: return items.size
        val localSecrets = try {
            readSecrets(cli.secrets).toMutableMap()
        } catch (e: IOException) {
            mutableMapOf()
        }
        localSecrets += secrets
        var imported = 0
        try {
            pleochroicHttp().use { http ->
                val import = AccountImport(HttpSyncTransport(http, apiBase, key))
                for (item in items) {
                    val fetched = runBlocking { import.fetch(item, accountSecrets, localSecrets) }
                    if (fetched is AccountFetch.Refused) {
                        val args = arrayOf("name" to item.id, "detail" to fetched.detail)
                        ui.warn(cli.t("init.import.refused.${fetched.reason.id}", *args))
                        continue
                    }
                    val ready = fetched as AccountFetch.Ready
                    if (item.area != AREA_SKILL) {
                        changes += ConfigChangeView(configPath(item.kind, item.id) ?: continue, ready.data)
                        for (secret in ready.secrets.filter { it.missing }) {
                            secrets[secret.name] = secret.value
                            localSecrets[secret.name] = secret.value
                        }
                    }
                    accountImported += ready
                    imported++
                }
            }
        } catch (e: SyncServiceException) {
            ui.warn(cli.t("init.import.pleochroic_unreachable", "reason" to (e.message ?: e.code)))
        }
        return items.size - imported
    }

    /**
     * Once the config file holds this run's changes: writes the skills the import step brought in from the account,
     * records in the sync state what landed (so a later first sync finds both sides agreeing), and turns sync on
     * when the account step was told to. Nothing is pushed here; the first sync is still `alexandrite sync now`.
     */
    private fun finishPleochroic() {
        if (accountImported.isEmpty() && !enableSync) return
        var state = readSyncState(cli)
        if (accountImported.isNotEmpty()) {
            val local = syncLocal()
            val skills = accountImported.filter { it.item.area == AREA_SKILL }
            try {
                runBlocking { local.apply(skills.map { LocalChange(it.item.area, it.item.kind, it.item.id, it.data) }) }
            } catch (e: IOException) {
                ui.warn(cli.t("init.import.skills_unwritable", "reason" to (e.message ?: e)))
            }
            val landed = runBlocking { local.collect() }.mapTo(mutableSetOf()) { it.key }
            // HostConfigSyncLocal refuses an unsafe skill quietly: one that is not there now did not land.
            for (skill in skills) {
                if (skill.item.key !in landed) ui.warn(cli.t("init.import.refused.invalid", "name" to skill.item.id))
            }
            val records = runBlocking { importedRecords(local, accountImported) }
            state = state.with(records = state.records + records)
        }
        if (enableSync && !state.enabled) state = state.with(enabled = true, policy = SyncPolicy.MERGE)
        writeSyncState(cli, state)
        if (enableSync) {
            ui.good(cli.t("sync.enabled", "policy" to SyncPolicy.MERGE.id))
            ui.say(cli.t("sync.run_now_hint"))
            summary["init.summary.sync"] = cli.t("init.summary.sync_on", "policy" to SyncPolicy.MERGE.id)
        }
    }

    private fun syncLocal() = HostConfigSyncLocal(CliHostConfig(cli), cli.locations.configFile, cli.secrets)

    private fun pleochroicHttp() = org.unitedsoftware.alexandrite.internal.http.HttpTransport(
        HttpTimeouts(10.seconds, 30.seconds, 30.seconds),
    )

    // Web search and fetch.

    private fun webBackends(): Int {
        val requested = options.searchBackend != null || options.fetchBackend != null
        if (!interactive && !requested) {
            skip(InitStep.SEARCH)
            return ExitCode.OK
        }
        if (!begins(InitStep.SEARCH)) return ExitCode.OK
        val pleochroic = pleochroicCredentials()
        val searchChoices =
            listOfNotNull("pleochroic".takeIf { pleochroic != null }, "brave", "tavily", "searxng", "duckduckgo")
        val search = when (val picked = chooseBackend("init.search.backend", searchChoices, options.searchBackend)) {
            is BackendPick.Failed -> return ExitCode.FAILURE
            is BackendPick.None -> null
            is BackendPick.Id -> picked.id
        }
        if (search != null) {
            changes += ConfigChangeView("tools.web.searchBackend", JsonPrimitive(search))
            configureBackend(search, pleochroic)
        } else if (config.at("tools.web.searchBackend") != null) {
            changes += ConfigChangeView("tools.web.searchBackend", remove = true)
        }
        val fetchChoices = listOfNotNull("pleochroic".takeIf { pleochroic != null })
        val fetchRequested = options.fetchBackend?.takeIf { it != "direct" }
        val fetch = when (val picked = chooseBackend("init.search.fetch", fetchChoices, fetchRequested)) {
            is BackendPick.Failed -> return ExitCode.FAILURE
            is BackendPick.None -> null
            is BackendPick.Id -> picked.id
        }
        if (fetch != null) {
            changes += ConfigChangeView("tools.web.fetchBackend", JsonPrimitive(fetch))
            configureBackend(fetch, pleochroic)
        } else if (config.at("tools.web.fetchBackend") != null) {
            changes += ConfigChangeView("tools.web.fetchBackend", remove = true)
        }
        if (search != null || fetch != null) {
            summary["init.summary.search"] = listOfNotNull(
                search?.let { cli.t("init.search.id.$it") },
                fetch?.let { cli.t("init.search.id.$it") },
            ).joinToString(" · ").ifEmpty { cli.t("init.search.none") }
        }
        return ExitCode.OK
    }

    /** What [chooseBackend] answers: a backend [Id], explicitly [None] of the choices offered, or [Failed] (a
     * named, unknown backend from the command line) — distinct from [None] so the step can tell "nothing chosen"
     * from "something named that is not a choice". */
    private sealed interface BackendPick {
        class Id(val id: String) : BackendPick

        object None : BackendPick

        object Failed : BackendPick
    }

    /** The backend chosen among [choices] (plus "none"): [requested] when given (validated against [choices]
     * first), else an interactive choice at a terminal, else [BackendPick.None]. */
    private fun chooseBackend(promptKey: String, choices: List<String>, requested: String?): BackendPick {
        if (requested != null) {
            if (requested !in choices) {
                cli.fail(
                    cli.t("init.search.no_such_backend", "answer" to requested, "backends" to choices.joinToString()),
                )
                return BackendPick.Failed
            }
            return BackendPick.Id(requested)
        }
        if (!interactive) return BackendPick.None
        val none = "none"
        val offered = listOf(none) + choices
        val chosen = ui.choose(
            prompt(promptKey, "${promptKey}_title"),
            offered.map {
                Choice(
                    if (it ==
                        none
                    ) {
                        cli.t("init.search.none")
                    } else {
                        cli.t("init.search.id.$it")
                    },
                    it,
                    key = it,
                )
            },
        ) { cli.t("init.search.no_such_backend", "answer" to it, "backends" to offered.joinToString()) }
        return if (chosen == null || chosen == none) BackendPick.None else BackendPick.Id(chosen)
    }

    /** Asks for, or takes from the environment, whatever [id] needs: a key for `brave`/`tavily`, an instance
     * address for `searxng`, nothing for `duckduckgo`, and the signed-in account's own address and key, already
     * known, for `pleochroic`. */
    private fun configureBackend(id: String, pleochroic: Pair<String, String>?) {
        when (id) {
            "brave" -> backendKey("BRAVE_API_KEY", "init.search.brave_key")?.let {
                changes += ConfigChangeView("tools.web.braveApiKey", JsonPrimitive(it))
            }

            "tavily" -> backendKey("TAVILY_API_KEY", "init.search.tavily_key")?.let {
                changes += ConfigChangeView("tools.web.tavilyApiKey", JsonPrimitive(it))
            }

            "searxng" -> {
                val url = options.searxngUrl
                    ?: (
                        if (interactive) {
                            ui.ask(
                                cli.t("init.search.searxng_url"),
                                config.at("tools.web.searxngUrl").text,
                            )
                        } else {
                            null
                        }
                        )
                url?.let { changes += ConfigChangeView("tools.web.searxngUrl", JsonPrimitive(it.trimEnd('/'))) }
            }

            "pleochroic" -> pleochroic?.let { (base, key) ->
                changes += ConfigChangeView("tools.web.pleochroicApiBase", JsonPrimitive(base))
                changes += ConfigChangeView("tools.web.pleochroicApiKey", JsonPrimitive(key))
            }
        }
    }

    private fun backendKey(variable: String, promptKey: String): String? {
        cli.environment[variable]?.takeIf(String::isNotEmpty)?.let { return it }
        if (!interactive) return null
        return ui.secret(cli.t(promptKey))?.trim()?.takeIf(String::isNotEmpty)
    }

    // Browser automation.

    /**
     * The browser backend `browser.use` drives: `pleochroic` where a Pleochroic account (or self-hosted
     * standalone-service) is signed in, the only one this build has, or none, which leaves the tool unoffered.
     */
    private fun browser(): Int {
        val requested = options.browserBackend
        if (!interactive && requested == null) {
            skip(InitStep.BROWSER)
            return ExitCode.OK
        }
        if (!begins(InitStep.BROWSER)) return ExitCode.OK
        val pleochroic = pleochroicCredentials()
        val choices = listOfNotNull("pleochroic".takeIf { pleochroic != null })
        if (choices.isEmpty() && requested == null) {
            ui.say(cli.t("init.browser.none"))
            return ExitCode.OK
        }
        val browser = when (
            val picked = chooseBackend(
                "init.browser.pick",
                choices,
                requested?.takeIf {
                    it != "none"
                },
            )
        ) {
            is BackendPick.Failed -> return ExitCode.FAILURE
            is BackendPick.None -> null
            is BackendPick.Id -> picked.id
        }
        if (browser != null) {
            changes += ConfigChangeView("tools.web.browserBackend", JsonPrimitive(browser))
            configureBackend(browser, pleochroic)
            summary["init.summary.browser"] = cli.t("init.search.id.$browser")
        } else if (config.at("tools.web.browserBackend") != null) {
            changes += ConfigChangeView("tools.web.browserBackend", remove = true)
        }
        return ExitCode.OK
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
