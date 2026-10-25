package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.account.pleochroic.AccountSummary
import org.unitedsoftware.alexandrite.account.pleochroic.DeviceCode
import org.unitedsoftware.alexandrite.account.pleochroic.DevicePoll
import org.unitedsoftware.alexandrite.account.pleochroic.Granted
import org.unitedsoftware.alexandrite.account.pleochroic.PLEOCHROIC_PORTAL_URL
import org.unitedsoftware.alexandrite.account.pleochroic.PLEOCHROIC_SLOW_DOWN
import org.unitedsoftware.alexandrite.account.pleochroic.PleochroicApiException
import org.unitedsoftware.alexandrite.account.pleochroic.beginDeviceLogin
import org.unitedsoftware.alexandrite.account.pleochroic.entitled
import org.unitedsoftware.alexandrite.account.pleochroic.fetchServiceStatus
import org.unitedsoftware.alexandrite.account.pleochroic.pollDeviceLogin
import org.unitedsoftware.alexandrite.account.pleochroic.revokeKey
import org.unitedsoftware.alexandrite.account.pleochroic.serviceAccount
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import org.unitedsoftware.alexandrite.runtime.config.writtenConfig
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val PLEOCHROIC_ENDPOINT = "default"
private const val PLEOCHROIC_PROVIDER = "providers.pleochroic.endpoints.$PLEOCHROIC_ENDPOINT"
private const val SEARCH_BACKEND = "tools.web.searchBackend"
private const val FETCH_BACKEND = "tools.web.fetchBackend"
private const val WEB_API_BASE = "tools.web.pleochroicApiBase"
private const val WEB_API_KEY = "tools.web.pleochroicApiKey"
private const val PLEOCHROIC_ID = "pleochroic"
private const val CLIENT = "alexandrite"

/** The timeouts every Pleochroic account HTTP call uses. */
private fun pleochroicHttp() = HttpTransport(HttpTimeouts(10.seconds, 30.seconds, 30.seconds))

/** The `X-Client` header's value, as the service API's convention asks for it. */
private fun clientHeader(deviceName: String?): String =
    "$CLIENT/$alexandriteVersion".let { if (deviceName == null) it else "$it $deviceName" }

/** The local machine's name, best used as the device login's `deviceName`, null when it cannot be told. */
private fun defaultDeviceName(): String? = try {
    InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() }
} catch (e: IOException) {
    null
}

/**
 * Logs in to a Pleochroic account through the `libraries/accounts/pleochroic` library: a device login at
 * [portalBaseUrl] (fixed in production; a parameter only so a test can point it at a fake), or, with [serviceUrl]
 * and [serviceToken] both given, a self-hosted standalone-service taken as is. Writes
 * `providers.pleochroic.endpoints.default` (the key through the secrets file, never as a plain value) and, where the
 * account is entitled for them, `tools.web.searchBackend` / `fetchBackend`, unless an operator already chose another
 * backend there.
 */
internal fun pleochroicLogin(
    cli: Cli,
    serviceUrl: String?,
    serviceToken: String?,
    deviceName: String?,
    portalBaseUrl: String = PLEOCHROIC_PORTAL_URL,
): Int = pleochroicHttp().use { http ->
    val apiBase: String
    val key: String
    val account: AccountSummary
    val status: JsonObject?
    if (serviceUrl != null && serviceToken != null) {
        status = try {
            runBlocking { fetchServiceStatus(http, serviceUrl, serviceToken, clientHeader(deviceName)) }
        } catch (e: PleochroicApiException) {
            return@use unreachable(cli, e)
        }
        apiBase = serviceUrl
        key = serviceToken
        account = serviceAccount(status)
    } else {
        val code = try {
            runBlocking {
                beginDeviceLogin(http, CLIENT, alexandriteVersion, deviceName ?: defaultDeviceName(), portalBaseUrl)
            }
        } catch (e: PleochroicApiException) {
            return@use unreachable(cli, e)
        }
        val granted = awaitDeviceLogin(cli, http, code, portalBaseUrl) ?: return@use ExitCode.FAILURE
        apiBase = granted.apiBase
        key = granted.key
        account = granted.account
        status = try {
            runBlocking { fetchServiceStatus(http, apiBase, key, clientHeader(deviceName)) }
        } catch (e: PleochroicApiException) {
            null
        }
    }
    val current = currentConfig(cli)
    val changes = mutableListOf(
        ConfigChangeView("$PLEOCHROIC_PROVIDER.baseUrl", JsonPrimitive(apiBase)),
        ConfigChangeView("$PLEOCHROIC_PROVIDER.apiKey", JsonPrimitive(key)),
    )
    if (status != null) {
        val search = status.entitled("search") && current?.at(SEARCH_BACKEND) == null
        val fetch = status.entitled("fetch") && current?.at(FETCH_BACKEND) == null
        if (search) changes += ConfigChangeView(SEARCH_BACKEND, JsonPrimitive(PLEOCHROIC_ID))
        if (fetch) changes += ConfigChangeView(FETCH_BACKEND, JsonPrimitive(PLEOCHROIC_ID))
        if (search || fetch) {
            // The web search/fetch backend reads its own copy of the apiBase and key, not the provider's endpoint.
            changes += ConfigChangeView(WEB_API_BASE, JsonPrimitive(apiBase))
            changes += ConfigChangeView(WEB_API_KEY, JsonPrimitive(key))
        }
    }
    val written = writeChanges(cli, changes) ?: return@use ExitCode.FAILURE
    cli.ui.good(cli.t("pleochroic.logged_in", "account" to (account.label ?: cli.t("pleochroic.unnamed"))))
    cli.reporting(false)(written)
}

/** Signs out: `DELETE /v1/key` at the account's own `apiBase` (best effort), then forgets it locally. */
internal fun pleochroicLogout(cli: Cli): Int {
    val current = currentConfig(cli)
    val baseUrl = current?.at("$PLEOCHROIC_PROVIDER.baseUrl").text
    val rawKey = current?.at("$PLEOCHROIC_PROVIDER.apiKey").text
    if (baseUrl == null || rawKey == null) return cli.done(cli.t("pleochroic.not_signed_in"))
    resolvedSecret(cli, rawKey)?.let { key ->
        val revoked = try {
            pleochroicHttp().use { http -> runBlocking { revokeKey(http, baseUrl, key) } }
            true
        } catch (e: PleochroicApiException) {
            false
        }
        if (!revoked) cli.say(cli.t("pleochroic.revoke_failed"))
    }
    val changes = mutableListOf(ConfigChangeView(PLEOCHROIC_PROVIDER, remove = true))
    if (current?.at(SEARCH_BACKEND).text == PLEOCHROIC_ID) changes += ConfigChangeView(SEARCH_BACKEND, remove = true)
    if (current?.at(FETCH_BACKEND).text == PLEOCHROIC_ID) changes += ConfigChangeView(FETCH_BACKEND, remove = true)
    if (current?.at(WEB_API_BASE) != null) changes += ConfigChangeView(WEB_API_BASE, remove = true)
    if (current?.at(WEB_API_KEY) != null) changes += ConfigChangeView(WEB_API_KEY, remove = true)
    val written = writeChanges(cli, changes) ?: return ExitCode.FAILURE
    forgetSecret(cli, rawKey)
    cli.ui.good(cli.t("pleochroic.logged_out"))
    return cli.reporting(false)(written)
}

/** `GET /v1/service` with the signed-in account's key, `--json` for the answer as is. */
internal fun pleochroicAccountStatus(cli: Cli, json: Boolean): Int {
    val current = currentConfig(cli)
    val baseUrl = current?.at("$PLEOCHROIC_PROVIDER.baseUrl").text
    val rawKey = current?.at("$PLEOCHROIC_PROVIDER.apiKey").text
    if (baseUrl == null || rawKey == null) {
        if (json) {
            cli.printJson(buildJsonObject { put("signedIn", false) })
            return ExitCode.OK
        }
        return cli.done(cli.t("pleochroic.not_signed_in"))
    }
    val key = resolvedSecret(cli, rawKey)
        ?: return cli.fail(cli.t("pleochroic.secret_missing", "file" to "${cli.secrets}"))
    val status = try {
        pleochroicHttp().use { http -> runBlocking { fetchServiceStatus(http, baseUrl, key) } }
    } catch (e: PleochroicApiException) {
        return unreachable(cli, e)
    }
    if (json) {
        cli.printJson(status)
        return ExitCode.OK
    }
    printStatus(cli, status)
    return ExitCode.OK
}

/**
 * Shows [code]'s user code and link, then polls [http] until the person allows or denies it, or it expires; null
 * after printing why nobody granted it.
 */
private fun awaitDeviceLogin(cli: Cli, http: HttpTransport, code: DeviceCode, portalBaseUrl: String): Granted? {
    val ui = cli.ui
    if (ui.rich) {
        ui.say(cli.t("pleochroic.open", "url" to code.verificationUri))
        ui.code(code.userCode)
    } else {
        ui.say(cli.t("pleochroic.open_complete", "url" to code.verificationUriComplete))
    }
    val deadline = System.nanoTime() + code.expiresIn.inWholeNanoseconds
    var interval = code.interval
    val (result, failure) = ui.busy({ cli.t("pleochroic.waiting") }) {
        var granted: Granted? = null
        var reason: Text? = null
        while (System.nanoTime() < deadline) {
            cli.machine.sleep(interval)
            val poll = try {
                runBlocking { pollDeviceLogin(http, code.deviceCode, portalBaseUrl) }
            } catch (e: PleochroicApiException) {
                reason = Text("pleochroic.unreachable", "reason" to (e.message ?: "$e"))
                break
            }
            when (poll) {
                is DevicePoll.Done -> {
                    granted = poll.granted
                    break
                }

                DevicePoll.Pending -> continue

                DevicePoll.SlowDown -> interval += PLEOCHROIC_SLOW_DOWN

                DevicePoll.Denied -> {
                    reason = Text("pleochroic.denied")
                    break
                }

                DevicePoll.Expired -> {
                    reason = Text("pleochroic.expired")
                    break
                }
            }
        }
        granted to reason
    }
    if (result != null) return result
    cli.fail(cli.texts(failure ?: Text("pleochroic.timeout")))
    return null
}

private fun unreachable(cli: Cli, e: PleochroicApiException): Int =
    cli.fail(cli.t("pleochroic.unreachable", "reason" to (e.message ?: "$e")))

private fun printStatus(cli: Cli, status: JsonObject) {
    val summary = serviceAccount(status)
    val scopes = (status["scopes"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.content.orEmpty() }
    val credits = (status.at("credits.balanceMicros") as? JsonPrimitive)?.content?.toLongOrNull()
    cli.ui.summary(
        cli.t("pleochroic.status_title"),
        listOfNotNull(
            summary.label?.let { cli.t("pleochroic.account") to it },
            summary.plan?.label?.let { cli.t("pleochroic.plan") to it },
            scopes?.let { cli.t("pleochroic.scopes") to it },
            credits?.let { cli.t("pleochroic.credits") to microDollars(it) },
        ) + servicesRows(cli, status),
    )
}

private fun servicesRows(cli: Cli, status: JsonObject): List<Pair<String, String>> =
    (status["services"] as? JsonObject)?.entries?.map { (name, value) ->
        val enabled = ((value as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
        name to cli.t(if (enabled) "pleochroic.enabled" else "pleochroic.disabled")
    }.orEmpty()

private fun microDollars(micros: Long): String = "$" + "%.2f".format(Locale.ROOT, micros / 1_000_000.0)

/** The config file as it is, without printing anything when it cannot be read (a fresh install has none yet). */
private fun currentConfig(cli: Cli): JsonObject? = try {
    writtenConfig(Files.readString(cli.locations.configFile))
} catch (e: IOException) {
    null
}

/** The value [raw] names: itself, or, when it is a `${env:NAME}` reference, that variable's value. */
private fun resolvedSecret(cli: Cli, raw: String): String? {
    val variable = EnvReferences.whole(raw) ?: return raw
    return try {
        withSecrets(cli.environment, cli.secrets)[variable]
    } catch (e: IOException) {
        null
    }
}

/** Removes the secrets-file entry [raw] refers to, once nothing in the config names it any longer. */
private fun forgetSecret(cli: Cli, raw: String) {
    val variable = EnvReferences.whole(raw) ?: return
    try {
        val secrets = readSecrets(cli.secrets).toMutableMap()
        if (secrets.remove(variable) != null) writeSecrets(cli.secrets, secrets)
    } catch (e: IOException) {
        cli.say(cli.t("secret.unwritable", "file" to "${cli.secrets}", "reason" to (e.message ?: "$e")))
    }
}
