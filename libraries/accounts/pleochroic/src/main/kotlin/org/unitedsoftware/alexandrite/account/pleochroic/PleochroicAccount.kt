package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import java.io.IOException
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a Pleochroic account's device login starts; fixed, never a self-hosted `standalone-service`'s address. */
public const val PLEOCHROIC_PORTAL_URL: String = "https://portal.pleochroic.co.uk"

/** The Pleochroic gateway's model relay and service API, also the default of a self-hosted `standalone-service`. */
public const val PLEOCHROIC_API_BASE: String = "https://api.pleochroic.co.uk/v1"

/** How much a poll's interval grows once the portal asks to slow down (`docs/API.md` "设备登录"). */
public val PLEOCHROIC_SLOW_DOWN: Duration = 5.seconds

/** A call to the portal or the gateway/`standalone-service` answered with an error, never holding a key. */
public class PleochroicApiException(public val code: String, message: String) : IOException(message)

/** What `POST /api/device/code` answered (`docs/API.md` "设备登录"). */
public class DeviceCode(
    public val deviceCode: String,
    public val userCode: String,
    public val verificationUri: String,
    public val verificationUriComplete: String,
    public val expiresIn: Duration,
    public val interval: Duration,
)

/** A plan as `GET /v1/service` or a device grant names it: `{id, name}`, or (older device grants) a bare string. */
public class PlanSummary(public val id: String?, public val name: String?) {
    public val label: String? get() = name ?: id
}

/** The account a device grant or `GET /v1/service` names. */
public class AccountSummary(
    public val id: String?,
    public val username: String?,
    public val name: String?,
    public val plan: PlanSummary?,
) {
    /** [name] or [username], whichever is told, else null. */
    public val label: String? get() = name ?: username
}

/** A granted device login: the new key, the API base the client talks to, and who it belongs to. */
public class Granted(public val key: String, public val apiBase: String, public val account: AccountSummary)

/** What one poll of `POST /api/device/token` answered. */
public sealed interface DevicePoll {
    /** The person approved the login. */
    public class Done(public val granted: Granted) : DevicePoll

    /** Not decided yet; poll again after [DeviceCode.interval]. */
    public data object Pending : DevicePoll

    /** Polling too fast; add [PLEOCHROIC_SLOW_DOWN] to the interval and poll again. */
    public data object SlowDown : DevicePoll

    /** The person denied the login. */
    public data object Denied : DevicePoll

    /** [DeviceCode.expiresIn] passed before the login was decided. */
    public data object Expired : DevicePoll
}

/** Starts a device login at [portalBaseUrl]: `POST /api/device/code` (`docs/API.md` "设备登录"). */
public suspend fun beginDeviceLogin(
    http: HttpTransport,
    client: String,
    clientVersion: String,
    deviceName: String? = null,
    portalBaseUrl: String = PLEOCHROIC_PORTAL_URL,
): DeviceCode {
    val body = buildJsonObject {
        put("client", client)
        put("clientVersion", clientVersion)
        deviceName?.let { put("deviceName", it) }
    }
    val json = call(http, "POST", "$portalBaseUrl/api/device/code", body = body.toString())
    val userCode = json.need("userCode")
    val verificationUri = json.need("verificationUri")
    return DeviceCode(
        deviceCode = json.need("deviceCode"),
        userCode = userCode,
        verificationUri = verificationUri,
        verificationUriComplete = json.string("verificationUriComplete") ?: "$verificationUri?code=$userCode",
        expiresIn = (json.number("expiresIn") ?: 600).seconds,
        interval = (json.number("interval") ?: 5).seconds,
    )
}

/**
 * One poll of `POST /api/device/token`, answered at once: the caller waits between calls and decides when to give
 * up (`docs/API.md` "设备登录"). Never blocks on its own, so a terminal or GUI front end drives its own countdown.
 */
public suspend fun pollDeviceLogin(
    http: HttpTransport,
    deviceCode: String,
    portalBaseUrl: String = PLEOCHROIC_PORTAL_URL,
): DevicePoll {
    val body = buildJsonObject { put("deviceCode", deviceCode) }
    val answer = try {
        call(http, "POST", "$portalBaseUrl/api/device/token", body = body.toString())
    } catch (e: PleochroicApiException) {
        return when (e.code) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "access_denied" -> DevicePoll.Denied
            "expired_token" -> DevicePoll.Expired
            else -> throw e
        }
    }
    val granted = Granted(
        key = answer.need("key"),
        apiBase = answer.need("apiBase"),
        account = accountOf(answer.obj("account")),
    )
    return DevicePoll.Done(granted)
}

/** `GET /v1/service`: the account, its scopes and what it is entitled to (`docs/API.md` "账户与权益"). */
public suspend fun fetchServiceStatus(
    http: HttpTransport,
    apiBase: String,
    apiKey: String,
    clientHeader: String? = null,
): JsonObject {
    val headers = buildMap {
        put("authorization", "Bearer $apiKey")
        clientHeader?.let { put("x-client", it) }
    }
    return call(http, "GET", "${apiBase.trimEnd('/')}/service", headers = headers, secrets = listOf(apiKey))
}

/** The account that [service] (`GET /v1/service`'s answer) names. */
public fun serviceAccount(service: JsonObject): AccountSummary = accountOf(service.obj("account"))

/** Whether [service]'s `services.<name>.enabled` is true (`docs/API.md` "账户与权益"). */
public fun JsonObject.entitled(name: String): Boolean =
    (obj("services")?.obj(name)?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true

/** `DELETE /v1/key`: revokes [apiKey] itself; a 404 (already revoked) is not an error. */
public suspend fun revokeKey(http: HttpTransport, apiBase: String, apiKey: String) {
    val headers = mapOf("authorization" to "Bearer $apiKey")
    val call = HttpCall("DELETE", URI("${apiBase.trimEnd('/')}/key"), headers, secrets = listOf(apiKey))
    try {
        http.open(call) { it.text(ERROR_BODY_LIMIT) }
    } catch (e: HttpException) {
        if (e.status != 404) throw toPleochroicException(e)
    }
}

/** The account that a device grant or `GET /v1/service` names in [json], best effort. */
internal fun accountOf(json: JsonObject?): AccountSummary {
    val plan = json?.get("plan")
    val planSummary = when (plan) {
        is JsonObject -> PlanSummary(plan.string("id"), plan.string("name"))
        is JsonPrimitive -> plan.content.takeIf { plan.isString }?.let { PlanSummary(null, it) }
        else -> null
    }
    return AccountSummary(json?.string("id"), json?.string("username"), json?.string("name"), planSummary)
}

private const val ERROR_BODY_LIMIT = 64 * 1024

private val JSON = Json { ignoreUnknownKeys = true }

private suspend fun call(
    http: HttpTransport,
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    secrets: List<String> = emptyList(),
): JsonObject {
    val all = if (body != null) headers + ("content-type" to "application/json") else headers
    val call = HttpCall(method, URI(url), all, body, secrets)
    val text = try {
        http.send(call, HttpTransport.DEFAULT_LIMIT)
    } catch (e: HttpException) {
        throw toPleochroicException(e)
    }
    return parseObject(text) ?: throw PleochroicApiException("protocol", "$method $url answered with no JSON object.")
}

/** Reads [code] (an `{error:"code"}` or `{error:{code,message}}` body) from [e], never its masked key. */
private fun toPleochroicException(e: HttpException): PleochroicApiException {
    val error = e.body?.let(::parseObject)?.get("error")
    val code = when (error) {
        is JsonPrimitive -> error.content
        is JsonObject -> error.string("code")
        else -> null
    } ?: "http_${e.status ?: 0}"
    val message = (error as? JsonObject)?.string("message") ?: e.message ?: code
    return PleochroicApiException(code, message)
}

private fun parseObject(text: String): JsonObject? = try {
    JSON.parseToJsonElement(text) as? JsonObject
} catch (e: SerializationException) {
    null
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.need(name: String): String =
    string(name) ?: throw PleochroicApiException("protocol", "The answer is missing \"$name\".")
