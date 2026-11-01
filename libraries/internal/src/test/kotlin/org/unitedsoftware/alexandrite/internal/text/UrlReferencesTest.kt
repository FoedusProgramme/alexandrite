package org.unitedsoftware.alexandrite.internal.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlReferencesTest {
    @Test
    fun `references resolve as the examples of RFC 3986 do`() {
        val base = "http://a/b/c/d;p?q"
        val examples = mapOf(
            "g:h" to "g:h", "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g", "//g" to "http://g", "?y" to "http://a/b/c/d;p?y", "g?y" to "http://a/b/c/g?y",
            "#s" to "http://a/b/c/d;p?q#s", "g#s" to "http://a/b/c/g#s", "g?y#s" to "http://a/b/c/g?y#s",
            ";x" to "http://a/b/c/;x", "g;x" to "http://a/b/c/g;x", "g;x?y#s" to "http://a/b/c/g;x?y#s",
            "" to "http://a/b/c/d;p?q", "." to "http://a/b/c/", "./" to "http://a/b/c/", ".." to "http://a/b/",
            "../" to "http://a/b/", "../g" to "http://a/b/g", "../.." to "http://a/", "../../" to "http://a/",
            "../../g" to "http://a/g", "../../../g" to "http://a/g", "../../../../g" to "http://a/g",
            "/./g" to "http://a/g", "/../g" to "http://a/g", "g." to "http://a/b/c/g.", ".g" to "http://a/b/c/.g",
            "g.." to "http://a/b/c/g..", "..g" to "http://a/b/c/..g", "./../g" to "http://a/b/g",
            "./g/." to "http://a/b/c/g/", "g/./h" to "http://a/b/c/g/h", "g/../h" to "http://a/b/c/h",
            "g;x=1/./y" to "http://a/b/c/g;x=1/y", "g;x=1/../y" to "http://a/b/c/y",
            "g?y/./x" to "http://a/b/c/g?y/./x", "g?y/../x" to "http://a/b/c/g?y/../x",
            "g#s/./x" to "http://a/b/c/g#s/./x",
            "g#s/../x" to "http://a/b/c/g#s/../x", "http:g" to "http:g",
        )
        for ((reference, target) in examples) assertEquals(target, resolved(base, reference), reference)
    }

    @Test
    fun `a URL with characters RFC 3986 does not allow resolves as well`() {
        assertEquals("https://example.org/a b/ü?q=x y", resolved("https://example.org/a b/c", "ü?q=x y"))
        assertEquals("https://example.org/", resolved("https://example.org", "/"))
        assertEquals("https://example.org/x", resolved("https://example.org", "x"))
    }

    @Test
    fun `a URL is absolute when it starts with a scheme`() {
        assertTrue(isAbsoluteUrl("mailto:a@example.org"))
        assertTrue(isAbsoluteUrl("HTTPS://example.org"))
        assertFalse(isAbsoluteUrl("//example.org/x"))
        assertFalse(isAbsoluteUrl("1http://x"))
        assertFalse(isAbsoluteUrl("relative/path"))
    }
}
