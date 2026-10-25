package org.unitedsoftware.alexandrite.app

import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.control.ControlRequest
import org.unitedsoftware.alexandrite.app.sync.SyncPolicy
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

private const val JSON = "--json"
private const val RESTART = "--restart"
private const val AGENT = "--agent"
private const val EXAMPLE_CHAT = "telegram:work:123456789"
private const val SERVICE_URL = "--service-url"
private const val SERVICE_TOKEN = "--service-token"
private const val DEVICE_NAME = "--device-name"

private const val RUN = "area.run"
private const val SETUP = "area.setup"
private const val CONFIGURATION = "area.config"
private const val SECRETS = "area.secrets"
private const val SHORTCUTS = "area.shortcuts"
private const val CHATS = "area.chats"
private const val PLEOCHROIC = "area.pleochroic"
private const val HELP = "area.help"

private fun noArguments(given: Given, name: String) = given.atMost(0, Text("usage.no_argument", "command" to name))

private fun admins(given: Given): List<Long> = given.all("--admin").map {
    it.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw UsageException("usage.admin_id")
}

/** The commands of `alexandrite`, in the order of the help. */
internal val COMMANDS: List<CliCommand> = listOf(
    CliCommand(listOf("run"), "", RUN) {
        noArguments(it, "run")
        CliAction.Foreground
    },
    CliCommand(listOf("start"), "", RUN) {
        noArguments(it, "start")
        CliAction.Act(::start)
    },
    CliCommand(listOf("stop"), "", RUN) {
        noArguments(it, "stop")
        CliAction.Act(::stop)
    },
    CliCommand(listOf("restart"), "", RUN) {
        noArguments(it, "restart")
        CliAction.Act(::restart)
    },
    CliCommand(listOf("status"), "[$JSON]", RUN, flags = setOf(JSON)) { given ->
        noArguments(given, "status")
        CliAction.Act { status(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("logs"),
        "[-f] [-n <lines>]",
        RUN,
        flags = setOf("-f", "--follow"),
        options = setOf("-n", "--lines"),
    ) { given ->
        noArguments(given, "logs")
        val text = given.option("-n") ?: given.option("--lines") ?: "200"
        val lines = text.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("usage.logs_lines")
        CliAction.Act { logs(it, lines, given.flag("-f") || given.flag("--follow")) }
    },
    CliCommand(
        listOf("init"),
        "[--non-interactive] [--only <step>]... [--skip <step>]... [--provider <provider>] [--endpoint <name>] " +
            "[--base-url <url>] [--model <model>]... [$AGENT <id>] [--admin <user id>]... [--language <tag>] " +
            "[--zone <zone>] [--api-base-url <url>] [--force]",
        SETUP,
        flags = setOf("--non-interactive", "--force"),
        options = setOf(
            "--only",
            "--skip",
            "--provider",
            "--endpoint",
            "--base-url",
            "--model",
            AGENT,
            "--admin",
            "--language",
            "--zone",
            "--api-base-url",
        ),
    ) { given ->
        noArguments(given, "init")
        val only = given.all("--only").map(::requireStep).toSet()
        val skipped = given.all("--skip").map(::requireStep).toSet()
        if (only.isNotEmpty() && skipped.isNotEmpty()) throw UsageException("usage.init_only_skip")
        val steps = (only.ifEmpty { InitStep.entries.toSet() }) - skipped
        val options = InitOptions(
            interactive = !given.flag("--non-interactive"),
            steps = steps,
            provider = given.option("--provider")?.let(::requireProvider),
            endpoint = given.option("--endpoint")?.let { requireId(it, "what.endpoint") },
            baseUrl = given.option("--base-url"),
            models = given.all("--model"),
            agent = given.option(AGENT)?.let { requireId(it, "what.agent_id") },
            admins = admins(given),
            language = given.option("--language"),
            zone = given.option("--zone"),
            apiBaseUrl = given.option("--api-base-url"),
            force = given.flag("--force"),
        )
        CliAction.Act { init(it, options) }
    },
    CliCommand(listOf("doctor"), "[$JSON]", SETUP, flags = setOf(JSON)) { given ->
        noArguments(given, "doctor")
        CliAction.Act { doctor(it, given.flag(JSON)) }
    },
    CliCommand(listOf("update"), "[--version <release>]", SETUP, options = setOf("--version")) { given ->
        noArguments(given, "update")
        CliAction.Act { update(it, given.option("--version")) }
    },
    CliCommand(listOf("uninstall"), "[--keep-data] [--yes]", SETUP, flags = setOf("--keep-data", "--yes")) { given ->
        noArguments(given, "uninstall")
        CliAction.Act { uninstall(it, given.flag("--keep-data"), given.flag("--yes")) }
    },
    CliCommand(listOf("config"), "", CONFIGURATION) { given ->
        given.operands.firstOrNull()?.let {
            throw UsageException(
                "usage.unknown_subcommand",
                "command" to "config",
                "given" to it,
                "subcommands" to configSubcommands(),
            )
        }
        CliAction.Act(::configMenu)
    },
    CliCommand(listOf("config", "get"), "[<key>] [$JSON]", CONFIGURATION, flags = setOf(JSON)) { given ->
        given.atMost(1, Text("usage.config_get"))
        val key = given.operands.firstOrNull()?.let(::requireKey)
        CliAction.Act { configGet(it, key, given.flag(JSON)) }
    },
    CliCommand(listOf("config", "set"), "<key> <value> [$RESTART]", CONFIGURATION, flags = setOf(RESTART)) { given ->
        if (given.operands.size < 2) throw UsageException("usage.config_set")
        val key = requireKey(given.operands.first())
        val value = valueOf(given.operands.drop(1).joinToString(" "))
        CliAction.Act { changeConfig(it, listOf(ConfigChangeView(key, value)), it.reporting(given.flag(RESTART))) }
    },
    CliCommand(listOf("config", "unset"), "<key> [$RESTART]", CONFIGURATION, flags = setOf(RESTART)) { given ->
        val key = requireKey(given.single(Text("usage.config_unset")))
        CliAction.Act {
            changeConfig(it, listOf(ConfigChangeView(key, remove = true)), it.reporting(given.flag(RESTART)))
        }
    },
    CliCommand(listOf("config", "edit"), "[$RESTART]", CONFIGURATION, flags = setOf(RESTART)) { given ->
        noArguments(given, "config edit")
        CliAction.Act { configEdit(it, it.reporting(given.flag(RESTART))) }
    },
    CliCommand(listOf("config", "check"), "[$JSON]", CONFIGURATION, flags = setOf(JSON)) { given ->
        noArguments(given, "config check")
        CliAction.Act { configCheck(it, given.flag(JSON)) }
    },
    CliCommand(listOf("config", "path"), "[$JSON]", CONFIGURATION, flags = setOf(JSON)) { given ->
        noArguments(given, "config path")
        CliAction.Act { configPath(it, given.flag(JSON)) }
    },
    CliCommand(listOf("secret", "set"), "<NAME>", SECRETS) { given ->
        val name = requireSecretName(given.single(Text("usage.secret_set")))
        CliAction.Act { secretSet(it, name) }
    },
    CliCommand(listOf("secret", "list"), "[$JSON]", SECRETS, flags = setOf(JSON)) { given ->
        noArguments(given, "secret list")
        CliAction.Act { secretList(it, given.flag(JSON)) }
    },
    CliCommand(listOf("secret", "unset"), "<NAME>", SECRETS) { given ->
        val name = requireSecretName(given.single(Text("usage.secret_unset")))
        CliAction.Act { secretUnset(it, name) }
    },
    CliCommand(listOf("agent", "list"), "[$JSON]", SHORTCUTS, flags = setOf(JSON)) { given ->
        noArguments(given, "agent list")
        CliAction.Act { agentList(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("agent", "add"),
        "<id> [--name <name>] [--description <text>] [--model <endpoint>/<model>] [--channel <type>:<name>]... " +
            "[--default] [$RESTART]",
        SHORTCUTS,
        flags = setOf("--default", RESTART),
        options = setOf("--name", "--description", "--model", "--channel"),
    ) { given ->
        val id = requireId(given.single(Text("usage.agent_add")), "what.agent_id")
        val model = given.option("--model")?.let(::requireModel)
        CliAction.Act {
            agentAdd(
                it,
                id,
                given.option("--name"),
                given.option("--description"),
                model,
                given.all("--channel"),
                given.flag("--default"),
                it.reporting(given.flag(RESTART)),
            )
        }
    },
    CliCommand(listOf("agent", "remove"), "<id> [$RESTART]", SHORTCUTS, setOf(RESTART)) {
        val id = requireId(it.single(Text("usage.agent_remove")), "what.agent_id")
        CliAction.Act { cli -> agentRemove(cli, id, cli.reporting(it.flag(RESTART))) }
    },
    CliCommand(
        listOf("model"),
        "[<endpoint>/<model>] [$AGENT <id>] [$JSON] [$RESTART]",
        SHORTCUTS,
        flags = setOf(JSON, RESTART),
        options = setOf(AGENT),
    ) { given ->
        given.atMost(1, Text("usage.model"))
        val model = given.operands.firstOrNull()?.let(::requireModel)
        val agent = given.option(AGENT)?.let { requireId(it, "what.agent_id") }
        CliAction.Act { model(it, model, agent, given.flag(JSON), given.flag(RESTART)) }
    },
    CliCommand(
        listOf("channel", "add"),
        "telegram [<name>] [--admin <user id>]... [$AGENT <id>] [--token-variable <NAME>] [--api-base-url <url>] " +
            "[$RESTART]",
        SHORTCUTS,
        flags = setOf(RESTART),
        options = setOf("--admin", AGENT, "--token-variable", "--api-base-url"),
    ) { given ->
        val type = given.operands.firstOrNull() ?: throw UsageException("usage.channel_type")
        if (type != "telegram") throw UsageException("usage.channel_unknown", "type" to type)
        given.atMost(2, Text("usage.channel_name"))
        val name = requireId(given.operands.getOrNull(1) ?: "main", "what.instance_name")
        val admins = admins(given)
        val variable = given.option("--token-variable")?.let(::requireSecretName)
        val agent = given.option(AGENT)?.let { requireId(it, "what.agent_id") }
        CliAction.Act {
            channelAddTelegram(it, name, admins, agent, variable, given.option("--api-base-url"), given.flag(RESTART))
        }
    },
    CliCommand(
        listOf("mcp", "add"),
        "<name> (--url <url> | <command> [<argument>...]) [$RESTART]",
        SHORTCUTS,
        flags = setOf(RESTART),
        options = setOf("--url"),
    ) { given ->
        val name = given.operands.firstOrNull() ?: throw UsageException("usage.mcp_add")
        val url = given.option("--url")
        val command = given.operands.drop(1)
        if ((url == null) == command.isEmpty()) throw UsageException("usage.mcp_target")
        CliAction.Act { mcpAdd(it, name, url, command, it.reporting(given.flag(RESTART))) }
    },
    CliCommand(listOf("mcp", "list"), "[$JSON]", SHORTCUTS, flags = setOf(JSON)) { given ->
        noArguments(given, "mcp list")
        CliAction.Act { mcpList(it, given.flag(JSON)) }
    },
    CliCommand(listOf("mcp", "remove"), "<name> [$RESTART]", SHORTCUTS, setOf(RESTART)) {
        val name = it.single(Text("usage.mcp_remove"))
        CliAction.Act { cli -> mcpRemove(cli, name, cli.reporting(it.flag(RESTART))) }
    },
    CliCommand(
        listOf("mcp", "login"),
        "<name> [--address <address>]",
        SHORTCUTS,
        options = setOf("--address"),
    ) { given ->
        val name = given.single(Text("usage.mcp_login"))
        CliAction.Act { mcpLogin(it, name, given.option("--address")) }
    },
    CliCommand(listOf("mcp", "logout"), "<name>", SHORTCUTS) { given ->
        val name = given.single(Text("usage.mcp_logout"))
        CliAction.Act { mcpLogout(it, name) }
    },
    CliCommand(listOf("pairings"), "[$JSON]", CHATS, flags = setOf(JSON)) { given ->
        noArguments(given, "pairings")
        if (given.flag(JSON)) CliAction.Act { pairings(it, true) } else CliAction.Ask(ControlRequest.ListPairings)
    },
    CliCommand(listOf("pair"), "<code>", CHATS) { given ->
        val code = given.operands.joinToString(" ").ifEmpty { throw UsageException("usage.pair") }
        CliAction.Ask(ControlRequest.Approve(code))
    },
    CliCommand(listOf("deny"), "<code>", CHATS) { given ->
        val code = given.operands.joinToString(" ").ifEmpty { throw UsageException("usage.deny") }
        CliAction.Ask(ControlRequest.Deny(code))
    },
    CliCommand(listOf("unpair"), "<chat>", CHATS) { given ->
        val chat = given.single(Text("usage.unpair", "example" to EXAMPLE_CHAT))
        if (ChatAddress.parseOrNull(chat) == null) {
            throw UsageException("usage.no_chat", "chat" to chat, "example" to EXAMPLE_CHAT)
        }
        CliAction.Ask(ControlRequest.Unpair(chat))
    },
    CliCommand(listOf("chats"), "[$JSON]", CHATS, flags = setOf(JSON)) { given ->
        noArguments(given, "chats")
        CliAction.Act { chats(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("login"),
        "pleochroic [$SERVICE_URL <url> $SERVICE_TOKEN <token>] [$DEVICE_NAME <name>]",
        PLEOCHROIC,
        options = setOf(SERVICE_URL, SERVICE_TOKEN, DEVICE_NAME),
    ) { given ->
        val service = given.single(Text("usage.login"))
        if (service != "pleochroic") throw UsageException("usage.login_unknown", "service" to service)
        val url = given.option(SERVICE_URL)
        val token = given.option(SERVICE_TOKEN)
        if ((url == null) != (token == null)) throw UsageException("usage.login_self_hosted")
        CliAction.Act { pleochroicLogin(it, url, token, given.option(DEVICE_NAME)) }
    },
    CliCommand(listOf("logout"), "pleochroic", PLEOCHROIC) { given ->
        val service = given.single(Text("usage.logout"))
        if (service != "pleochroic") throw UsageException("usage.logout_unknown", "service" to service)
        CliAction.Act(::pleochroicLogout)
    },
    CliCommand(listOf("account"), "[$JSON]", PLEOCHROIC, flags = setOf(JSON)) { given ->
        noArguments(given, "account")
        CliAction.Act { pleochroicAccountStatus(it, given.flag(JSON)) }
    },
    CliCommand(
        listOf("sync", "enable"),
        "[--prefer local|remote|merge]",
        PLEOCHROIC,
        options = setOf("--prefer"),
    ) { given ->
        noArguments(given, "sync enable")
        val policy = given.option("--prefer")?.let(::requireSyncPolicy) ?: SyncPolicy.MERGE
        CliAction.Act { syncEnable(it, policy) }
    },
    CliCommand(listOf("sync", "disable"), "", PLEOCHROIC) { given ->
        noArguments(given, "sync disable")
        CliAction.Act(::syncDisable)
    },
    CliCommand(listOf("sync", "now"), "[$JSON]", PLEOCHROIC, flags = setOf(JSON)) { given ->
        noArguments(given, "sync now")
        CliAction.Act { syncNow(it, given.flag(JSON)) }
    },
    CliCommand(listOf("sync", "status"), "[$JSON]", PLEOCHROIC, flags = setOf(JSON)) { given ->
        noArguments(given, "sync status")
        CliAction.Act { syncStatus(it, given.flag(JSON)) }
    },
    CliCommand(listOf("manual"), "[<topic>] [--search <words>]", HELP, options = setOf("--search")) { given ->
        val search = given.option("--search")
        if (search != null) {
            CliAction.Act { manualSearch(it, (listOf(search) + given.operands).joinToString(" ")) }
        } else {
            given.atMost(1, Text("usage.manual"))
            CliAction.Act { manual(it, given.operands.firstOrNull()) }
        }
    },
)
