package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SECRET_NAME = "PROVIDERS_PLEOCHROIC_ENDPOINTS_DEFAULT_API_KEY"

/**
 * The Pleochroic account commands, which now make their calls through the `libraries/accounts/pleochroic` library's
 * real [org.unitedsoftware.alexandrite.internal.http.HttpTransport] against a loopback [FakeModelServer] that
 * serves both the portal's device endpoints and the account's own `apiBase`, since nothing but a test overrides the
 * fixed portal URL.
 */
class PleochroicCommandsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var server: FakeModelServer
    private lateinit var out: ByteArrayOutputStream
    private lateinit var err: ByteArrayOutputStream
    private lateinit var cli: Cli

    @BeforeTest
    fun setUp() {
        server = FakeModelServer()
        out = ByteArrayOutputStream()
        err = ByteArrayOutputStream()
        val configFile = directory.resolve("config/alexandrite.json")
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, """{"app": {"zone": "UTC"}}""")
        val locations = Locations(configFile, directory.resolve("data"), directory.resolve("cache"))
        cli = Cli(
            locations,
            emptyMap(),
            PrintStream(out, true),
            PrintStream(err, true),
            FakeMachine(),
            Platform.UNIX,
            directory.resolve("home"),
            null,
        )
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun file(): JsonObject = Json.parseToJsonElement(Files.readString(cli.locations.configFile)).jsonObject

    private fun secrets(): String = try {
        Files.readString(cli.secrets)
    } catch (e: NoSuchFileException) {
        ""
    }

    private fun deviceCode(interval: Int = 0) = """{"deviceCode": "dc-1", "userCode": "BCDF-GHJK",
        "verificationUri": "https://portal.pleochroic.co.uk/device",
        "verificationUriComplete": "https://portal.pleochroic.co.uk/device?code=BCDF-GHJK",
        "expiresIn": 600, "interval": $interval}"""

    private fun granted(key: String = "plc-abc123") = """{"key": "$key", "apiBase": "${server.baseUrl}",
        "account": {"username": "john", "name": "John", "plan": {"id": "intermodal", "name": "Intermodal"}}}"""

    private fun service(search: Boolean = true, fetch: Boolean = true, browser: Boolean = true) =
        """{"account": {"id": "42", "name": "John", "plan": {"id": "intermodal", "name": "Intermodal"}},
            "scopes": ["models", "sync", "tools"], "credits": {"balanceMicros": 12500000},
            "services": {"search": {"enabled": $search}, "fetch": {"enabled": $fetch}, "browser": {"enabled": $browser},
            "sync": {"enabled": true}}}"""

    private fun login(): Int = pleochroicLogin(cli, null, null, "test-device", portalBaseUrl = server.baseUrl)

    @Test
    fun `device login writes the endpoint and key as a secret, and turns on entitled web backends`() {
        server.enqueue(fakeResponse { body(deviceCode()) }, "/api/device/code")
        server.enqueue(fakeResponse { body(granted()) }, "/api/device/token")
        server.enqueue(fakeResponse { body(service()) }, "/service")

        val code = login()

        assertEquals(0, code, err.toString())
        assertContains(out.toString(), "Signed in to the Pleochroic account John.")
        assertEquals("\"${server.baseUrl}\"", "${file().at("providers.pleochroic.endpoints.default.baseUrl")}")
        assertEquals("\"\${env:$SECRET_NAME}\"", "${file().at("providers.pleochroic.endpoints.default.apiKey")}")
        assertEquals("$SECRET_NAME=plc-abc123\n", secrets())
        assertEquals("\"pleochroic\"", "${file().at("tools.web.searchBackend")}")
        assertEquals("\"pleochroic\"", "${file().at("tools.web.fetchBackend")}")
        assertEquals("\"pleochroic\"", "${file().at("tools.web.browserBackend")}")
        assertEquals("\"${server.baseUrl}\"", "${file().at("tools.web.pleochroicApiBase")}")
        assertEquals("\"\${env:$SECRET_NAME}\"", "${file().at("tools.web.pleochroicApiKey")}")
        val codeRequest = server.requests.first { it.path == "/api/device/code" }
        assertContains(codeRequest.body, "\"client\":\"alexandrite\"")
    }

    @Test
    fun `an entitlement an account lacks is not turned on, and an operator's own backend choice is kept`() {
        Files.writeString(
            cli.locations.configFile,
            """{"app": {"zone": "UTC"}, "tools": {"web": {"fetchBackend": "brave"}}}""",
        )
        server.enqueue(fakeResponse { body(deviceCode()) }, "/api/device/code")
        server.enqueue(fakeResponse { body(granted()) }, "/api/device/token")
        server.enqueue(fakeResponse { body(service(search = true, fetch = false, browser = false)) }, "/service")

        val code = login()

        assertEquals(0, code, err.toString())
        assertEquals("\"pleochroic\"", "${file().at("tools.web.searchBackend")}")
        assertEquals("\"brave\"", "${file().at("tools.web.fetchBackend")}")
        assertNull(file().at("tools.web.browserBackend"))
    }

    @Test
    fun `pending and slow_down polls are retried until the login is granted`() {
        server.enqueue(fakeResponse { body(deviceCode()) }, "/api/device/code")
        server.enqueue(fakeResponse(400) { body("""{"error": "authorization_pending"}""") }, "/api/device/token")
        server.enqueue(fakeResponse(400) { body("""{"error": "slow_down"}""") }, "/api/device/token")
        server.enqueue(fakeResponse(400) { body("""{"error": "authorization_pending"}""") }, "/api/device/token")
        server.enqueue(fakeResponse { body(granted()) }, "/api/device/token")
        server.enqueue(fakeResponse { body(service()) }, "/service")

        val code = login()

        assertEquals(0, code, err.toString())
        assertEquals(4, server.requests.count { it.path == "/api/device/token" })
        assertEquals("$SECRET_NAME=plc-abc123\n", secrets())
    }

    @Test
    fun `a denied login is refused and writes nothing`() {
        server.enqueue(fakeResponse { body(deviceCode()) }, "/api/device/code")
        server.enqueue(fakeResponse(400) { body("""{"error": "access_denied"}""") }, "/api/device/token")

        val code = login()

        assertEquals(1, code)
        assertContains(err.toString(), "denied")
        assertNull(file().at("providers"))
        assertFalse(Files.exists(cli.secrets))
    }

    @Test
    fun `a self-hosted service is used directly, with no device login at all`() {
        server.enqueue(
            fakeResponse {
                body(
                    """{"account": {"name": "Self Hosted"}, "scopes": ["models", "sync", "tools"],
                        "services": {"search": {"enabled": true}, "fetch": {"enabled": true}}}""",
                )
            },
            "/service",
        )

        val code = pleochroicLogin(cli, server.baseUrl, "SERVICE_TOKEN_VALUE", null)

        assertEquals(0, code, err.toString())
        assertContains(out.toString(), "Signed in to the Pleochroic account Self Hosted.")
        assertEquals("\"${server.baseUrl}\"", "${file().at("providers.pleochroic.endpoints.default.baseUrl")}")
        assertEquals("SERVICE_TOKEN_VALUE", secrets().substringAfter('=').trim())
        assertTrue(server.requests.none { it.path == "/api/device/code" })
    }

    @Test
    fun `--service-url without --service-token, or the other way around, is a usage error`() {
        val harness = Commands(directory)
        assertEquals(ExitCode.USAGE, harness("login", "pleochroic", "--service-url", "http://x/v1").code)
        assertEquals(ExitCode.USAGE, harness("login", "pleochroic", "--service-token", "tok").code)
        assertEquals(ExitCode.USAGE, harness("login", "other").code)
        assertEquals(ExitCode.USAGE, harness("logout", "other").code)
    }

    @Test
    fun `status without a login says so, as text or as json`() {
        val printed = pleochroicAccountStatus(cli, false)
        val textMessage = out.toString()
        out.reset()
        val jsonCode = pleochroicAccountStatus(cli, true)

        assertEquals(0, printed)
        assertEquals("Not signed in to a Pleochroic account: run alexandrite login pleochroic.\n", textMessage)
        assertEquals(0, jsonCode)
        assertEquals("{\n  \"signedIn\": false\n}\n", out.toString())
    }

    @Test
    fun `status calls GET service with the stored key and prints the account, plan and services`() {
        signIn()
        server.enqueue(fakeResponse { body(service()) }, "/service")

        pleochroicAccountStatus(cli, false)
        val printed = out.toString()
        out.reset()
        server.enqueue(fakeResponse { body(service()) }, "/service")
        pleochroicAccountStatus(cli, true)

        assertContains(printed, "John")
        assertContains(printed, "Intermodal")
        assertContains(out.toString(), "\"balanceMicros\": 12500000")
        val request = server.requests.last { it.path == "/service" }
        assertEquals("Bearer plc-abc123", request.header("authorization"))
    }

    @Test
    fun `logout revokes the key, forgets the secret and clears the entitled backends it had set`() {
        signIn()
        server.enqueue(fakeResponse { }, "/key")

        val code = pleochroicLogout(cli)

        assertEquals(0, code, err.toString())
        assertContains(out.toString(), "Signed out")
        assertNull(file().at("providers.pleochroic.endpoints.default"))
        assertNull(file().at("tools.web.searchBackend"))
        assertNull(file().at("tools.web.fetchBackend"))
        assertNull(file().at("tools.web.browserBackend"))
        assertNull(file().at("tools.web.pleochroicApiBase"))
        assertNull(file().at("tools.web.pleochroicApiKey"))
        assertFalse(SECRET_NAME in secrets())
        val request = server.requests.last { it.path == "/key" }
        assertEquals("DELETE", request.method)
        assertEquals("Bearer plc-abc123", request.header("authorization"))
    }

    @Test
    fun `logout without a login is a no-op, and a failed revoke still forgets the key locally`() {
        val none = pleochroicLogout(cli)

        assertEquals(0, none)
        assertContains(out.toString(), "Not signed in")

        signIn()
        out.reset()
        // No `/key` response is queued, so the DELETE call fails as unreachable.
        val code = pleochroicLogout(cli)

        assertEquals(0, code, err.toString())
        assertContains(out.toString(), "forgotten locally all the same")
        assertNull(file().at("providers.pleochroic.endpoints.default"))
    }

    /** Signs in once, with both web entitlements, for a test that starts from a signed-in state. */
    private fun signIn() {
        server.enqueue(fakeResponse { body(deviceCode()) }, "/api/device/code")
        server.enqueue(fakeResponse { body(granted()) }, "/api/device/token")
        server.enqueue(fakeResponse { body(service()) }, "/service")
        assertEquals(0, login())
        out.reset()
        err.reset()
    }
}
