package org.unitedsoftware.alexandrite.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.mcp.fake.FakeOAuthServer
import org.unitedsoftware.alexandrite.mcp.oauth.McpOAuth
import org.unitedsoftware.alexandrite.mcp.oauth.StoredTokens
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.testkit.TestCommandContext
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.io.IOException
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logins to MCP servers over HTTP: discovery, registration, PKCE, completion, tokens and their refresh. */
class OAuthTest {
    private val operator = testUser("ada", isOperator = true)
    private val clock = TestClock()
    private val replies = mutableListOf<String>()

    private class TestClock(var now: Instant = Instant.parse("2026-10-19T10:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private fun config(
        fake: FakeOAuthServer,
        oauth: OAuthConfig? = null,
        publicOrigin: String? = null,
        callbackPort: Int? = null,
    ) = McpConfig(
        mapOf("fake" to ServerConfigBuilder(url = fake.url, oauth = oauth).build(), "files" to fake()),
        publicOrigin,
        callbackPort,
    )

    /** The reply to `/mcp [arguments]` from [issuer] in a chat of [kind], and whether the model may see it. */
    private suspend fun Hub.mcp(
        arguments: String,
        issuer: ChatUser = operator,
        kind: ChatKind = ChatKind.DIRECT,
    ): Pair<String, Boolean> {
        val chat = testChat()
        val context = TestCommandContext(testTurn(TurnKind.COMMAND, chat) { actor(issuer) })
        McpCommand(hub, testTexts(PLUGIN)).handle(testCommand("mcp", arguments, chat, issuer, kind = kind), context)
        val reply = context.replies.single().text
        replies += reply
        return reply to context.repliesForModel.single()
    }

    private suspend fun Hub.text(arguments: String, issuer: ChatUser = operator, kind: ChatKind = ChatKind.DIRECT) =
        mcp(arguments, issuer, kind).first

    private fun link(reply: String): String = Regex("https?://\\S+/auth/authorize\\?\\S+").find(reply)!!.value

    private fun param(url: String, name: String): String? = url.substringAfter('?').split('&')
        .map {
            it.split('=', limit = 2)
        }.firstOrNull { it[0] == name }?.let { URLDecoder.decode(it[1], Charsets.UTF_8) }

    private suspend fun Hub.waitingForLogin() = until("waiting for a login") {
        server("fake").status.value.state == ServerState.NEEDS_LOGIN
    }

    /** Logs in to `fake` by pasting the address back, and waits until it is connected again. */
    private suspend fun Hub.login(fake: FakeOAuthServer) {
        val location = fake.approve(link(text("login fake")))
        val listed = fake.events.count { it == "listed" }
        assertEquals("MCP server fake is logged in and connects again.", text("login fake $location"))
        reconnected(fake, "listed", listed + 1)
    }

    private suspend fun Hub.call(tool: String, text: String = "hi") =
        tool("mcp.fake.$tool").execute(args("text" to text), testToolContext())

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Waits until [fake] has seen [event] [count] times and `fake` is connected again. */
    private suspend fun Hub.reconnected(fake: FakeOAuthServer, event: String, count: Int = 1) = until("reconnected") {
        fake.events.count { it == event } >= count && server("fake").status.value.state == ServerState.CONNECTED
    }

    // Discovery, registration and completion.

    @Test
    fun `on a host without an HTTP server a server that needs a login says it cannot log in and the others work`() =
        FakeOAuthServer().use { fake ->
            withHub(config(fake), clock = clock, callbacks = false) {
                waitingForLogin()
                until("files connected") { server("files").status.value.state == ServerState.CONNECTED }

                val reply = text("login fake")

                assertContains(reply, "MCP server fake cannot log in: ${McpOAuth.NO_CALLBACK}")
                assertFalse(fake.events.any { it.startsWith("register") }, "${fake.events}")
            }
        }

    @Test
    fun `a login found through the challenge registers a client and completes through the loopback callback`() =
        FakeOAuthServer().use { fake ->
            withHub(config(fake), clock = clock) {
                waitingForLogin()
                val listed = text("")
                assertContains(listed, "fake: waiting for a login")
                assertContains(
                    listed,
                    "To log in to a server, an operator sends /mcp login <server> in a direct chat with the bot.",
                )

                val (reply, forModel) = mcp("login fake")
                assertFalse(forModel)
                assertTrue(reply.startsWith("Open this link to log in to MCP server fake"), reply)
                assertContains(reply, "copy that page's address and send /mcp login fake <address>")
                val link = link(reply)
                assertEquals("S256", param(link, "code_challenge_method"))
                assertEquals(fake.url, param(link, "resource"))
                assertEquals("files:read offline_access", param(link, "scope"))
                val redirect = param(link, "redirect_uri")!!
                assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/callback").matches(redirect), redirect)
                assertContains(fake.events, "register native none $redirect")
                assertContains(fake.events, "resource metadata")

                val location = fake.approve(link)
                assertTrue(location.startsWith("$redirect?code="), location)
                assertEquals(
                    200 to "MCP server fake is logged in. You can close this page.",
                    FakeOAuthServer.browse(location),
                )
                connected("fake")
                assertContains(fake.events, "token pkce verified")
                assertEquals("hi", call("echo").text)
                until("the callback closed") {
                    try {
                        FakeOAuthServer.browse(location)
                        false
                    } catch (e: IOException) {
                        true
                    }
                }
            }
        }

    @Test
    fun `a login found at the well-known paths completes with the address pasted back`() =
        FakeOAuthServer(namesMetadata = false).use { fake ->
            withHub(config(fake), clock = clock) {
                waitingForLogin()
                login(fake)
                assertContains(fake.events, "resource metadata")
                assertEquals("hi", call("echo").text)
            }
        }

    @Test
    fun `the pasted answer may be the bare query, with or without its question mark`() = FakeOAuthServer().use { fake ->
        withHub(config(fake), clock = clock) {
            waitingForLogin()
            val first = fake.approve(link(text("login fake")))
            assertEquals(
                "MCP server fake is logged in and connects again.",
                text("login fake ?${first.substringAfter('?')}"),
            )
            connected("fake")
            assertEquals("The login to MCP server fake is forgotten.", text("logout fake"))
            waitingForLogin()
            assertEquals("MCP server fake has no login to forget.", text("logout fake"))

            val second = fake.approve(link(text("login fake")))
            assertEquals(
                "MCP server fake is logged in and connects again.",
                text("login fake ${second.substringAfter('?')}"),
            )
            connected("fake")
        }
    }

    @Test
    fun `a forged or a stale state is refused, and so is an answer from another issuer`() =
        FakeOAuthServer().use { fake ->
            withHub(config(fake), clock = clock) {
                waitingForLogin()
                val location = fake.approve(link(text("login fake")))
                assertEquals(
                    "The login to MCP server fake failed: No login waits for this answer: it expired or another one " +
                        "replaced it.",
                    text("login fake ${location.replace(Regex("state=[^&]+"), "state=forged")}"),
                )
                assertEquals(
                    "The login to MCP server fake failed: The answer names no issuer, though ${fake.issuer} " +
                        "promises to.",
                    text("login fake ${location.replace(Regex("&iss=[^&]+"), "")}"),
                )

                val stale = fake.approve(link(text("login fake")))
                clock.now = clock.now.plusSeconds(11 * 60)
                assertEquals(
                    "The login to MCP server fake failed: The login expired after 10 minutes.",
                    text("login fake $stale"),
                )

                fake.wrongIss = "https://evil.example"
                val mixed = fake.approve(link(text("login fake")))
                assertEquals(
                    "The login to MCP server fake failed: The answer comes from the issuer 'https://evil.example', " +
                        "not ${fake.issuer}.",
                    text("login fake $mixed"),
                )
                assertFalse(fake.events.any { it == "token pkce verified" })
                waitingForLogin()
            }
        }

    @Test
    fun `with a public origin the callback is served under it and the client registers as a web one`() {
        val port = freePort()
        FakeOAuthServer().use { fake ->
            withHub(config(fake, publicOrigin = "https://bot.example.test", callbackPort = port), clock = clock) {
                waitingForLogin()
                val reply = text("login fake")
                assertContains(reply, "The login then completes by itself.")
                val redirect = "https://bot.example.test/oauth/mcp/callback"
                assertEquals(redirect, param(link(reply), "redirect_uri"))
                assertContains(fake.events, "register web none $redirect")

                val location = fake.approve(link(reply))
                val proxied = "http://127.0.0.1:$port/oauth/mcp/callback?${location.substringAfter('?')}"
                assertEquals(200, FakeOAuthServer.browse(proxied).first)
                connected("fake")
            }
        }
    }

    @Test
    fun `with a public origin and no registration the host's own client document names the client`() {
        val port = freePort()
        FakeOAuthServer(registration = false, clientDocuments = true).use { fake ->
            withHub(config(fake, publicOrigin = "https://bot.example.test", callbackPort = port), clock = clock) {
                waitingForLogin()
                val link = link(text("login fake"))
                assertEquals("https://bot.example.test/oauth/mcp/client.json", param(link, "client_id"))
                val (status, document) = FakeOAuthServer.browse("http://127.0.0.1:$port/oauth/mcp/client.json")
                assertEquals(200, status)
                assertContains(document, "\"client_id\":\"https://bot.example.test/oauth/mcp/client.json\"")
                assertContains(document, "\"redirect_uris\":[\"https://bot.example.test/oauth/mcp/callback\"]")

                val location = fake.approve(link)
                FakeOAuthServer.browse("http://127.0.0.1:$port/oauth/mcp/callback?${location.substringAfter('?')}")
                connected("fake")
            }
        }
    }

    @Test
    fun `a server without registration needs a configured client, which then logs in with its secret`() {
        FakeOAuthServer(registration = false).use { fake ->
            withHub(config(fake), clock = clock) {
                waitingForLogin()
                assertEquals(
                    "The login to MCP server fake failed: ${fake.issuer} registers no clients by itself, so MCP " +
                        "server fake needs a client of its own: register one there and set " +
                        "mcp.servers.fake.oauth.clientId (and clientSecret if it has one).",
                    text("login fake"),
                )
            }
        }
        FakeOAuthServer(registration = false, clientId = "host-client", clientSecret = "host-secret-1").use { fake ->
            val oauth = OAuthConfig("host-client", Secret("host-secret-1"), listOf(FakeOAuthServer.WRITE))
            withHub(config(fake, oauth), clock = clock) {
                waitingForLogin()
                val link = link(text("login fake"))
                assertEquals("host-client", param(link, "client_id"))
                assertEquals("files:write files:read offline_access", param(link, "scope"))
                assertEquals(
                    "MCP server fake is logged in and connects again.",
                    text("login fake ${fake.approve(link)}"),
                )
                connected("fake")
                assertEquals("written", call("write", "written").text)
                assertFalse(fake.events.any { it.startsWith("register") })
            }
        }
    }

    // Tokens.

    @Test
    fun `tokens are refreshed shortly before they expire, once for calls that need them together`() =
        FakeOAuthServer().use { fake ->
            withHub(config(fake), clock = clock) {
                waitingForLogin()
                login(fake)
                clock.now = clock.now.plusSeconds(3000)
                assertEquals("hi", call("echo").text)
                assertFalse("refresh" in fake.events)

                clock.now = clock.now.plusSeconds(560)
                val results = (1..3).map { async { call("echo", "call $it").text } }.awaitAll()
                assertEquals(listOf("call 1", "call 2", "call 3"), results)
                assertEquals(1, fake.events.count { it == "refresh" })
            }
        }

    @Test
    fun `a refused refresh falls back to waiting for a login, which the call tells the model`() =
        FakeOAuthServer().use { fake ->
            withHub(config(fake), clock = clock) {
                waitingForLogin()
                login(fake)
                fake.refuseRefresh = true
                clock.now = clock.now.plusSeconds(3590)
                val result = call("echo")
                assertTrue(result.isError)
                assertEquals(
                    "MCP server fake needs a login, so the call did not run. An operator must send /mcp login " +
                        "fake in a direct chat with the bot, then try again.",
                    result.text,
                )
                waitingForLogin()
                assertContains(fake.events, "refresh refused")
            }
        }

    @Test
    fun `a token the server stops accepting is refreshed once`() = FakeOAuthServer().use { fake ->
        withHub(config(fake), clock = clock) {
            waitingForLogin()
            login(fake)
            fake.revoke()
            assertTrue(call("echo").isError)
            reconnected(fake, "refresh")
            assertEquals("hi", call("echo").text)
        }
    }

    @Test
    fun `an insufficient scope asks for a new login with the wider scope`() = FakeOAuthServer().use { fake ->
        withHub(config(fake), clock = clock) {
            waitingForLogin()
            login(fake)
            val result = call("write")
            assertEquals(
                "MCP server fake needs a login with more permissions (scope 'files:write'), so the call did not " +
                    "run. An operator must send /mcp login fake in a direct chat with the bot, then try again.",
                result.text,
            )
            connected("fake")
            val listed = text("")
            assertContains(
                listed,
                "fake: connected, protocol 2026-07-28, 2 tools; needs a login with the scope files:write",
            )
            assertContains(listed, "an operator sends /mcp login <server>")
            assertFalse("refresh" in fake.events)

            val link = link(text("login fake"))
            assertEquals(
                setOf("files:read", "offline_access", "files:write"),
                param(link, "scope")!!.split(' ').toSet(),
            )
            val lists = fake.events.count { it == "listed" }
            text("login fake ${fake.approve(link)}")
            reconnected(fake, "listed", lists + 1)
            assertEquals("done", call("write", "done").text)
            assertFalse("needs a login" in text(""))
        }
    }

    @Test
    fun `tokens are kept in the records and survive a restart`() = FakeOAuthServer().use { fake ->
        val records = TestRecords().of(PLUGIN)
        withHub(config(fake), records, clock) {
            waitingForLogin()
            login(fake)
        }
        val stored = runBlocking { records.collection<StoredTokens>("oauth.tokens").list() }
        assertEquals(listOf("fake ${fake.issuer}"), stored.map { it.key })
        assertEquals(fake.issuer, stored.single().value.issuer)
        withHub(config(fake), records, clock) {
            connected("fake")
            assertEquals("hi", call("echo").text)
        }
    }

    @Test
    fun `no token, code or verifier reaches the log or a reply`() = FakeOAuthServer().use { fake ->
        val logger = LoggerFactory.getLogger("org.unitedsoftware") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val level = logger.level
        logger.level = Level.TRACE
        logger.addAppender(appender)
        try {
            withHub(config(fake), clock = clock) {
                waitingForLogin()
                login(fake)
                clock.now = clock.now.plusSeconds(3590)
                call("echo")
                fake.revoke()
                call("echo")
                reconnected(fake, "refresh", 2)
                text("")
                fake.refuseRefresh = true
                clock.now = clock.now.plusSeconds(7200)
                call("echo")
                waitingForLogin()
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
        val logged = appender.list.map { "${it.formattedMessage} ${it.throwableProxy?.message.orEmpty()}" }
        assertTrue(logged.any { "is logged in at" in it })
        assertTrue(fake.secrets.size >= 6)
        for (secret in fake.secrets) {
            assertNull(logged.firstOrNull { secret in it }, "A log line holds a credential")
            assertNull(replies.firstOrNull { secret in it }, "A reply holds a credential")
        }
    }

    // The command.

    @Test
    fun `logging in and out is for direct chats, and for servers reached over HTTP`() = FakeOAuthServer().use { fake ->
        withHub(config(fake), clock = clock) {
            val private = "Send /mcp login and /mcp logout in a direct chat with the bot: the login link and the " +
                "address you send back are credentials."
            assertEquals(private, text("login fake", kind = ChatKind.GROUP))
            assertEquals(
                private,
                text("login fake http://127.0.0.1/callback?code=c&state=s", kind = ChatKind.GROUP),
            )
            assertEquals(private, text("logout fake", kind = ChatKind.UNKNOWN))
            assertEquals("MCP server files runs as a command, so it has no login.", text("login files"))
            assertEquals("No MCP server nope is configured.", text("login nope"))
            assertFalse(fake.events.any { it.startsWith("register") })
        }
    }

    @Test
    fun `an authorization response is read from an address or its query`() {
        assertEquals(
            mapOf("code" to "a b", "state" to "s", "iss" to "https://as.example"),
            McpOAuth.parse("http://127.0.0.1:5/callback?code=a+b&state=s&iss=https%3A%2F%2Fas.example#frag"),
        )
        assertEquals(mapOf("code" to "c", "state" to "s"), McpOAuth.parse(" ?code=c&state=s "))
        assertEquals(mapOf("code" to "c", "state" to "s"), McpOAuth.parse("code=c&state=s"))
    }
}
