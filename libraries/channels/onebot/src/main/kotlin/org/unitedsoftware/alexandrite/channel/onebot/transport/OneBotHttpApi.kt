package org.unitedsoftware.alexandrite.channel.onebot.transport

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.auth.OneBotAuth
import org.unitedsoftware.alexandrite.channel.onebot.auth.Value
import org.unitedsoftware.alexandrite.channel.onebot.protocol.OneBotRetcode
import org.unitedsoftware.alexandrite.channel.onebot.protocol.OneBotStatus
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotFailure
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotFailureKind
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.internal.http.HttpCall
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpFailureKind
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

/**
 * The API of an implementation that serves it over HTTP.
 *
 * One call is one request to `<endpoint>/<action>` with the token as a bearer header, and the answer is the JSON
 * object of the standard. The transport of `internal` sends it, so this class only says what a call is and what its
 * answer means.
 */
public class OneBotHttpApi(
    private val endpoint: String,
    private val token: Value?,
    connectTimeoutMillis: Long,
    firstByteTimeoutMillis: Long,
    idleTimeoutMillis: Long,
) : AutoCloseable {
    private val masked: List<Value> = listOfNotNull(token?.reveal()?.let(Value::secretOf))
    private val transport = HttpTransport(
        HttpTimeouts(
            connect = connectTimeoutMillis.milliseconds,
            firstByte = firstByteTimeoutMillis.milliseconds,
            idle = idleTimeoutMillis.milliseconds,
        ),
    )

    /** Sends one call, with [action] already carrying its suffix. */
    public suspend fun call(action: String, params: JsonObject): OneBotResult<JsonElement> {
        val call = HttpCall(
            method = "POST",
            // The token goes in both places the standard allows: a call is accepted by an implementation that reads
            // the header and by one that only reads the query, and sending the same token twice costs nothing.
            uri = URI.create(OneBotAuth.withToken("${endpoint.trimEnd('/')}/$action", token)),
            headers = buildMap {
                put("Content-Type", "application/json")
                token?.let { put(OneBotAuth.AUTHORIZATION, OneBotAuth.bearer(it)) }
            },
            body = OneBotWire.encodeToString(JsonObject.serializer(), params),
            secrets = masked.map(Value::reveal),
        )
        val answer = try {
            OneBotWire.decodeFromString(JsonObject.serializer(), transport.send(call))
        } catch (e: HttpException) {
            return failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return unreachable(OneBotFailureKind.CONNECTION, call.mask("$call was not answered: ${e.message}"))
        }
        return resultOf(answer, call)
    }

    override fun close() {
        transport.close()
    }

    private fun failure(error: HttpException): OneBotResult.Unreachable {
        val kind = when (error.kind) {
            HttpFailureKind.AUTHENTICATION, HttpFailureKind.PERMISSION_DENIED -> OneBotFailureKind.AUTHENTICATION
            HttpFailureKind.TIMEOUT -> OneBotFailureKind.TIMEOUT
            HttpFailureKind.CONNECTION -> OneBotFailureKind.CONNECTION
            else -> OneBotFailureKind.HTTP_STATUS
        }
        return unreachable(kind, error.message ?: "the call failed", error.status)
    }

    private fun unreachable(
        kind: OneBotFailureKind,
        message: String,
        httpStatus: Int? = null,
    ): OneBotResult.Unreachable = OneBotResult.Unreachable(
        OneBotFailure(kind, OneBotAuth.redact(message, masked), httpStatus),
    )

    private fun resultOf(answer: JsonObject, call: HttpCall): OneBotResult<JsonElement> {
        val status = answer.status()
        val retcode = answer.retcode()
        val echo = answer["echo"]?.takeUnless { it is JsonNull }
        return when {
            status == OneBotStatus.ASYNC -> OneBotResult.Async(
                retcode = retcode ?: OneBotRetcode.ASYNC,
                echo = echo,
                raw = answer,
            )

            status == OneBotStatus.OK && (retcode == null || retcode == OneBotRetcode.OK) -> OneBotResult.Ok(
                data = answer.data(),
                echo = echo,
                raw = answer,
            )

            status == null -> OneBotResult.Malformed(
                "the answer of $call names no status: ${answer.keys.sorted()}",
                echo,
                answer,
            )

            else -> OneBotResult.Failed(
                retcode = retcode ?: OneBotRetcode.BAD_REQUEST,
                message = answer.message(),
                echo = echo,
                raw = answer,
            )
        }
    }
}

/**
 * Classifies the answer of one call that came back over a socket, as a call over HTTP classifies its own.
 *
 * A socket carries the same envelope as an HTTP answer, and reading it as data made a `failed` answer an `Ok` whose
 * data was the envelope, which a caller read as a delivery that happened. The status is what says whether a call
 * succeeded, whichever transport carried it.
 */
internal fun envelopeResult(answer: JsonObject): OneBotResult<JsonElement> {
    val status = answer.status()
    val retcode = answer.retcode()
    val echo = answer["echo"]?.takeUnless { it is JsonNull }
    return when {
        status == OneBotStatus.ASYNC -> OneBotResult.Async(
            retcode = retcode ?: OneBotRetcode.ASYNC,
            echo = echo,
            raw = answer,
        )

        status == OneBotStatus.OK && (retcode == null || retcode == OneBotRetcode.OK) -> OneBotResult.Ok(
            data = answer.data(),
            echo = echo,
            raw = answer,
        )

        status == null -> OneBotResult.Malformed(
            "an answer over a socket names no status: ${answer.keys.sorted()}",
            echo,
            answer,
        )

        else -> OneBotResult.Failed(
            retcode = retcode ?: OneBotRetcode.BAD_REQUEST,
            message = answer.message(),
            echo = echo,
            raw = answer,
        )
    }
}

/** The `status` of this answer, null when it carries none. */
internal fun JsonObject.status(): OneBotStatus? =
    (this["status"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let(OneBotStatus::of)

/** The `retcode` of this answer, null when it carries none. */
internal fun JsonObject.retcode(): Int? = (this["retcode"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/**
 * The `data` of this answer as it arrived.
 *
 * The standard lets an action answer with an object, with an array or with nothing, and a list of friends or of groups
 * is one of the arrays, so a reader that took the object shape alone reported every such answer as malformed.
 */
internal fun JsonObject.data(): JsonElement = this["data"] ?: JsonNull

/** The `message` or `wording` of this answer, null when it carries neither. */
internal fun JsonObject.message(): String? =
    (this["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        ?: (this["wording"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** The request of one call over a socket, as the standard spells it. */
internal fun requestOf(action: String, params: JsonObject, echo: String): JsonObject = buildJsonObject {
    put("action", action)
    put("params", params)
    put("echo", echo)
}
