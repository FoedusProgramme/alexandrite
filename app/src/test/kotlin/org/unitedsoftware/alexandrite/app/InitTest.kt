package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.control.ControlAnswers
import org.unitedsoftware.alexandrite.app.control.controlSocket
import org.unitedsoftware.alexandrite.app.control.serveControl
import org.unitedsoftware.alexandrite.app.tui.Key
import org.unitedsoftware.alexandrite.app.tui.ScriptedConsole
import org.unitedsoftware.alexandrite.app.tui.typed
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesks
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotApi
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeUser
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands
    private val models = FakeModelServer()
    private val bot = FakeBotApi()
    private val ada = FakeUser(1001, "Ada", "Lovelace", "ada_test")
    private val bob = FakeUser(1002, "Bob", username = "bob_test")
    private val app = FakeApp(pid = 5151)
    private var serving: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @BeforeTest
    fun setUp() {
        cli = Commands(directory, FakeMachine(interactive = true))
        cli.machine.live = true
        Files.createDirectories(cli.dataDir)
        Files.setPosixFilePermissions(cli.dataDir, PosixFilePermissions.fromString("rwx------"))
        val launcher = cli.home.resolve(".local/lib/alexandrite/bin/alexandrite")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, "#!/bin/sh\n")
        cli.machine.programs = { command ->
            if (command.take(3) == listOf("systemctl", "--user", "start")) serve()
            Executed(0, "")
        }
        models.enqueue(
            FakeResponse.builder().body("""{"data": [{"id": "deepseek-chat"}, {"id": "deepseek-reasoner"}]}""").build(),
            "/models",
        )
    }

    @AfterTest
    fun tearDown() {
        runBlocking { serving?.cancelAndJoin() }
        models.close()
        bot.close()
    }

    /** Serves the control socket as the started service would, once it answers. */
    private fun serve() {
        val answers = ControlAnswers("1.2.3", app = app) { PairingDesks(emptyList()) }
        serving = scope.launch { serveControl(cli.dataDir, answers::answer) }
        runBlocking { awaitControlSocket(controlSocket(cli.dataDir)) }
    }

    private fun file(): JsonObject = Json.parseToJsonElement(cli.config()).jsonObject

    private fun unit(): Path = cli.home.resolve(".config/systemd/user/alexandrite.service")

    @Test
    fun `the interactive init sets up the model, the bot, its admin, the language and the service`() {
        bot.send(ada, bot.direct(ada), "/start TEST-C0DE")
        cli.machine.answers += listOf(
            "",
            "zh-CN",
            "",
            "",
            "2",
            "sk-test",
            "2,1",
            "y",
            "3",
            bot.token,
            "n",
            "n",
            "n",
            "n",
            "",
        )

        val printed = cli("init", "--base-url", models.baseUrl, "--api-base-url", bot.baseUrl)

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "lists 2 models")
        assertContains(
            printed.out,
            "The token belongs to the bot Alexandrite Test Bot (@alexandrite_test_bot, 1234567890).",
        )
        assertContains(printed.out, "Send this code to the bot Alexandrite Test Bot")
        assertContains(printed.out, "https://t.me/alexandrite_test_bot?start=TEST-C0DE")
        assertContains(printed.out, "Ada Lovelace @ada_test (1001) sent the code and is the bot's admin.")
        assertContains(printed.out, "Alexandrite 1.2.3 (pid 5151) is running.")
        assertContains(printed.out, "Logs: journalctl --user --unit alexandrite.service, or alexandrite logs.")
        assertEquals("Bearer sk-test", models.requests.single().header("authorization"))
        val config = file()
        assertEquals(
            """{"apiKey":"${'$'}{env:DEEPSEEK_API_KEY}","baseUrl":"${models.baseUrl}"}""",
            "${config.at("providers.deepseek.endpoints.deepseek")}",
        )
        assertEquals(
            """["deepseek/deepseek-reasoner","deepseek/deepseek-chat"]""",
            "${config.at("agent.agents.main.models")}",
        )
        assertEquals(
            """{"botToken":"${'$'}{env:ALEXANDRITE_TELEGRAM_MAIN_TOKEN}","apiBaseUrl":"${bot.baseUrl}",""" +
                """"adminUserIds":[1001]}""",
            "${config.at("channels.telegram.instances.main")}",
        )
        assertEquals("""{"home":"1001"}""", "${config.at("agent.agents.main.channels.telegram:main")}")
        assertEquals("""{"language":"zh-CN","zone":"Europe/Berlin"}""", "${config.at("app")}")
        assertEquals(
            "DEEPSEEK_API_KEY=sk-test\nALEXANDRITE_TELEGRAM_MAIN_TOKEN=${bot.token}\n",
            Files.readString(cli.secrets),
        )
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cli.secrets)))
        assertFalse("sk-test" in cli.config() || bot.token in cli.config())
        assertTrue(bot.offsets.last()!! > 1, "${bot.offsets}")
        assertEquals("1001", "${bot.calls("sendMessage").single().params["chat_id"]}")
        assertContains(
            Files.readString(unit()),
            "ExecStart=${cli.home.resolve(".local/lib/alexandrite/bin/alexandrite")} run",
        )
        assertContains(cli.machine.executed, listOf("systemctl", "--user", "enable", "alexandrite.service"))
        assertContains(cli.machine.executed, listOf("loginctl", "enable-linger"))
        assertTrue(
            cli.machine.prompts.any {
                it.startsWith("API key for DeepSeek (not shown)")
            },
            "${cli.machine.prompts}",
        )
    }

    @Test
    fun `the claim ignores other senders and messages outside a direct chat`() {
        val group = bot.group(-100777, "Backend")
        bot.send(bob, bot.direct(bob), "let me in")
        bot.send(bob, group, "TEST-C0DE")
        bot.send(ada, bot.direct(ada), "test c0de")
        cli.environment = mapOf(TELEGRAM_TOKEN_VARIABLE to bot.token)

        val printed = cli(
            "init",
            "--non-interactive",
            "--only",
            "channels",
            "--api-base-url",
            bot.baseUrl,
        )

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "Ignored a message without the code from Bob @bob_test (1002).")
        assertContains(printed.out, "Ada Lovelace @ada_test (1001) sent the code")
        assertEquals("[1001]", "${file().at("channels.telegram.instances.main.adminUserIds")}")
        assertNull(file().at("channels.telegram.instances.main.allowedChats"))
        assertEquals(1, bot.calls("sendMessage").size)
        assertContains(printed.out, "The agent main has no model yet")
        assertNull(file().at("agent"))
    }

    @Test
    fun `a bot whose updates another program reads cannot be claimed`() {
        bot.fail("getUpdates", 409, "Conflict: terminated by other getUpdates request")
        cli.environment = mapOf(TELEGRAM_TOKEN_VARIABLE to bot.token)

        val printed =
            cli("init", "--non-interactive", "--only", "channels", "--api-base-url", bot.baseUrl)

        assertEquals(1, printed.code)
        assertContains(printed.err, "Another program reads this bot's updates (Telegram says: Conflict: terminated")
        assertFalse(Files.exists(cli.configFile))
    }

    @Test
    fun `the non-interactive init takes the flags and the environment, and skips what is missing`() {
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-env", "LANG" to "de_DE.UTF-8")

        val printed = cli(
            "init",
            "--non-interactive",
            "--provider",
            "deepseek",
            "--base-url",
            models.baseUrl,
            "--model",
            "deepseek-chat",
            "--skip",
            "service",
        )

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "No ALEXANDRITE_TELEGRAM_TOKEN is set, so Telegram is left out.")
        assertContains(
            printed.out,
            "Skipped Telegram and other channels: set it up later with alexandrite init --only channels",
        )
        assertEquals("\"deepseek/deepseek-chat\"", "${file().at("agent.agents.main.model")}")
        assertEquals("""{"language":"de-DE","zone":"Europe/Berlin"}""", "${file().at("app")}")
        assertEquals("DEEPSEEK_API_KEY=sk-env\n", Files.readString(cli.secrets))
        assertEquals(emptyList(), cli.machine.answers.toList())
        assertTrue(cli.machine.prompts.isEmpty())
        assertFalse(Files.exists(unit()))
    }

    @Test
    fun `a model the endpoint does not list is kept with a warning`() {
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-env")

        val printed = cli(
            "init",
            "--non-interactive",
            "--provider",
            "deepseek",
            "--base-url",
            models.baseUrl,
            "--model",
            "deepseek-alias",
            "--skip",
            "service",
        )

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(
            printed.out,
            "The endpoint does not list deepseek-alias (it lists deepseek-chat, deepseek-reasoner): kept as given",
        )
        assertEquals("\"deepseek/deepseek-alias\"", "${file().at("agent.agents.main.model")}")
    }

    @Test
    fun `a model typed at the pick that the endpoint does not list is kept`() {
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-env")
        cli.machine.answers += listOf("", "", "deepseek-alias")

        val asked = cli("init", "--only", "model", "--provider", "deepseek", "--base-url", models.baseUrl)

        assertEquals(0, asked.code, asked.out + asked.err)
        assertContains(asked.out, "The endpoint does not list deepseek-alias")
        assertEquals("\"deepseek/deepseek-alias\"", "${file().at("agent.agents.main.model")}")
    }

    @Test
    fun `a key the endpoint refuses writes nothing`() {
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-env")
        val refused = FakeModelServer().use { refusing ->
            refusing.enqueue(FakeResponse.builder(401).body("{}").build(), "/models")
            cli("init", "--non-interactive", "--provider", "deepseek", "--base-url", refusing.baseUrl, "--model", "x")
        }

        assertEquals(1, refused.code)
        assertContains(refused.err, "refuses the key (HTTP 401).")
        assertFalse(Files.exists(cli.configFile))
        assertFalse(Files.exists(cli.secrets))
    }

    @Test
    fun `an OpenRouter key is checked by a request only a valid key gets an answer to`() {
        models.enqueue(FakeResponse.builder(401).body("{}").build(), "/key")
        models.enqueue(FakeResponse.builder().body("""{"data": {"label": "test"}}""").build(), "/key")
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-made-up")
        val scripted = cli("init", "--non-interactive", "--provider", "openrouter", "--base-url", models.baseUrl)
        cli.environment = emptyMap()
        cli.machine.answers += listOf("", "sk-or-real", "1")

        val asked = cli("init", "--only", "model", "--provider", "openrouter", "--base-url", models.baseUrl)

        assertEquals(1, scripted.code)
        assertContains(scripted.err, "${models.baseUrl} refuses the key (HTTP 401).")
        assertEquals(0, asked.code, asked.out + asked.err)
        assertContains(asked.out, "${models.baseUrl} takes the key and lists 2 models.")
        assertEquals(
            listOf("/key" to "Bearer sk-made-up", "/key" to "Bearer sk-or-real", "/models" to "Bearer sk-or-real"),
            models.requests.map { it.path to it.header("authorization") },
        )
        assertEquals("OPENROUTER_API_KEY=sk-or-real\n", Files.readString(cli.secrets))
    }

    @Test
    fun `a key that cannot be checked is kept when the person says so, and the model is typed`() {
        models.enqueue(FakeResponse.builder(503).body("{}").build(), "/key")
        cli.machine.answers += listOf("", "sk-or-unchecked", "y", "vendor/model-a")

        val printed = cli("init", "--only", "model", "--provider", "openrouter", "--base-url", models.baseUrl)

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.err, "${models.baseUrl} answers the key check with HTTP 503.")
        assertContains(cli.machine.prompts, "The key could not be checked. Keep it anyway? [Y/n] ")
        assertContains(cli.machine.prompts, "Model name (the endpoint gave no list): ")
        assertEquals("\"openrouter/vendor/model-a\"", "${file().at("agent.agents.main.model")}")
        assertEquals("OPENROUTER_API_KEY=sk-or-unchecked\n", Files.readString(cli.secrets))
    }

    @Test
    fun `an existing config file is changed only when the person agrees, or with --force`() {
        val original = """{"app": {"language": "en"}}"""
        cli.config(original)
        cli.machine.answers += "n"

        val declined = cli("init", "--only", "language")
        val scripted = cli("init", "--non-interactive", "--only", "language")
        val forced = cli("init", "--non-interactive", "--only", "language", "--language", "fr", "--force")

        assertEquals(0, declined.code, declined.err)
        assertContains(declined.out, "The config file stays as it is.")
        assertEquals(1, scripted.code)
        assertContains(scripted.err, "add --force, or run it at a terminal.")
        assertEquals(0, forced.code, forced.err)
        assertEquals("""{"language":"fr","zone":"Europe/Berlin"}""", "${file().at("app")}")
        assertEquals(original, Files.readString(cli.configFile.resolveSibling("alexandrite.json.bak")))
    }

    @Test
    fun `a change the config writer refuses leaves the file, the secrets and the service as they were`() {
        val broken = """{"agent": {"agents": {"main": {"channels": {"telegram:old": {}}}}}}"""
        cli.config(broken)
        cli.environment = mapOf(TELEGRAM_TOKEN_VARIABLE to bot.token)

        val printed = cli("init", "--non-interactive", "--force", "--admin", "1001", "--api-base-url", bot.baseUrl)

        assertEquals(1, printed.code, printed.out + printed.err)
        assertContains(printed.err, "an agent that serves channels needs a model")
        assertEquals(broken, cli.config())
        assertFalse(Files.exists(cli.secrets))
        assertFalse(Files.exists(unit()))
    }

    @Test
    fun `channel add telegram claims the admin by code`() {
        cli.environment = mapOf("DEEPSEEK_KEY" to "sk-test")
        cli.config(
            """{"providers": {"deepseek": {"endpoints": {"deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_KEY}"}}}}, """ +
                """"agent": {"agents": {"main": {"model": "deepseek/deepseek-chat"}}}}""",
        )
        bot.send(ada, bot.direct(ada), "TEST-C0DE")
        cli.machine.answers += bot.token

        val printed = cli("channel", "add", "telegram", "--api-base-url", bot.baseUrl)

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "Ada Lovelace @ada_test (1001) sent the code and is the bot's admin.")
        assertEquals("[1001]", "${file().at("channels.telegram.instances.main.adminUserIds")}")
        assertEquals("""{"home":"1001"}""", "${file().at("agent.agents.main.channels.telegram:main")}")
    }

    @Test
    fun `init sets up a OneBot connection and its admin`() {
        cli.environment = mapOf("DEEPSEEK_KEY" to "sk-test")
        cli.config(
            """{"providers": {"deepseek": {"endpoints": {"deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_KEY}"}}}}, """ +
                """"agent": {"agents": {"main": {"model": "deepseek/deepseek-chat"}}}}""",
        )
        cli.machine.answers += listOf("", "1", "ws://127.0.0.1:3001", "secret-token", "111,222")

        val printed = cli("init", "--only", "channels", "--force")

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "The agent main answers on ws://127.0.0.1:3001.")
        assertEquals(
            """{"transport":"ws","endpoint":"ws://127.0.0.1:3001",""" +
                """"accessToken":"${'$'}{env:ALEXANDRITE_ONEBOT_MAIN_TOKEN}","admins":["111","222"],""" +
                """"allowedChats":["private:111","private:222"]}""",
            "${file().at("channels.onebot.instances.main")}",
        )
        assertEquals("""{"home":"private:111"}""", "${file().at("agent.agents.main.channels.onebot:main")}")
        assertContains(Files.readString(cli.secrets), "ALEXANDRITE_ONEBOT_MAIN_TOKEN=secret-token")
    }

    @Test
    fun `init leaves a OneBot connection without an admin unattached, with a warning`() {
        cli.environment = mapOf("DEEPSEEK_KEY" to "sk-test")
        cli.config(
            """{"providers": {"deepseek": {"endpoints": {"deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_KEY}"}}}}, """ +
                """"agent": {"agents": {"main": {"model": "deepseek/deepseek-chat"}}}}""",
        )
        cli.machine.answers += listOf("", "1", "ws://127.0.0.1:3001", "", "")

        val printed = cli("init", "--only", "channels", "--force")

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "No admin was given: nobody can approve")
        assertNull(file().at("channels.onebot.instances.main.admins"))
        assertEquals(
            """{"onebot:main":{}}""",
            "${file().at("agent.agents.main.channels")}",
        )
    }

    @Test
    fun `init sets up a QQ bot and its admin`() {
        cli.environment = mapOf("DEEPSEEK_KEY" to "sk-test")
        cli.config(
            """{"providers": {"deepseek": {"endpoints": {"deepseek": {"apiKey": "${'$'}{env:DEEPSEEK_KEY}"}}}}, """ +
                """"agent": {"agents": {"main": {"model": "deepseek/deepseek-chat"}}}}""",
        )
        cli.machine.answers += listOf("", "2", "APPID123", "app-secret", "OPENID1,OPENID2")

        val printed = cli("init", "--only", "channels", "--force")

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "The agent main answers as the bot APPID123.")
        assertEquals(
            """{"appId":"APPID123","appSecret":"${'$'}{env:ALEXANDRITE_QQBOT_MAIN_APPSECRET}",""" +
                """"admins":["OPENID1","OPENID2"]}""",
            "${file().at("channels.qqbot.instances.main")}",
        )
        assertEquals("""{"home":"private:OPENID1"}""", "${file().at("agent.agents.main.channels.qqbot:main")}")
        assertContains(Files.readString(cli.secrets), "ALEXANDRITE_QQBOT_MAIN_APPSECRET=app-secret")
    }

    @Test
    fun `the non-interactive init sets up Telegram alone when OneBot and QQ give it nothing to go on`() {
        cli.environment = mapOf(TELEGRAM_TOKEN_VARIABLE to bot.token)

        val printed =
            cli("init", "--non-interactive", "--only", "channels", "--admin", "1001", "--api-base-url", bot.baseUrl)

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "Alexandrite Test Bot")
        assertEquals("[1001]", "${file().at("channels.telegram.instances.main.adminUserIds")}")
        assertNull(file().at("channels.onebot.instances"))
        assertNull(file().at("channels.qqbot.instances"))
    }

    @Test
    fun `the non-interactive init takes OneBot and QQ from the environment`() {
        cli.environment = mapOf(
            ONEBOT_ENDPOINT_VARIABLE to "ws://127.0.0.1:3001",
            ONEBOT_ADMIN_VARIABLE to "111",
            QQBOT_APP_ID_VARIABLE to "APPID123",
            QQBOT_APP_SECRET_VARIABLE to "app-secret",
            QQBOT_ADMIN_VARIABLE to "OPENID1",
        )

        val printed = cli("init", "--non-interactive", "--only", "channels")

        assertEquals(0, printed.code, printed.out + printed.err)
        assertEquals("""["111"]""", "${file().at("channels.onebot.instances.main.admins")}")
        assertEquals("""["OPENID1"]""", "${file().at("channels.qqbot.instances.main.admins")}")
        assertNull(file().at("channels.telegram.instances"))
    }

    @Test
    fun `init keeps an already configured OneBot endpoint and QQ AppID`() {
        cli.config(
            """{"channels": {"onebot": {"instances": {"main": {"transport": "ws", """ +
                """"endpoint": "ws://127.0.0.1:3001", "accessToken": "tok", "admins": ["111"]}}}, """ +
                """"qqbot": {"instances": {"main": {"appId": "APPID123", "appSecret": "s3cret", """ +
                """"admins": ["OPENID1"]}}}}}""",
        )
        cli.machine.answers += listOf("", "1,2")

        val printed = cli("init", "--only", "channels", "--force")

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "The configured OneBot endpoint ws://127.0.0.1:3001 is kept.")
        assertContains(printed.out, "The configured QQ bot APPID123 is kept.")
        assertEquals("""["111"]""", "${file().at("channels.onebot.instances.main.admins")}")
        assertEquals("""["OPENID1"]""", "${file().at("channels.qqbot.instances.main.admins")}")
    }

    @Test
    fun `the service step without systemd says how to run Alexandrite`() {
        cli.config("{}")
        cli.machine.programs = { Executed(if (it.contains("show-environment")) 1 else 0, "") }

        val printed = cli("init", "--non-interactive", "--only", "service")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "This system has no systemd user manager")
        assertContains(printed.out, "alexandrite run --config ${cli.configFile.toAbsolutePath()}")
        assertNull(cli.machine.executed.firstOrNull { it.contains("enable") })
    }

    // Pleochroic.

    private fun deviceCodeBody(interval: Int = 0) = """{"deviceCode": "dc-1", "userCode": "BCDF-GHJK",
        "verificationUri": "https://portal.pleochroic.co.uk/device",
        "verificationUriComplete": "https://portal.pleochroic.co.uk/device?code=BCDF-GHJK",
        "expiresIn": 600, "interval": $interval}"""

    /** A raw [Cli] below [name] in [directory], bypassing the [Commands] harness so a test can override
     * [InitOptions.portalBaseUrl] (no CLI flag reaches it, the portal being fixed in production). */
    private fun rawCli(name: String, out: ByteArrayOutputStream, err: ByteArrayOutputStream): Cli {
        val base = directory.resolve(name)
        val machine = FakeMachine(interactive = false)
        machine.live = true
        return Cli(
            Locations(base.resolve("config/alexandrite.json"), base.resolve("data"), base.resolve("cache")),
            emptyMap(),
            PrintStream(out, true),
            PrintStream(err, true),
            machine,
            Platform.UNIX,
            base.resolve("home"),
            null,
        )
    }

    @Test
    fun `init signs in to a Pleochroic account by device login, retries through slow-downs, and shows the plan`() {
        val portal = FakeModelServer()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val raw = rawCli("device-login", out, err)
        try {
            portal.enqueue(FakeResponse.builder().body(deviceCodeBody()).build(), "/api/device/code")
            portal.enqueue(
                FakeResponse.builder(400).body("""{"error": "authorization_pending"}""").build(),
                "/api/device/token",
            )
            portal.enqueue(FakeResponse.builder(400).body("""{"error": "slow_down"}""").build(), "/api/device/token")
            portal.enqueue(
                FakeResponse.builder().body(
                    """{"key": "plc-abc123", "apiBase": "${portal.baseUrl}", "account": {"username": "john",
                        "name": "John", "plan": {"id": "intermodal", "name": "Intermodal"}}}""",
                ).build(),
                "/api/device/token",
            )
            portal.enqueue(
                FakeResponse.builder().body(
                    """{"account": {"name": "John", "plan": {"name": "Intermodal"}}, "scopes": ["models"],
                        "services": {"search": {"enabled": true}, "fetch": {"enabled": true}}}""",
                ).build(),
                "/service",
            )
            portal.enqueue(FakeResponse.builder().body("""{"data": [{"id": "chroma-1"}]}""").build(), "/models")

            val code = init(
                raw,
                InitOptions(
                    interactive = false,
                    steps = setOf(InitStep.MODEL),
                    provider = providerKind("pleochroic"),
                    models = listOf("chroma-1"),
                    portalBaseUrl = portal.baseUrl,
                ),
            )

            assertEquals(0, code, err.toString())
            assertContains(out.toString(), "https://portal.pleochroic.co.uk/device?code=BCDF-GHJK")
            assertEquals(3, portal.requests.count { it.path == "/api/device/token" })
            val config = Json.parseToJsonElement(Files.readString(raw.locations.configFile)).jsonObject
            assertEquals(
                """{"apiKey":"${'$'}{env:PLEOCHROIC_API_KEY}","baseUrl":"${portal.baseUrl}"}""",
                "${config.at("providers.pleochroic.endpoints.default")}",
            )
            assertEquals("\"default/chroma-1\"", "${config.at("agent.agents.main.model")}")
            assertEquals("\"pleochroic\"", "${config.at("tools.web.searchBackend")}")
            assertEquals("\"pleochroic\"", "${config.at("tools.web.fetchBackend")}")
            assertEquals("\"${portal.baseUrl}\"", "${config.at("tools.web.pleochroicApiBase")}")
            assertContains(out.toString(), "Pleochroic · John (Intermodal)")
            assertEquals("PLEOCHROIC_API_KEY=plc-abc123\n", Files.readString(raw.secrets))
        } finally {
            portal.close()
        }
    }

    @Test
    fun `a denied Pleochroic device login during init writes nothing`() {
        val portal = FakeModelServer()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val raw = rawCli("device-login-denied", out, err)
        try {
            portal.enqueue(FakeResponse.builder().body(deviceCodeBody()).build(), "/api/device/code")
            portal.enqueue(
                FakeResponse.builder(400).body("""{"error": "access_denied"}""").build(),
                "/api/device/token",
            )

            val code = init(
                raw,
                InitOptions(
                    interactive = false,
                    steps = setOf(InitStep.MODEL),
                    provider = providerKind("pleochroic"),
                    portalBaseUrl = portal.baseUrl,
                ),
            )

            assertEquals(1, code)
            assertContains(err.toString(), "denied")
            assertFalse(Files.exists(raw.locations.configFile))
            assertFalse(Files.exists(raw.secrets))
        } finally {
            portal.close()
        }
    }

    @Test
    fun `init signs in to a self-hosted Pleochroic service instead of asking for a key`() {
        models.enqueue(
            FakeResponse.builder().body(
                """{"account": {"name": "Acme"}, "scopes": ["models"],
                    "services": {"search": {"enabled": true}, "fetch": {"enabled": true}}}""",
            ).build(),
            "/service",
        )

        val printed = cli(
            "init", "--non-interactive", "--only", "model",
            "--provider", "pleochroic",
            "--service-url", models.baseUrl,
            "--service-token", "plc-self-token",
            "--model", "deepseek-chat",
        )

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "Pleochroic · Acme")
        val config = file()
        assertEquals(
            """{"apiKey":"${'$'}{env:PLEOCHROIC_API_KEY}","baseUrl":"${models.baseUrl}"}""",
            "${config.at("providers.pleochroic.endpoints.default")}",
        )
        assertEquals("\"default/deepseek-chat\"", "${config.at("agent.agents.main.model")}")
        assertEquals("\"pleochroic\"", "${config.at("tools.web.searchBackend")}")
        assertEquals("\"pleochroic\"", "${config.at("tools.web.fetchBackend")}")
        assertEquals("\"${models.baseUrl}\"", "${config.at("tools.web.pleochroicApiBase")}")
        assertEquals("PLEOCHROIC_API_KEY=plc-self-token\n", Files.readString(cli.secrets))
        assertTrue(models.requests.none { it.path == "/api/device/code" })
    }

    @Test
    fun `init's --service-url without --service-token, or the other way around, is a usage error`() {
        assertEquals(ExitCode.USAGE, cli("init", "--service-url", "http://x/v1").code)
        assertEquals(ExitCode.USAGE, cli("init", "--service-token", "tok").code)
    }

    @Test
    fun `the account step is skipped when the model step already signed in to Pleochroic`() {
        models.enqueue(
            FakeResponse.builder().body(
                """{"account": {"name": "Acme"}, "scopes": ["models"],
                    "services": {"search": {"enabled": true}, "fetch": {"enabled": true}}}""",
            ).build(),
            "/service",
        )

        val printed = cli(
            "init", "--non-interactive", "--only", "model", "--only", "account",
            "--provider", "pleochroic",
            "--service-url", models.baseUrl,
            "--service-token", "plc-tok",
            "--model", "deepseek-chat",
        )

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "Already signed in to a Pleochroic account as the model provider")
        assertEquals(1, Files.readString(cli.secrets).lines().count { it.isNotBlank() })
    }

    // Vision and other side models.

    @Test
    fun `a model that cannot take images offers a side model from one that can`() {
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-env")
        val vision = FakeModelServer()
        try {
            vision.enqueue(
                FakeResponse.builder().body(
                    """{"data": [
                        {"id": "text-only", "architecture": {"input_modalities": ["text"]}},
                        {"id": "vision-model", "architecture": {"input_modalities": ["text", "image"]}}
                    ]}""",
                ).build(),
                "/models",
            )
            cli.machine.answers += listOf("", "", "1", "y", "")

            val printed = cli("init", "--only", "model", "--provider", "deepseek", "--base-url", vision.baseUrl)

            assertEquals(0, printed.code, printed.out + printed.err)
            assertContains(printed.out, "The agent reads image with vision-model.")
            assertEquals("\"deepseek/vision-model\"", "${file().at("agent.agents.main.roles.image")}")
            assertEquals("\"deepseek/text-only\"", "${file().at("agent.agents.main.model")}")
        } finally {
            vision.close()
        }
    }

    @Test
    fun `no side model is offered when the listing gives no capabilities at all`() {
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-env")
        cli.machine.answers += listOf("", "", "1")

        val printed = cli("init", "--only", "model", "--provider", "deepseek", "--base-url", models.baseUrl)

        assertEquals(0, printed.code, printed.out + printed.err)
        assertNull(file().at("agent.agents.main.roles"))
    }

    // Import.

    @Test
    fun `import finds a locally discovered skill and MCP server and imports the ones picked`() {
        val skill = cli.home.resolve(".codex/skills/sample/SKILL.md")
        Files.createDirectories(skill.parent)
        Files.writeString(skill, "---\nname: sample\n---\nBody")
        val mcpConfig = cli.home.resolve(".codex/config.toml")
        Files.writeString(
            mcpConfig,
            """
            [mcp_servers.fixture]
            command = "uvx"
            args = ["mcp-server-fixture"]

            [mcp_servers.fixture.env]
            FIXTURE_TOKEN = "fixture-secret"
            """.trimIndent(),
        )
        cli.environment = mapOf(API_KEY_VARIABLE to "sk-env")
        cli.machine.answers += listOf("", "", "", "1,2")

        val printed = cli(
            "init", "--only", "model", "--only", "import",
            "--provider", "deepseek", "--base-url", models.baseUrl, "--model", "deepseek-chat",
        )

        assertEquals(0, printed.code, printed.out + printed.err)
        assertContains(printed.out, "Imported 2.")
        assertEquals(
            "Body",
            Files.readString(
                cli.configFile.resolveSibling("skills/sample/SKILL.md"),
            ).substringAfterLast("---\n").trim(),
        )
        assertEquals("\"uvx\"", "${file().at("mcp.servers.fixture.command")}")
        assertEquals("""["mcp-server-fixture"]""", "${file().at("mcp.servers.fixture.args")}")
        val envRef = file().at("mcp.servers.fixture.env.FIXTURE_TOKEN").toString().removeSurrounding("\"")
        val variable = envRef.removePrefix("\${env:").removeSuffix("}")
        assertContains(Files.readString(cli.secrets), "$variable=fixture-secret")
    }

    // On the terminal.

    @Test
    fun `init draws its prompts on the terminal, with the key masked and a summary at the end`() {
        val console = ScriptedConsole(
            listOf(Key.Enter) + typed("zh-CN") + listOf(Key.Enter, Key.Enter) +
                listOf(Key.Enter, Key.Down, Key.Enter) + typed("sk-test") + Key.Enter +
                listOf(Key.Down, Key.Space, Key.Up, Key.Space, Key.Enter),
            width = 200,
        )
        cli.machine.console = console

        val printed = cli("init", "--only", "model", "--only", "language", "--base-url", models.baseUrl)

        assertEquals(0, printed.code, printed.out + printed.err + console.screen.lines())
        assertEquals("", printed.out + printed.err)
        assertTrue(cli.machine.prompts.isEmpty(), "${cli.machine.prompts}")
        val screen = console.screen.lines()
        assertEquals(
            listOf(
                "┌   Alexandrite setup",
                "│",
                "│  Setting Alexandrite up, with the config file ${cli.configFile}.",
                "│",
                "◈  Step 1 of 9: language and time zone.",
            ),
            screen.take(5),
        )
        val modelStart = screen.indexOf("◈  Step 2 of 9: the model.")
        assertTrue(modelStart > 5, "$screen")
        val expected = listOf(
            "◈  Step 2 of 9: the model.",
            "│",
            "◇  Set up a model provider now?",
            "│  Yes",
            "│",
            "◇  Model provider",
            "│  DeepSeek",
            "│",
            "◇  API key for DeepSeek",
            "│  ▪▪▪▪▪▪▪",
            "│",
            "✓  ${models.baseUrl} takes the key and lists 2 models.",
            "│",
            "◇  Models",
            "│  deepseek-reasoner, deepseek-chat",
            "│",
            "✓  The agent main uses deepseek/deepseek-reasoner, deepseek/deepseek-chat.",
        )
        assertEquals(expected, screen.subList(modelStart, modelStart + expected.size))
        assertTrue(screen.any { it.startsWith("│  Provider            DeepSeek · ${models.baseUrl}  ") }, "$screen")
        assertTrue(screen.any { it.startsWith("│  Language and zone   zh-CN · Europe/Berlin  ") }, "$screen")
        assertEquals("└  Done.", screen.last())
        assertFalse("sk-test" in "${console.written}")
        assertTrue(console.closed)
        assertEquals(
            """["deepseek/deepseek-reasoner","deepseek/deepseek-chat"]""",
            "${file().at("agent.agents.main.models")}",
        )
        assertEquals("""{"language":"zh-CN","zone":"Europe/Berlin"}""", "${file().at("app")}")
    }

    @Test
    fun `a cancelled prompt stops init without writing`() {
        val console = ScriptedConsole(listOf(Key.Enter, Key.Interrupt))
        cli.machine.console = console

        val printed = cli("init", "--only", "model", "--base-url", models.baseUrl)

        assertEquals(1, printed.code)
        assertContains(console.screen.lines(), "■  Model provider")
        assertEquals("└  Stopped at your request.", console.screen.lines().last())
        assertFalse(Files.exists(cli.configFile))
    }

    @Test
    fun `--plain, a dumb terminal or no terminal keep the plain prompts`() {
        val console = ScriptedConsole()
        cli.machine.console = console
        cli.machine.answers += listOf("", "fr", "")

        val plain = cli("init", "--plain", "--only", "language")
        cli.environment = mapOf("TERM" to "dumb")
        cli.machine.answers += listOf("y", "", "", "")
        val dumb = cli("init", "--only", "language")

        assertEquals(0, plain.code, plain.err)
        assertEquals(0, dumb.code, dumb.err)
        assertContains(plain.out, "Step 1 of 9: language and time zone.")
        assertContains(cli.machine.prompts, "Language of the agent's answers [en]: ")
        assertEquals("", "${console.written}")
    }
}
