package org.unitedsoftware.alexandrite.automation.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A five-field cron expression (minute hour day-of-month month day-of-week) in wall-clock time.
 *
 * When both day fields are restricted, a day matches if either does.
 */
@InternalAutomationApi
public class CronExpression internal constructor(
    private val canonical: String,
    private val minutes: IntArray,
    private val hours: IntArray,
    private val months: BooleanArray,
    private val days: DayRule,
) {
    /**
     * The first firing strictly after [after], or null when there is none within [SEARCH_YEARS] years.
     *
     * A wall time in a daylight-saving gap fires at the end of the gap, one in an overlap at its first occurrence.
     */
    public fun next(after: Instant, zone: ZoneId): Instant? {
        var start = LocalDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = start.toLocalDate().plusYears(SEARCH_YEARS)
        while (true) {
            val local = nextLocal(start, limit) ?: return null
            val instant = resolve(local, zone)
            if (instant > after) return instant
            start = local.plusMinutes(1)
        }
    }

    /** The canonical expression, which [parse] reads back. */
    override fun toString(): String = canonical

    override fun equals(other: Any?): Boolean = other is CronExpression && other.canonical == canonical

    override fun hashCode(): Int = canonical.hashCode()

    private fun nextLocal(start: LocalDateTime, limit: LocalDate): LocalDateTime? {
        var date = start.toLocalDate()
        var from = start.toLocalTime()
        while (!date.isAfter(limit)) {
            if (!months[date.monthValue]) {
                date = date.withDayOfMonth(1).plusMonths(1)
                from = LocalTime.MIDNIGHT
                continue
            }
            if (days.matches(date)) firstTime(from)?.let { return date.atTime(it) }
            date = date.plusDays(1)
            from = LocalTime.MIDNIGHT
        }
        return null
    }

    private fun firstTime(from: LocalTime): LocalTime? {
        for (hour in hours) {
            if (hour < from.hour) continue
            val minute = if (hour == from.hour) minutes.firstOrNull { it >= from.minute } else minutes.first()
            if (minute != null) return LocalTime.of(hour, minute)
        }
        return null
    }

    public companion object {
        /** How far ahead [next] searches */
        public const val SEARCH_YEARS: Long = 50

        /** Reads [expression], a five-field expression or a macro such as `@daily`. */
        public fun parse(expression: String): CronExpression = CronParser.parse(expression)

        private fun resolve(local: LocalDateTime, zone: ZoneId): Instant {
            val rules = zone.rules
            return if (rules.getValidOffsets(local).isEmpty()) {
                rules.getTransition(local).instant
            } else {
                ZonedDateTime.ofLocal(local, zone, null).toInstant()
            }
        }
    }
}

/** A malformed cron expression. */
@InternalAutomationApi
public class CronSyntaxException(
    /** The field the bad token is in, null for the expression as a whole */
    public val field: String?,
    public val token: String,
    reason: String,
) : IllegalArgumentException("Bad cron ${field ?: "expression"} '$token': $reason.")

internal class DayRule(
    /** Null when unrestricted. */
    private val dayOfMonth: List<MonthDayRule>?,
    /** Null when unrestricted. */
    private val dayOfWeek: List<WeekDayRule>?,
) {
    fun matches(date: LocalDate): Boolean {
        val byMonth = dayOfMonth?.any { it.matches(date) }
        val byWeek = dayOfWeek?.any { it.matches(date) }
        if (byMonth != null && byWeek != null) return byMonth || byWeek
        return byMonth ?: byWeek ?: true
    }
}

internal sealed interface MonthDayRule {
    fun matches(date: LocalDate): Boolean

    class Days(private val days: BooleanArray) : MonthDayRule {
        override fun matches(date: LocalDate) = days[date.dayOfMonth]
    }

    /** `L` and `L-n`. */
    class LastDay(private val before: Int) : MonthDayRule {
        override fun matches(date: LocalDate) = date.dayOfMonth == date.lengthOfMonth() - before
    }

    /** `nW`: the weekday nearest to day [day], within its month. */
    class NearestWeekday(private val day: Int) : MonthDayRule {
        override fun matches(date: LocalDate): Boolean {
            if (day > date.lengthOfMonth()) return false
            return date.dayOfMonth == nearestWeekday(date.withDayOfMonth(day)).dayOfMonth
        }
    }

    /** `LW`. */
    object LastWeekday : MonthDayRule {
        override fun matches(date: LocalDate) =
            date.dayOfMonth == nearestWeekday(date.withDayOfMonth(date.lengthOfMonth())).dayOfMonth
    }
}

private fun nearestWeekday(day: LocalDate): LocalDate = when (day.dayOfWeek) {
    java.time.DayOfWeek.SATURDAY -> if (day.dayOfMonth == 1) day.plusDays(2) else day.minusDays(1)
    java.time.DayOfWeek.SUNDAY -> if (day.dayOfMonth == day.lengthOfMonth()) day.minusDays(2) else day.plusDays(1)
    else -> day
}

/** Days of the week numbered as cron does, Sunday 0. */
internal fun LocalDate.cronDayOfWeek(): Int = dayOfWeek.value % 7

internal sealed interface WeekDayRule {
    fun matches(date: LocalDate): Boolean

    class Days(private val days: BooleanArray) : WeekDayRule {
        override fun matches(date: LocalDate) = days[date.cronDayOfWeek()]
    }

    /** `nL`. */
    class Last(private val day: Int) : WeekDayRule {
        override fun matches(date: LocalDate) =
            date.cronDayOfWeek() == day && date.dayOfMonth + 7 > date.lengthOfMonth()
    }

    /** `n#k`. */
    class Nth(private val day: Int, private val nth: Int) : WeekDayRule {
        override fun matches(date: LocalDate) = date.cronDayOfWeek() == day && (date.dayOfMonth - 1) / 7 + 1 == nth
    }
}
