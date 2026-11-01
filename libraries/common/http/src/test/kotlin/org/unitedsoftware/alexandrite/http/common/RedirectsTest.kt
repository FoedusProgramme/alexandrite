package org.unitedsoftware.alexandrite.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedirectsTest {
    @Test
    fun `a location resolves against the URL it answered`() {
        assertEquals("https://a.example/b/d", redirectTarget("https://a.example/b/c", "d"))
        assertEquals("https://b.example/e", redirectTarget("https://a.example/b/c", "https://b.example/e"))
        assertEquals("https://a.example/f", redirectTarget("http://a.example/", "https://a.example/f"))
    }

    @Test
    fun `a location that leaves https for http or is no address leads nowhere`() {
        assertNull(redirectTarget("https://a.example/", "http://a.example/"))
        assertNull(redirectTarget("https://a.example/", "http://exa mple/"))
    }
}
