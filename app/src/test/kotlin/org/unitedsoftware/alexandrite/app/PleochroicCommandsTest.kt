package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val API_BASE = "https://api.pleochroic.co.uk/v1"
private const val SECRET_NAME = "PROVIDERS_PLEOCHROIC_ENDPOINTS_DEFAULT_API_KEY"

/** The Pleochroic account commands, which make their own HTTP calls through [Machine] rather than the daemon. */
class PleochroicCommandsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var cli: Commands

    private val deviceCodeUrl = "$PLEOCHROIC_PORTAL/api/device/code"
    private val tokenUrl = "$PLEOCHROIC_PORTAL/api/device/token"
    private val serviceUrl = "$API_BASE/service"
    private val keyUrl = "$API_BASE/key"

    @BeforeTest
    fun setUp() {
        cli = Commands(directory)
        cli.config("{\"app\": {\"zone\": \"UTC\"}}")
    }

    private fun file(): JsonObject = Json.parseToJsonElement(cli.config()).jsonObject

    private fun deviceCode(interval: Int = 5) = "{\"deviceCode\":\"dc-1\",\"userCode\":\"BCDF-GHJK\"," +
        "\"verificationUri\":\"https://portal.pleochroic.co.uk/device\"," +
        "\"verificationUriComplete\":\"https://portal.pleochroic.co.uk/device?code=BCDF-GHJK\"," +
        "\"expiresIn\":600,\"interval\":$interval}"

    private fun granted(key: String = "plc-abc123") = "{\"key\":\"$key\",\"keyId\":\"k1\"," +
        "\"scopes\":[\"models\",\"sync\"]," +
        "\"account\":{\"username\":\"john\",\"name\":\"John\"," +
        "\"plan\":{\"id\":\"intermodal\",\"name\":\"Intermodal\"}}," +
        "\"apiBase\":\"$API_BASE\"}"

    private fun service(search: Boolean = true, fetch: Boolean = true) =
        "{\"service\":{\"name\":\"pleochroic\",\"version\":\"0.1.0\",\"apiVersion\":1}," +
            "\"account\":{\"id\":\"42\",\"name\":\"John\",\"plan\":{\"id\":\"intermodal\",\"name\":\"Intermodal\"}}," +
            "\"scopes\":[\"models\",\"sync\",\"tools\"],\"credits\":{\"balanceMicros\":12500000}," +
            "\"services\":{\"search\":{\"enabled\":$search},\"fetch\":{\"enabled\":$fetch}," +
            "\"sync\":{\"enabled\":true}}}"

    @Test
    fun `device login writes the endpoint and key as a secret, and turns on entitled web backends`() {
        cli.machine.web[deviceCodeUrl] = Fetched(200, deviceCode())
        cli.machine.web[tokenUrl] = Fetched(200, granted())
        cli.machine.web[serviceUrl] = Fetched(200, service())

        val printed = cli("login", "pleochroic")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "Signed in to the Pleochroic account John.")
        assertEquals("\"$API_BASE\"", "${file().at("providers.pleochroic.endpoints.default.baseUrl")}")
        assertEquals("\"\${env:$SECRET_NAME}\"", "${file().at("providers.pleochroic.endpoints.default.apiKey")}")
        assertEquals("$SECRET_NAME=plc-abc123\n", Files.readString(cli.secrets))
        assertEquals("\"pleochroic\"", "${file().at("tools.web.searchBackend")}")
        assertEquals("\"pleochroic\"", "${file().at("tools.web.fetchBackend")}")
        assertEquals("\"$API_BASE\"", "${file().at("tools.web.pleochroicApiBase")}")
        assertEquals("\"\${env:$SECRET_NAME}\"", "${file().at("tools.web.pleochroicApiKey")}")
        assertEquals(1, cli.machine.posted.count { it.first == deviceCodeUrl })
        assertContains(cli.machine.posted.first { it.first == deviceCodeUrl }.second, "\"client\":\"alexandrite\"")
    }

    @Test
    fun `an entitlement an account lacks is not turned on, and an operator's own backend choice is kept`() {
        cli.config(
            """{"app": {"zone": "UTC"}, "tools": {"web": {"fetchBackend": "brave"}}}""",
        )
        cli.machine.web[deviceCodeUrl] = Fetched(200, deviceCode())
        cli.machine.web[tokenUrl] = Fetched(200, granted())
        cli.machine.web[serviceUrl] = Fetched(200, service(search = true, fetch = false))

        val printed = cli("login", "pleochroic")

        assertEquals(0, printed.code, printed.err)
        assertEquals("\"pleochroic\"", "${file().at("tools.web.searchBackend")}")
        assertEquals("\"brave\"", "${file().at("tools.web.fetchBackend")}")
    }

    @Test
    fun `pending and slow_down polls are retried with a growing interval until the login is granted`() {
        cli.machine.web[deviceCodeUrl] = Fetched(200, deviceCode(interval = 1))
        cli.machine.postQueue[tokenUrl] = ArrayDeque(
            listOf(
                Fetched(400, """{"error":"authorization_pending"}"""),
                Fetched(400, """{"error":"slow_down"}"""),
                Fetched(400, """{"error":"authorization_pending"}"""),
                Fetched(200, granted()),
            ),
        )
        cli.machine.web[serviceUrl] = Fetched(200, service())

        val printed = cli("login", "pleochroic")

        assertEquals(0, printed.code, printed.err)
        assertEquals(4, cli.machine.posted.count { it.first == tokenUrl })
        assertEquals("$SECRET_NAME=plc-abc123\n", Files.readString(cli.secrets))
    }

    @Test
    fun `a denied or expired login is refused and writes nothing`() {
        cli.machine.web[deviceCodeUrl] = Fetched(200, deviceCode())
        cli.machine.web[tokenUrl] = Fetched(400, """{"error":"access_denied"}""")

        val printed = cli("login", "pleochroic")

        assertEquals(1, printed.code)
        assertContains(printed.err, "denied")
        assertNull(file().at("providers"))
        assertFalse(Files.exists(cli.secrets))
    }

    @Test
    fun `a self-hosted service is used directly, with no device login at all`() {
        cli.machine.web["http://localhost:8787/v1/service"] = Fetched(
            200,
            """{"account":{"name":"Self Hosted"},"scopes":["models","sync","tools"],
               |"services":{"search":{"enabled":true},"fetch":{"enabled":true}}}
            """.trimMargin().replace("\n", ""),
        )

        val printed = cli(
            "login",
            "pleochroic",
            "--service-url",
            "http://localhost:8787/v1",
            "--service-token",
            "SERVICE_TOKEN_VALUE",
        )

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "Signed in to the Pleochroic account Self Hosted.")
        assertEquals("\"http://localhost:8787/v1\"", "${file().at("providers.pleochroic.endpoints.default.baseUrl")}")
        assertEquals("SERVICE_TOKEN_VALUE", Files.readString(cli.secrets).substringAfter('=').trim())
        assertTrue(cli.machine.posted.none { it.first == deviceCodeUrl })
    }

    @Test
    fun `--service-url without --service-token, or the other way around, is a usage error`() {
        assertEquals(ExitCode.USAGE, cli("login", "pleochroic", "--service-url", "http://x/v1").code)
        assertEquals(ExitCode.USAGE, cli("login", "pleochroic", "--service-token", "tok").code)
        assertEquals(ExitCode.USAGE, cli("login", "other").code)
        assertEquals(ExitCode.USAGE, cli("logout", "other").code)
    }

    @Test
    fun `status without a login says so, as text or as json`() {
        val printed = cli("account")
        val json = cli("account", "--json")

        val message = "Not signed in to a Pleochroic account: run alexandrite login pleochroic.\n"
        assertEquals(Printed(0, message, ""), printed)
        assertEquals("{\n  \"signedIn\": false\n}\n", json.out)
    }

    @Test
    fun `status calls GET v1 service with the stored key and prints the account, plan and services`() {
        signIn()
        cli.machine.web[serviceUrl] = Fetched(200, service())

        val printed = cli("account")
        val json = cli("account", "--json")

        assertContains(printed.out, "John")
        assertContains(printed.out, "Intermodal")
        assertContains(json.out, "\"balanceMicros\": 12500000")
        val headers = cli.machine.fetched.last { it.first == serviceUrl }.second
        assertEquals("Bearer plc-abc123", headers["Authorization"])
    }

    @Test
    fun `logout revokes the key, forgets the secret and clears the entitled backends it had set`() {
        signIn()
        cli.machine.web[keyUrl] = Fetched(204, "")

        val printed = cli("logout", "pleochroic")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "Signed out")
        assertNull(file().at("providers.pleochroic.endpoints.default"))
        assertNull(file().at("tools.web.searchBackend"))
        assertNull(file().at("tools.web.fetchBackend"))
        assertNull(file().at("tools.web.pleochroicApiBase"))
        assertNull(file().at("tools.web.pleochroicApiKey"))
        assertFalse(SECRET_NAME in Files.readString(cli.secrets))
        val headers = cli.machine.deleted.last { it.first == keyUrl }.second
        assertEquals("Bearer plc-abc123", headers["Authorization"])
    }

    @Test
    fun `logout without a login is a no-op, and a failed revoke still forgets the key locally`() {
        val none = cli("logout", "pleochroic")

        assertEquals(0, none.code)
        assertContains(none.out, "Not signed in")

        signIn()
        // No `web[keyUrl]` is set, so the DELETE call fails as unreachable.
        val printed = cli("logout", "pleochroic")

        assertEquals(0, printed.code, printed.err)
        assertContains(printed.out, "forgotten locally all the same")
        assertNull(file().at("providers.pleochroic.endpoints.default"))
    }

    /** Signs in once, with both web entitlements, for a test that starts from a signed-in state. */
    private fun signIn() {
        cli.machine.web[deviceCodeUrl] = Fetched(200, deviceCode())
        cli.machine.web[tokenUrl] = Fetched(200, granted())
        cli.machine.web[serviceUrl] = Fetched(200, service())
        assertEquals(0, cli("login", "pleochroic").code)
        cli.machine.posted.clear()
        cli.machine.fetched.clear()
    }
}
