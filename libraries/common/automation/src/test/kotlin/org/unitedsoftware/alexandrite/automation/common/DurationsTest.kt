package org.unitedsoftware.alexandrite.automation.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class DurationsTest {
    @Test
    fun `durations are written in days, hours and minutes`() {
        assertEquals(
            listOf(30.minutes, 2.hours, 1.days, 90.minutes, 100.minutes, 90.minutes),
            listOf("30m", "2h", "1d", "1h30m", "100m", " 1H 30M ").map(::parseDuration),
        )
    }

    @Test
    fun `other durations are refused with the reason`() {
        val cases = mapOf(
            "" to "'' is no duration",
            "30" to "'30' is no duration",
            "half an hour" to "'half an hour' is no duration",
            "90s" to "'90s' counts seconds",
            "0m" to "'0m' is no positive duration",
            "3661d" to "'3661d' is longer than 3660 days.",
            "9999999d" to "'9999999d' is too long a duration.",
        )
        for ((text, reason) in cases) {
            val error = assertFailsWith<IllegalArgumentException>(text) { parseDuration(text) }
            assertEquals(true, error.message?.startsWith(reason), error.message)
        }
    }
}
