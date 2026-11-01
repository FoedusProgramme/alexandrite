package org.unitedsoftware.alexandrite.automation.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class WhenTest {
    // Once.

    @Test
    fun `once is due at its local time and never again`() {
        val once = When.Once(local("2026-10-18T12:00"))
        assertEquals(instant("2026-10-18T10:00:00Z"), once.next(ORIGIN.minusSeconds(1), BERLIN))
        assertNull(once.next(instant("2026-10-18T10:00:00Z"), BERLIN))
        assertNull(once.next(ORIGIN.plusSeconds(3600), BERLIN))
    }

    // Every.

    @Test
    fun `every starts at from and steps strictly after`() {
        val every = When.Every(15.minutes, ORIGIN)
        assertEquals(ORIGIN, every.next(ORIGIN.minusSeconds(3600), UTC))
        assertEquals(ORIGIN.plusSeconds(900), every.next(ORIGIN, UTC))
        assertEquals(ORIGIN.plusSeconds(900), every.next(ORIGIN.plusSeconds(1), UTC))
        assertEquals(ORIGIN.plusSeconds(1800), every.next(ORIGIN.plusSeconds(900), UTC))
    }

    @Test
    fun `every refuses an interval that is not positive`() {
        for (interval in listOf(Duration.ZERO, (-1).seconds)) {
            assertFailsWith<IllegalArgumentException> { When.Every(interval, ORIGIN) }
        }
    }
}
