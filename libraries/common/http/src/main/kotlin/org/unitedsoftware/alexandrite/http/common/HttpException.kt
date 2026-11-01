package org.unitedsoftware.alexandrite.http.common

import org.unitedsoftware.alexandrite.sdk.net.HttpHeaders
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** An HTTP call that failed. */
public class HttpException(
    public val kind: HttpFailureKind,
    message: String,
    /** The response's status, null when no response came. */
    public val status: Int? = null,
    /** How long the server asked to wait, null when it did not say. */
    public val retryAfter: Duration? = null,
    /** The server's id of the request. */
    public val requestId: String? = null,
    /** The start of the response body, its secrets masked, null when there is none. */
    public val body: String? = null,
    /** Whether the response's body had started when the call failed. */
    public val afterResponse: Boolean = false,
    cause: Throwable? = null,
    /** The response's headers, null when no response came. */
    public val headers: HttpHeaders? = null,
) : IOException(message, cause)

/** What kind of failure an [HttpException] is, and whether the same request may succeed when sent again. */
public enum class HttpFailureKind(public val retryable: Boolean) {
    AUTHENTICATION(false),
    PERMISSION_DENIED(false),
    QUOTA_EXHAUSTED(false),
    NOT_FOUND(false),
    REQUEST_TOO_LARGE(false),
    INVALID_REQUEST(false),
    RATE_LIMITED(true),
    OVERLOADED(true),
    SERVER_ERROR(true),

    /** A server too slow for the call's timeouts or a 408 / 504 status. */
    TIMEOUT(true),

    /** A server that cannot be reached or a connection that breaks. */
    CONNECTION(true),

    /** A response that breaks HTTP or what the call expects, such as a redirect. */
    PROTOCOL(false),
    ;

    public companion object {
        /** The kind of a response with [status], which is no 2xx status. */
        public fun of(status: Int): HttpFailureKind = when (status) {
            401 -> AUTHENTICATION
            402 -> QUOTA_EXHAUSTED
            403 -> PERMISSION_DENIED
            404 -> NOT_FOUND
            408, 504 -> TIMEOUT
            409 -> SERVER_ERROR
            413 -> REQUEST_TOO_LARGE
            429 -> RATE_LIMITED
            503, 529 -> OVERLOADED
            in 400..499 -> INVALID_REQUEST
            in 500..599 -> SERVER_ERROR
            else -> PROTOCOL
        }
    }
}

/** The wait that [headers] ask for at [now] in `retry-after-ms` or `retry-after`, null when they ask for none. */
public fun retryAfter(headers: HttpHeaders, now: Instant): Duration? {
    headers["retry-after-ms"]?.trim()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 }
        ?.let { return it.milliseconds }
    val value = headers["retry-after"]?.trim() ?: return null
    value.toDoubleOrNull()?.let { return if (it.isFinite() && it >= 0) it.seconds else null }
    return try {
        val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        java.time.Duration.between(now, at).coerceAtLeast(java.time.Duration.ZERO).toKotlinDuration()
    } catch (e: DateTimeParseException) {
        null
    }
}

/** The server's id of the request in [headers], null when they hold none. */
public fun requestId(headers: HttpHeaders): String? = REQUEST_ID_HEADERS.firstNotNullOfOrNull { name ->
    headers[name]?.takeIf { it.isNotBlank() }
}

private val REQUEST_ID_HEADERS = listOf("x-request-id", "request-id", "x-amzn-requestid", "x-amz-request-id")
