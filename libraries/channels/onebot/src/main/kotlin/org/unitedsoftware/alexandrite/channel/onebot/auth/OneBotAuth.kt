package org.unitedsoftware.alexandrite.channel.onebot.auth

import org.unitedsoftware.alexandrite.sdk.config.Secret
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The authentication of the OneBot standard.
 *
 * A call carries its token either as a bearer header or as a query parameter, and a report carries an HMAC-SHA1 of
 * its body under the shared secret. Neither the token nor the secret ever reaches a message: a transport masks what
 * it logs with [redact], and the values only leave [Value] where a request or a comparison needs them.
 */
public object OneBotAuth {
    /** The header a call carries its token in. */
    public const val AUTHORIZATION: String = "Authorization"

    /** The query parameter a call may carry its token in instead. */
    public const val TOKEN_QUERY: String = "access_token"

    /** The header a report carries its signature in. */
    public const val SIGNATURE: String = "X-Signature"

    /** The header a report names the account it is about in. */
    public const val SELF_ID: String = "X-Self-ID"

    /** The header a reverse WebSocket connection names its role in. */
    public const val CLIENT_ROLE: String = "X-Client-Role"

    /** The prefix of a signature value. */
    public const val SIGNATURE_PREFIX: String = "sha1="

    /** The value of the [AUTHORIZATION] header for [token]. */
    public fun bearer(token: Value): String = "Bearer ${token.reveal()}"

    /** The value of the [SIGNATURE] header for [body] under [secret]. */
    public fun signature(secret: Value, body: String): String =
        SIGNATURE_PREFIX + hmacSha1(secret.reveal().toByteArray(Charsets.UTF_8), body.toByteArray(Charsets.UTF_8))

    /** Whether [signature] is the [SIGNATURE] header of [body] under [secret], whatever its case. */
    public fun matchesSignature(secret: Value, body: String, signature: String?): Boolean {
        val value = signature?.trim()?.lowercase() ?: return false
        val presented = value.removePrefix(SIGNATURE_PREFIX)
        val expected = hmacSha1(secret.reveal().toByteArray(Charsets.UTF_8), body.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(
            expected.lowercase().toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )
    }

    /** The token of [authorization], which may be a bearer header or the token itself, null when it holds none. */
    public fun tokenOf(authorization: String?): String? {
        val value = authorization?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bearer = value.substringBefore(' ').lowercase()
        return if (bearer == "bearer") value.substringAfter(' ').trim().ifEmpty { null } else value
    }

    /** Whether [token] matches the token [presented] carries. */
    public fun matchesToken(token: Value, presented: String?): Boolean {
        val expected = token.reveal()
        return presented != null && MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )
    }

    /** [uri] with the token of [token] as its query parameter, unchanged when this instance has none. */
    public fun withToken(uri: String, token: Value?): String {
        if (token == null) return uri
        val separator = if ('?' in uri) '&' else '?'
        return "$uri$separator$TOKEN_QUERY=${urlEncode(token.reveal())}"
    }

    /**
     * [text] with every value of [values] replaced, so that a message carries no token and no secret.
     *
     * Every value that is not empty is replaced, whatever its length: an access token and a signing key have no
     * minimum length, and a short one is no less a secret than a long one.
     */
    public fun redact(text: String, values: Collection<Value>): String = values.asSequence()
        .map { it.reveal() }
        .filter { it.isNotEmpty() }
        .sortedByDescending { it.length }
        .fold(text) { masked, secret -> masked.replace(secret, MASKED) }

    private const val MASKED = "***"

    private fun hmacSha1(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(message).joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(text: String): String = java.net.URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
}

/** One value of the config a transport must keep out of its logs, such as an access token or a signing key. */
@JvmInline
public value class Value(private val secret: Secret) {
    /** The value itself, for the one place that needs it. */
    public fun reveal(): String = secret.reveal()

    override fun toString(): String = secret.toString()

    public companion object {
        /** A value that masks [secret]. */
        public fun of(secret: Secret): Value = Value(secret)

        /** The value of [secret], null when the instance has none. */
        public fun orNull(secret: Secret?): Value? = secret?.let(::Value)

        /** A value that masks [text], for a caller that holds the string of a token itself. */
        public fun secretOf(text: String): Value = Value(Secret(text))
    }
}
