package org.unitedsoftware.alexandrite.automation.common

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CronExpressionTest {
    /** The next [count] firings of [expression] after the local time [after], as local times in [zone]. */
    private fun series(expression: String, after: String, count: Int = 1, zone: ZoneId = UTC): List<String> {
        val parsed = cron(expression)
        var cursor = local(after).atZone(zone).toInstant()
        return List(count) {
            cursor = parsed.next(cursor, zone) ?: return@List "none"
            cursor.atZone(zone).toLocalDateTime().toString()
        }
    }

    private fun next(expression: String, after: String, zone: ZoneId = UTC): String =
        series(expression, after, 1, zone)[0]

    private fun instants(expression: String, after: Instant, zone: ZoneId, count: Int): List<Instant> {
        val parsed = cron(expression)
        var cursor = after
        return List(count) { parsed.next(cursor, zone)!!.also { cursor = it } }
    }

    // Syntax.

    @Test
    fun `a star fires every minute, strictly after the given instant`() {
        assertEquals(listOf("2026-10-18T10:01", "2026-10-18T10:02"), series("* * * * *", "2026-10-18T10:00", 2))
        assertEquals("2026-10-18T10:01", next("* * * * *", "2026-10-18T10:00:30"))
    }

    @Test
    fun `lists, ranges and steps`() {
        assertEquals(
            listOf("2026-10-18T10:15", "2026-10-18T10:30", "2026-10-18T11:00"),
            series("0,15,30 * * * *", "2026-10-18T10:00", 3),
        )
        assertEquals(
            listOf("2026-10-18T10:11", "2026-10-18T10:12", "2026-10-18T11:10"),
            series("10-12 * * * *", "2026-10-18T10:10", 3),
        )
        assertEquals(listOf("2026-10-18T10:05", "2026-10-18T10:10"), series("*/5 * * * *", "2026-10-18T10:00", 2))
        assertEquals(
            listOf("2026-10-18T10:30", "2026-10-18T11:10", "2026-10-18T11:15"),
            series("10-30/5 * * * *", "2026-10-18T10:25", 3),
        )
        assertEquals(
            listOf("2026-10-18T10:05", "2026-10-18T10:20", "2026-10-18T10:35", "2026-10-18T10:50", "2026-10-18T11:05"),
            series("5/15 * * * *", "2026-10-18T10:00", 5),
        )
        assertEquals(listOf("2026-10-18T12:00", "2026-10-18T18:00"), series("0 */6 * * *", "2026-10-18T10:00", 2))
    }

    @Test
    fun `month and day names in any case`() {
        assertEquals(listOf("2027-01-01T00:00", "2027-07-01T00:00"), series("0 0 1 jan,Jul *", "2026-10-18T10:00", 2))
        assertEquals(listOf("2026-10-19T09:00", "2026-10-20T09:00"), series("0 9 * * mon-FRI", "2026-10-18T10:00", 2))
        assertEquals("2026-11-01T00:00", next("0 0 1 OCT-DEC/1 *", "2026-10-18T10:00"))
        assertEquals("2026-10-23T00:00", next("0 0 * * fri", "2026-10-18T10:00"))
    }

    @Test
    fun `day of week 0 and 7 are Sunday, and a range may end on Sunday`() {
        assertEquals("2026-10-25T00:00", next("0 0 * * 0", "2026-10-18T10:00"))
        assertEquals("2026-10-25T00:00", next("0 0 * * 7", "2026-10-18T10:00"))
        assertEquals("2026-10-25T00:00", next("0 0 * * SUN", "2026-10-18T10:00"))
        assertEquals(
            listOf("2026-10-24T00:00", "2026-10-25T00:00", "2026-10-31T00:00"),
            series("0 0 * * SAT-SUN", "2026-10-18T10:00", 3),
        )
        assertEquals(
            listOf("2026-10-23T00:00", "2026-10-24T00:00", "2026-10-25T00:00", "2026-10-30T00:00"),
            series("0 0 * * 5-7", "2026-10-18T10:00", 4),
        )
    }

    @Test
    fun `a question mark is a star in the day fields`() {
        assertEquals(series("0 0 13 * *", "2026-10-18T10:00", 3), series("0 0 13 * ?", "2026-10-18T10:00", 3))
        assertEquals(series("0 0 * * MON", "2026-10-18T10:00", 3), series("0 0 ? * MON", "2026-10-18T10:00", 3))
    }

    @Test
    fun `both day fields restricted match either, as Vixie cron does`() {
        assertEquals(
            listOf("2026-12-11T00:00", "2026-12-13T00:00", "2026-12-18T00:00"),
            series("0 0 13 * FRI", "2026-12-05T10:00", 3),
        )
        assertEquals(
            listOf("2026-11-01T00:00", "2026-11-02T00:00", "2026-11-09T00:00", "2026-11-15T00:00"),
            series("0 0 1,15 * MON", "2026-10-31T10:00", 4),
        )
    }

    @Test
    fun `L is the last day of the month and L-n n days before it`() {
        assertEquals(
            listOf("2026-10-31T00:00", "2026-11-30T00:00", "2026-12-31T00:00", "2027-01-31T00:00", "2027-02-28T00:00"),
            series("0 0 L * *", "2026-10-18T10:00", 5),
        )
        assertEquals("2028-02-29T00:00", next("0 0 L 2 *", "2027-03-01T00:00"))
        assertEquals(
            listOf("2026-10-28T00:00", "2026-11-27T00:00", "2027-02-25T00:00"),
            listOf(next("0 0 L-3 * *", "2026-10-18T10:00"), next("0 0 L-3 * *", "2026-11-01T00:00")) +
                next("0 0 L-3 2 *", "2026-10-18T10:00"),
        )
        assertEquals("2026-10-31T00:00", next("0 0 L-0 * *", "2026-10-18T10:00"))
    }

    @Test
    fun `nW is the nearest weekday, never in another month`() {
        assertEquals("2026-08-03T00:00", next("0 0 1W * *", "2026-07-02T00:00"))
        assertEquals("2026-05-29T00:00", next("0 0 31W * *", "2026-05-01T00:00"))
        assertEquals("2026-08-14T00:00", next("0 0 15W * *", "2026-08-01T00:00"))
        assertEquals("2026-11-16T00:00", next("0 0 15W * *", "2026-11-01T00:00"))
        assertEquals("2026-10-15T00:00", next("0 0 15W * *", "2026-10-01T00:00"))
        assertEquals("2026-05-29T00:00", next("0 0 31W * *", "2026-04-01T00:00"))
    }

    @Test
    fun `LW is the last weekday of the month`() {
        assertEquals("2026-01-30T00:00", next("0 0 LW * *", "2026-01-01T00:00"))
        assertEquals("2026-05-29T00:00", next("0 0 LW * *", "2026-05-01T00:00"))
        assertEquals("2026-03-31T00:00", next("0 0 LW * *", "2026-03-01T00:00"))
    }

    @Test
    fun `nL is the last such weekday of the month`() {
        assertEquals("2026-10-30T00:00", next("0 0 * * 5L", "2026-10-18T10:00"))
        assertEquals("2026-10-30T00:00", next("0 0 * * FRIL", "2026-10-18T10:00"))
        assertEquals("2026-10-25T00:00", next("0 0 * * 7L", "2026-10-18T10:00"))
        assertEquals("2026-11-30T00:00", next("0 0 * * 1L", "2026-10-27T00:00"))
    }

    @Test
    fun `n#k is the k-th such weekday, skipping months without one`() {
        assertEquals("2026-03-30T00:00", next("0 0 * * 1#5", "2026-03-01T00:00"))
        assertEquals("2026-06-29T00:00", next("0 0 * * 1#5", "2026-03-31T00:00"))
        assertEquals("2026-11-02T00:00", next("0 0 * * MON#1", "2026-10-18T10:00"))
        assertEquals("2026-11-13T00:00", next("0 0 * * 5#2", "2026-10-18T10:00"))
    }

    @Test
    fun `an expression that never fires has no next`() {
        assertNull(cron("0 0 30 2 *").next(ORIGIN, UTC))
        assertNull(cron("0 0 31 4,6,9,11 *").next(ORIGIN, UTC))
        assertNull(cron("0 0 L-29 2 *").next(ORIGIN, UTC))
    }

    @Test
    fun `every macro`() {
        val after = "2026-10-18T10:15"
        assertEquals("2026-10-18T11:00", next("@hourly", after))
        assertEquals("2026-10-19T00:00", next("@daily", after))
        assertEquals("2026-10-19T00:00", next("@midnight", after))
        assertEquals("2026-10-25T00:00", next("@weekly", after))
        assertEquals("2026-11-01T00:00", next("@monthly", after))
        assertEquals("2027-01-01T00:00", next("@yearly", after))
        assertEquals("2027-01-01T00:00", next("@ANNUALLY", after))
    }

    @Test
    fun `the canonical expression reads back to an equal expression`() {
        assertEquals("0 9 * * MON-FRI", cron("  0  9 * *\tmon-fri ").toString())
        assertEquals("@yearly", cron("@annually").toString())
        assertEquals("@daily", cron("@Midnight").toString())
        assertEquals("0 0 L-3,15W * 5L", cron("0 0 l-3,15w * 5l").toString())
        for (text in listOf("*/5 1-3 L * SUN#2", "@weekly", "0 0 ? JAN-MAR 1-7/2")) {
            assertEquals(cron(text), cron(cron(text).toString()))
        }
    }

    // Refusals.

    @Test
    fun `malformed expressions are refused naming the field and the token`() {
        val cases = listOf(
            Triple("", null, ""),
            Triple("@reboot", null, "@reboot"),
            Triple("@often", null, "@often"),
            Triple("0 0 0 * * *", null, "0 0 0 * * *"),
            Triple("0 0 0 * * * 2026", null, "0 0 0 * * * 2026"),
            Triple("* * *", null, "* * *"),
            Triple("60 * * * *", "minute", "60"),
            Triple("-1 * * * *", "minute", "-1"),
            Triple("a * * * *", "minute", "A"),
            Triple("MON * * * *", "minute", "MON"),
            Triple("? * * * *", "minute", "?"),
            Triple("1,,2 * * * *", "minute", ""),
            Triple("*/0 * * * *", "minute", "*/0"),
            Triple("*/61 * * * *", "minute", "*/61"),
            Triple("*/x * * * *", "minute", "*/X"),
            Triple("5-1 * * * *", "minute", "5-1"),
            Triple("1-2-3 * * * *", "minute", "1-2-3"),
            Triple("1/2/3 * * * *", "minute", "1/2/3"),
            Triple("* 24 * * *", "hour", "24"),
            Triple("* * 0 * *", "day of month", "0"),
            Triple("* * 32 * *", "day of month", "32"),
            Triple("* * ?,1 * *", "day of month", "?"),
            Triple("* * L-31 * *", "day of month", "L-31"),
            Triple("* * 0W * *", "day of month", "0W"),
            Triple("* * 32W * *", "day of month", "32W"),
            Triple("* * 1-5W * *", "day of month", "1-5W"),
            Triple("* * MON * *", "day of month", "MON"),
            Triple("* * * 0 *", "month", "0"),
            Triple("* * * 13 *", "month", "13"),
            Triple("* * * FOO *", "month", "FOO"),
            Triple("* * * L *", "month", "L"),
            Triple("* * * * 8", "day of week", "8"),
            Triple("* * * * JAN", "day of week", "JAN"),
            Triple("* * * * MON-FOO", "day of week", "MON-FOO"),
            Triple("* * * * L", "day of week", "L"),
            Triple("* * * * 8L", "day of week", "8L"),
            Triple("* * * * 1#0", "day of week", "1#0"),
            Triple("* * * * 1#6", "day of week", "1#6"),
            Triple("* * * * 1#2#3", "day of week", "1#2#3"),
            Triple("* * * * 1W", "day of week", "1W"),
        )
        for ((expression, field, token) in cases) {
            val error = assertFailsWith<CronSyntaxException>(expression) { cron(expression) }
            assertEquals(field, error.field, expression)
            assertEquals(token, error.token, expression)
            assertContains(error.message!!, "'$token'", message = expression)
            if (field != null) assertContains(error.message!!, field, message = expression)
        }
    }

    // Daylight saving.

    @Test
    fun `a wall time in the spring gap fires once, at the end of the gap`() {
        assertEquals(
            listOf(instant("2026-03-28T01:30:00Z"), instant("2026-03-29T01:00:00Z"), instant("2026-03-30T00:30:00Z")),
            instants("30 2 * * *", instant("2026-03-27T12:00:00Z"), BERLIN, 3),
        )
        assertEquals(
            listOf(instant("2026-03-29T01:00:00Z"), instant("2026-03-30T00:00:00Z")),
            instants("*/15 2 * * *", instant("2026-03-29T00:00:00Z"), BERLIN, 2),
        )
        assertEquals(
            listOf(instant("2026-03-08T07:00:00Z"), instant("2026-03-09T06:30:00Z")),
            instants("30 2 * * *", instant("2026-03-08T00:00:00Z"), NEW_YORK, 2),
        )
    }

    @Test
    fun `a wall time in the autumn overlap fires once, at its first occurrence`() {
        assertEquals(
            listOf(instant("2026-10-25T00:30:00Z"), instant("2026-10-26T01:30:00Z")),
            instants("30 2 * * *", instant("2026-10-24T12:00:00Z"), BERLIN, 2),
        )
        assertEquals(
            listOf(instant("2026-10-25T00:30:00Z"), instant("2026-10-25T02:00:00Z")),
            instants("*/30 * * * *", instant("2026-10-25T00:00:00Z"), BERLIN, 2),
        )
        assertEquals(
            listOf(instant("2026-11-01T05:30:00Z"), instant("2026-11-02T06:30:00Z")),
            instants("30 1 * * *", instant("2026-11-01T00:00:00Z"), NEW_YORK, 2),
        )
    }

    @Test
    fun `a firing asked for during the repeated hour does not fire the first pass again`() {
        val secondPass = instant("2026-10-25T01:10:00Z")
        assertEquals(instant("2026-10-25T02:00:00Z"), cron("*/30 * * * *").next(secondPass, BERLIN))
    }
}
