package org.unitedsoftware.alexandrite.internal.hash

import java.io.InputStream
import java.security.MessageDigest

public fun sha256(bytes: ByteArray): ByteArray = sha256Digest().digest(bytes)

/** The SHA-256 of [bytes] in lowercase hex. */
public fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

/** The SHA-256 of [text]'s UTF-8 bytes in lowercase hex. */
public fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/** The SHA-256 of what is left of [input] in lowercase hex, read without holding it. */
public fun sha256Hex(input: InputStream): String {
    val digest = sha256Digest()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return hex(digest.digest())
}

public fun sha256Digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

/** [bytes] in lowercase hex. */
public fun hex(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 0xff
        chars[index * 2] = HEX_DIGITS[value ushr 4]
        chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(chars)
}

private const val HEX_DIGITS = "0123456789abcdef"

private const val BUFFER_BYTES = 64 * 1024
