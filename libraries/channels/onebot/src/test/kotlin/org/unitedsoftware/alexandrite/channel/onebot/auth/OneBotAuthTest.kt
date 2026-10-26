package org.unitedsoftware.alexandrite.channel.onebot.auth

import org.unitedsoftware.alexandrite.sdk.config.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneBotAuthTest {
    private val token = Value.of(Secret("s3cr3t-token"))
    private val secret = Value.of(Secret("signing-key"))

    @Test
    fun `a token is presented as a bearer header and read back from either form`() {
        assertEquals("Bearer s3cr3t-token", OneBotAuth.bearer(token))
        assertEquals("s3cr3t-token", OneBotAuth.tokenOf("Bearer s3cr3t-token"))
        assertEquals("s3cr3t-token", OneBotAuth.tokenOf("bearer s3cr3t-token"))
        assertEquals("s3cr3t-token", OneBotAuth.tokenOf("s3cr3t-token"))
        assertEquals(null, OneBotAuth.tokenOf(""))
        assertEquals(null, OneBotAuth.tokenOf(null))
        assertTrue(OneBotAuth.matchesToken(token, "s3cr3t-token"))
        assertFalse(OneBotAuth.matchesToken(token, "s3cr3t-toke"))
    }

    @Test
    fun `a report is signed with hmac sha1 over its body`() {
        val signature = OneBotAuth.signature(secret, """{"post_type":"meta_event"}""")
        assertTrue(signature.startsWith(OneBotAuth.SIGNATURE_PREFIX))
        assertEquals(OneBotAuth.SIGNATURE_PREFIX.length + 40, signature.length)
        assertTrue(OneBotAuth.matchesSignature(secret, """{"post_type":"meta_event"}""", signature))
        assertTrue(OneBotAuth.matchesSignature(secret, """{"post_type":"meta_event"}""", signature.uppercase()))
        assertFalse(OneBotAuth.matchesSignature(secret, """{"post_type":"meta_event","x":1}""", signature))
        assertFalse(OneBotAuth.matchesSignature(secret, """{"post_type":"meta_event"}""", "sha1=00"))
        assertFalse(OneBotAuth.matchesSignature(secret, """{"post_type":"meta_event"}""", null))
    }

    @Test
    fun `the documented example signs its body the way the standard spells it`() {
        val signature = OneBotAuth.signature(Value.of(Secret("some-secret")), "the body")
        assertEquals("sha1=" + hex(hmac("some-secret", "the body")), signature)
    }

    @Test
    fun `a token is carried as a query parameter as well`() {
        assertEquals(
            "http://host/send_msg?access_token=a%20b",
            OneBotAuth.withToken("http://host/send_msg", Value.of(Secret("a b"))),
        )
        assertEquals(
            "http://host/x?a=1&access_token=t",
            OneBotAuth.withToken("http://host/x?a=1", Value.of(Secret("t"))),
        )
        assertEquals("http://host/x", OneBotAuth.withToken("http://host/x", null))
    }

    @Test
    fun `neither a token nor a secret reaches a message`() {
        val text = "call failed with Bearer s3cr3t-token and signing-key"
        val redacted = OneBotAuth.redact(text, listOf(token, secret))
        assertFalse(redacted.contains("s3cr3t-token"))
        assertFalse(redacted.contains("signing-key"))
        assertTrue(redacted.contains("***"))
        assertEquals(text, OneBotAuth.redact(text, emptyList()))
        assertFalse(Secret("s3cr3t-token").toString().contains("s3cr3t-token"))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hmac(key: String, message: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(), "HmacSHA1"))
        return mac.doFinal(message.toByteArray())
    }
}
