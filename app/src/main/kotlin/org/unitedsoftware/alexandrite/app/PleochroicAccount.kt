package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where the device login of a Pleochroic account starts; fixed, the portal is not an operator setting. */
internal const val PLEOCHROIC_PORTAL = "https://portal.pleochroic.co.uk"

/** The config id of the one Pleochroic endpoint these commands manage. */
internal const val PLEOCHROIC_ENDPOINT = "default"

private const val CLIENT = "alexandrite"

/** How much a poll interval grows after the server asks to slow down. */
private val SLOW_DOWN = 5.seconds

/** What the device-code endpoint answered. */
internal class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Duration,
    val interval: Duration,
)

/** The account a key or a service status answer names. */
internal class AccountSummary(val username: String?, val name: String?, val plan: String?) {
    /** [name] or [username], whichever is told, else null. */
    val label: String? get() = name ?: username
}

/** A granted device login: the new key, the API base the client talks to, and who it belongs to. */
internal class Granted(val key: String, val apiBase: String, val account: AccountSummary)

/** What polling the device-token endpoint answered. */
internal sealed interface DeviceToken {
    class Done(val granted: Granted) : DeviceToken

    data object Pending : DeviceToken

    data object SlowDown : DeviceToken

    data object Denied : DeviceToken

    data object Expired : DeviceToken

    class Failed(val reason: Text) : DeviceToken
}

/** The `X-Client` header's value, as the service API's convention asks for it. */
internal fun clientHeader(deviceName: String?): String =
    "$CLIENT/$alexandriteVersion".let { if (deviceName == null) it else "$it $deviceName" }

/** The local machine's name, best used as the device login's `deviceName`, null when it cannot be told. */
internal fun defaultDeviceName(): String? = try {
    InetAddress.getLocalHost().hostName.takeIf { it.isNotBlank() }
} catch (e: IOException) {
    null
}

/** Starts a device login at the portal, or null after printing why it could not. */
internal fun requestDeviceCode(cli: Cli, deviceName: String?): DeviceCode? {
    val body = buildJsonObject {
        put("client", CLIENT)
        put("clientVersion", alexandriteVersion)
        deviceName?.let { put("deviceName", it) }
    }
    val fetched = try {
        cli.machine.post("$PLEOCHROIC_PORTAL/api/device/code", "$body")
    } catch (e: IOException) {
        cli.fail(cli.t("pleochroic.unreachable", "reason" to (e.message ?: e)))
        return null
    }
    val answer = parsedObject(fetched.body)
    if (fetched.status != 200 || answer == null) {
        cli.fail(cli.t("pleochroic.device_code_failed", "status" to fetched.status))
        return null
    }
    val deviceCode = answer["deviceCode"].text
    val userCode = answer["userCode"].text
    val verificationUri = answer["verificationUri"].text
    val expiresIn = (answer["expiresIn"] as? JsonPrimitive)?.content?.toIntOrNull()
    val interval = (answer["interval"] as? JsonPrimitive)?.content?.toIntOrNull()
    if (deviceCode == null || userCode == null || verificationUri == null || expiresIn == null || interval == null) {
        cli.fail(cli.t("pleochroic.device_code_malformed"))
        return null
    }
    val complete = answer["verificationUriComplete"].text ?: "$verificationUri?code=$userCode"
    return DeviceCode(deviceCode, userCode, verificationUri, complete, expiresIn.seconds, interval.seconds)
}

/** One poll of the device-token endpoint for [deviceCode]. */
internal fun pollDeviceToken(cli: Cli, deviceCode: String): DeviceToken {
    val body = buildJsonObject { put("deviceCode", deviceCode) }
    val fetched = try {
        cli.machine.post("$PLEOCHROIC_PORTAL/api/device/token", "$body")
    } catch (e: IOException) {
        return DeviceToken.Failed(Text("pleochroic.unreachable", "reason" to (e.message ?: "$e")))
    }
    val answer = parsedObject(fetched.body) ?: return DeviceToken.Failed(
        Text("pleochroic.device_token_malformed", "status" to fetched.status),
    )
    if (fetched.status == 200) {
        val key = answer["key"].text
        val apiBase = answer["apiBase"].text
        if (key == null || apiBase == null) {
            return DeviceToken.Failed(Text("pleochroic.device_token_malformed", "status" to fetched.status))
        }
        return DeviceToken.Done(Granted(key, apiBase, accountOf(answer["account"] as? JsonObject)))
    }
    return when ((answer["error"] as? JsonPrimitive)?.content) {
        "authorization_pending" -> DeviceToken.Pending
        "slow_down" -> DeviceToken.SlowDown
        "access_denied" -> DeviceToken.Denied
        "expired_token" -> DeviceToken.Expired
        else -> DeviceToken.Failed(Text("pleochroic.device_token_malformed", "status" to fetched.status))
    }
}

/**
 * Shows [code]'s user code and link, then polls until the person allows or denies it, or it expires; null after
 * printing why nobody granted it.
 */
internal fun awaitDeviceLogin(cli: Cli, code: DeviceCode): Granted? {
    val ui = cli.ui
    if (ui.rich) {
        ui.say(cli.t("pleochroic.open", "url" to code.verificationUri))
        ui.code(code.userCode)
    } else {
        ui.say(cli.t("pleochroic.open_complete", "url" to code.verificationUriComplete))
    }
    val deadline = System.nanoTime() + code.expiresIn.inWholeNanoseconds
    var interval = code.interval
    val granted = ui.busy({ cli.t("pleochroic.waiting") }) {
        var result: Granted? = null
        var failure: Text? = null
        while (System.nanoTime() < deadline) {
            cli.machine.sleep(interval)
            when (val answer = pollDeviceToken(cli, code.deviceCode)) {
                is DeviceToken.Done -> {
                    result = answer.granted
                    break
                }

                DeviceToken.Pending -> continue

                DeviceToken.SlowDown -> interval += SLOW_DOWN

                DeviceToken.Denied -> {
                    failure = Text("pleochroic.denied")
                    break
                }

                DeviceToken.Expired -> {
                    failure = Text("pleochroic.expired")
                    break
                }

                is DeviceToken.Failed -> {
                    failure = answer.reason
                    break
                }
            }
        }
        result to failure
    }
    val (result, failure) = granted
    if (result != null) return result
    cli.fail(cli.texts(failure ?: Text("pleochroic.timeout")))
    return null
}

/**
 * The account that [GET /v1/service][fetchServiceStatus] or a device login names in [json], best effort: `plan` is
 * read as the `{id, name}` object `GET /v1/service` documents, falling back to a bare string in case a device
 * grant's `account.plan` is one (the API doc shows no worked example of that response's shape).
 */
internal fun accountOf(json: JsonObject?): AccountSummary {
    val plan = json?.get("plan")
    val planLabel = when (plan) {
        is JsonObject -> (plan["name"] ?: plan["id"]).text
        is JsonPrimitive -> plan.content
        else -> null
    }
    return AccountSummary(json?.get("username").text, json?.get("name").text, planLabel)
}

/** `GET /v1/service` at [apiBase] with [key], or null after printing why it could not be read. */
internal fun fetchServiceStatus(cli: Cli, apiBase: String, key: String, deviceName: String? = null): JsonObject? {
    val headers = mapOf("Authorization" to "Bearer $key", "X-Client" to clientHeader(deviceName))
    val fetched = try {
        cli.machine.get("${apiBase.trimEnd('/')}/service", headers)
    } catch (e: IOException) {
        cli.fail(cli.t("pleochroic.unreachable", "reason" to maskedKey(e, key)))
        return null
    }
    if (fetched.status == 401) {
        cli.fail(cli.t("pleochroic.unauthorized"))
        return null
    }
    val answer = parsedObject(fetched.body)
    if (fetched.status !in 200..299 || answer == null) {
        cli.fail(cli.t("pleochroic.service_failed", "status" to fetched.status))
        return null
    }
    return answer
}

/** Whether [status]'s `services.<name>.enabled` is true. */
internal fun JsonObject.entitled(name: String): Boolean =
    (at("services.$name.enabled") as? JsonPrimitive)?.booleanOrNull == true

/** Revokes [key] at [apiBase] (`DELETE /v1/key`); never throws, since signing out forgets the key locally anyway. */
internal fun revokeKey(cli: Cli, apiBase: String, key: String): Boolean {
    val headers = mapOf("Authorization" to "Bearer $key", "X-Client" to clientHeader(null))
    return try {
        cli.machine.delete("${apiBase.trimEnd('/')}/key", headers).status in 200..299
    } catch (e: IOException) {
        false
    }
}

private fun parsedObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (e: IllegalArgumentException) {
    null
}

private fun maskedKey(e: IOException, key: String): String = (e.message ?: "$e").replace(key, "***")
