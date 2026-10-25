package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.runtime.config.EnvReferences
import org.unitedsoftware.alexandrite.runtime.config.writtenConfig
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

private const val PLEOCHROIC_PROVIDER = "providers.pleochroic.endpoints.$PLEOCHROIC_ENDPOINT"
private const val SEARCH_BACKEND = "tools.web.searchBackend"
private const val FETCH_BACKEND = "tools.web.fetchBackend"
private const val WEB_API_BASE = "tools.web.pleochroicApiBase"
private const val WEB_API_KEY = "tools.web.pleochroicApiKey"
private const val PLEOCHROIC_ID = "pleochroic"

/**
 * Logs in to a Pleochroic account: a device login at the portal, or, with [serviceUrl] and [serviceToken] both given,
 * a self-hosted standalone-service taken as is. Writes `providers.pleochroic.endpoints.default` (the key through the
 * secrets file, never as a plain value) and, where the account is entitled for them, `tools.web.searchBackend` /
 * `fetchBackend`, unless an operator already chose another backend there.
 */
internal fun pleochroicLogin(cli: Cli, serviceUrl: String?, serviceToken: String?, deviceName: String?): Int {
    val apiBase: String
    val key: String
    val account: AccountSummary
    val status: JsonObject?
    if (serviceUrl != null && serviceToken != null) {
        val fetched = fetchServiceStatus(cli, serviceUrl, serviceToken, deviceName) ?: return ExitCode.FAILURE
        apiBase = serviceUrl
        key = serviceToken
        account = accountOf(fetched["account"] as? JsonObject)
        status = fetched
    } else {
        val code = requestDeviceCode(cli, deviceName ?: defaultDeviceName()) ?: return ExitCode.FAILURE
        val granted = awaitDeviceLogin(cli, code) ?: return ExitCode.FAILURE
        apiBase = granted.apiBase
        key = granted.key
        account = granted.account
        status = fetchServiceStatus(cli, apiBase, key, deviceName)
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
    val written = writeChanges(cli, changes) ?: return ExitCode.FAILURE
    cli.ui.good(cli.t("pleochroic.logged_in", "account" to (account.label ?: cli.t("pleochroic.unnamed"))))
    return cli.reporting(false)(written)
}

/** Signs out: `DELETE /v1/key` at the account's own `apiBase` (best effort), then forgets it locally. */
internal fun pleochroicLogout(cli: Cli): Int {
    val current = currentConfig(cli)
    val baseUrl = current?.at("$PLEOCHROIC_PROVIDER.baseUrl").text
    val rawKey = current?.at("$PLEOCHROIC_PROVIDER.apiKey").text
    if (baseUrl == null || rawKey == null) return cli.done(cli.t("pleochroic.not_signed_in"))
    resolvedSecret(cli, rawKey)?.let { key ->
        if (!revokeKey(cli, baseUrl, key)) cli.say(cli.t("pleochroic.revoke_failed"))
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
    val status = fetchServiceStatus(cli, baseUrl, key) ?: return ExitCode.FAILURE
    if (json) {
        cli.printJson(status)
        return ExitCode.OK
    }
    printStatus(cli, status)
    return ExitCode.OK
}

private fun printStatus(cli: Cli, status: JsonObject) {
    val summary = accountOf(status["account"] as? JsonObject)
    val scopes = (status["scopes"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.content.orEmpty() }
    val credits = (status.at("credits.balanceMicros") as? JsonPrimitive)?.content?.toLongOrNull()
    cli.ui.summary(
        cli.t("pleochroic.status_title"),
        listOfNotNull(
            summary.label?.let { cli.t("pleochroic.account") to it },
            summary.plan?.let { cli.t("pleochroic.plan") to it },
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
