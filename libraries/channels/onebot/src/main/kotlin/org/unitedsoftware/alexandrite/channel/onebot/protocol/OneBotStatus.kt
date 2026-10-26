package org.unitedsoftware.alexandrite.channel.onebot.protocol

import kotlinx.serialization.Serializable

/**
 * The `status` field of an OneBot response.
 *
 * `ok` carries `retcode` 0, `async` carries 1, and `failed` carries anything else, so an implementation's own
 * code stays readable through [of].
 */
@JvmInline
@Serializable
public value class OneBotStatus(public val value: String) {
    init {
        require(value.isNotEmpty()) { "A status may not be empty." }
    }

    override fun toString(): String = value

    public companion object {
        public val OK: OneBotStatus = OneBotStatus("ok")

        public val ASYNC: OneBotStatus = OneBotStatus("async")

        public val FAILED: OneBotStatus = OneBotStatus("failed")

        /** The values this version knows. */
        public val entries: List<OneBotStatus> = listOf(OK, ASYNC, FAILED)

        /** The value of [value], which keeps a status this version does not know. */
        public fun of(value: String): OneBotStatus = OneBotStatus(value)

        /** The status that [retcode] means. */
        public fun ofRetcode(retcode: Int): OneBotStatus = when (retcode) {
            OneBotRetcode.OK -> OK
            OneBotRetcode.ASYNC -> ASYNC
            else -> FAILED
        }
    }
}

/** The `retcode` values OneBot v11 defines. */
public object OneBotRetcode {
    public const val OK: Int = 0

    public const val ASYNC: Int = 1

    /** Bad request, the WebSocket counterpart of HTTP 400. */
    public const val BAD_REQUEST: Int = 1400

    /** Unauthorized, the counterpart of HTTP 401. */
    public const val UNAUTHORIZED: Int = 1401

    /** Forbidden, the counterpart of HTTP 403. */
    public const val FORBIDDEN: Int = 1403

    /** No such action, the counterpart of HTTP 404. */
    public const val NOT_FOUND: Int = 1404

    /** The counterpart of the HTTP status [status], used where a WebSocket has no status line. */
    public fun ofHttp(status: Int): Int = when (status) {
        400 -> BAD_REQUEST
        401 -> UNAUTHORIZED
        403 -> FORBIDDEN
        404 -> NOT_FOUND
        else -> BAD_REQUEST
    }
}

/** What an OneBot response was about, used to report and to classify failures. */
public enum class OneBotResponseKind {
    /** A synchronous success. */
    OK,

    /** Work accepted for later, whose outcome the client cannot learn. */
    ASYNC,

    /** The implementation refused the call, see its `retcode`. */
    FAILED,
}
