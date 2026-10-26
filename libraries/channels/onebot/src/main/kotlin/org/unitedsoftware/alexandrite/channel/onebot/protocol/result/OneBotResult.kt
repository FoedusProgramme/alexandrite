package org.unitedsoftware.alexandrite.channel.onebot.protocol.result

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.channel.onebot.protocol.OneBotRetcode
import org.unitedsoftware.alexandrite.channel.onebot.protocol.OneBotStatus

/**
 * What came of one API call.
 *
 * The kinds stay apart on purpose: a transport that never reached the implementation, an implementation that
 * refused the call, and a call that succeeded are different facts, and only [data] needs the answer of the call.
 * Every kind keeps the response [raw], so a field an implementation added is read from there.
 */
public sealed interface OneBotResult<out T> {
    /** The `status` the response reported, or the one the transport made of its failure. */
    public val status: OneBotStatus

    /** The `retcode` the response reported, or one this module chose for a failure it saw itself. */
    public val retcode: Int

    /** The `echo` the response carried back, null when it carried none. */
    public val echo: JsonElement?

    /** The whole response object, empty when no response came. */
    public val raw: JsonObject

    /** The data of a successful call. */
    public data class Ok<T>(
        public val data: T,
        override val retcode: Int = OneBotRetcode.OK,
        override val echo: JsonElement? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : OneBotResult<T> {
        override val status: OneBotStatus get() = OneBotStatus.OK
    }

    /** A call the implementation accepted for later, whose outcome the client cannot learn. */
    public data class Async(
        override val retcode: Int = OneBotRetcode.ASYNC,
        override val echo: JsonElement? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : OneBotResult<Nothing> {
        override val status: OneBotStatus get() = OneBotStatus.ASYNC
    }

    /** A call the implementation refused, carrying its `retcode`. */
    public data class Failed(
        override val retcode: Int,
        /** The `message` an implementation may add, null when it added none. */
        public val message: String? = null,
        override val echo: JsonElement? = null,
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : OneBotResult<Nothing> {
        override val status: OneBotStatus get() = OneBotStatus.FAILED
    }

    /** A call that never reached the implementation, or whose answer was lost on the way. */
    public data class Unreachable(
        public val failure: OneBotFailure,
        /** The answer of the implementation, null when the request never got one. */
        override val raw: JsonObject = JsonObject(emptyMap()),
        override val echo: JsonElement? = null,
    ) : OneBotResult<Nothing> {
        override val status: OneBotStatus get() = OneBotStatus.FAILED

        override val retcode: Int
            get() = when (failure.kind) {
                OneBotFailureKind.AUTHENTICATION -> OneBotRetcode.UNAUTHORIZED
                OneBotFailureKind.UNKNOWN_ACTION -> OneBotRetcode.NOT_FOUND
                else -> OneBotRetcode.BAD_REQUEST
            }
    }

    /** An answer this version cannot read, with what the implementation sent. */
    public data class Malformed(
        public val detail: String,
        override val echo: JsonElement? = null,
        /** The answer as it arrived, so that a caller can read the field this version misread. */
        override val raw: JsonObject = JsonObject(emptyMap()),
    ) : OneBotResult<Nothing> {
        override val status: OneBotStatus get() = OneBotStatus.FAILED

        override val retcode: Int get() = OneBotRetcode.BAD_REQUEST
    }
}

/** Why an [OneBotResult.Unreachable] call failed. */
public enum class OneBotFailureKind {
    /** The endpoint could not be reached, or the connection broke. */
    CONNECTION,

    /** The implementation was too slow for the call's timeouts. */
    TIMEOUT,

    /** The token was missing or wrong. */
    AUTHENTICATION,

    /** The implementation answered with an HTTP status the call cannot use. */
    HTTP_STATUS,

    /** The implementation closed the connection, or the client did. */
    CLOSED,

    /** The call spoke about an action the registry does not know. */
    UNKNOWN_ACTION,
}

/** One failure of a call that never produced an answer. */
public data class OneBotFailure(
    public val kind: OneBotFailureKind,
    /** A description that names no token or secret. */
    public val message: String,
    /** The HTTP status of the answer, null when none came. */
    public val httpStatus: Int? = null,
) {
    init {
        require(message.isNotBlank()) { "A failure needs a message." }
    }

    /** Whether sending the same call again may succeed. */
    public val retryable: Boolean
        get() = kind == OneBotFailureKind.CONNECTION || kind == OneBotFailureKind.TIMEOUT ||
            (httpStatus != null && httpStatus in 500..599)
}

/** The `message` field of a response, null when it holds none. */
public fun JsonObject.messageOrNull(): String? =
    (this["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** The `retcode` the response body of [raw] holds, null when it holds none. */
public fun retcodeOf(raw: JsonObject): Int? = (raw["retcode"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

/** The `status` of the response body of [raw], null when it holds none. */
public fun statusOf(raw: JsonObject): OneBotStatus? =
    (raw["status"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let(OneBotStatus::of)
