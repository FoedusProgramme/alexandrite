package org.unitedsoftware.alexandrite.automation.common

internal object CronParser {
    private val MACROS = mapOf(
        "@yearly" to ("@yearly" to "0 0 1 1 *"),
        "@annually" to ("@yearly" to "0 0 1 1 *"),
        "@monthly" to ("@monthly" to "0 0 1 * *"),
        "@weekly" to ("@weekly" to "0 0 * * 0"),
        "@daily" to ("@daily" to "0 0 * * *"),
        "@midnight" to ("@daily" to "0 0 * * *"),
        "@hourly" to ("@hourly" to "0 * * * *"),
    )

    private val MONTH_NAMES = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        .withIndex().associate { (index, name) -> name to index + 1 }

    private val DAY_NAMES = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        .withIndex().associate { (index, name) -> name to index }

    private class Field(val name: String, val min: Int, val max: Int, val names: Map<String, Int> = emptyMap())

    private val MINUTE = Field("minute", 0, 59)
    private val HOUR = Field("hour", 0, 23)
    private val DAY_OF_MONTH = Field("day of month", 1, 31)
    private val MONTH = Field("month", 1, 12, MONTH_NAMES)
    private val DAY_OF_WEEK = Field("day of week", 0, 7, DAY_NAMES)

    fun parse(expression: String): CronExpression {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) throw CronSyntaxException(null, expression, "it is empty")
        if (trimmed.startsWith("@")) {
            val macro = trimmed.lowercase()
            if (macro == "@reboot") throw CronSyntaxException(null, trimmed, "@reboot is not supported")
            val (canonical, fields) = MACROS[macro] ?: throw CronSyntaxException(null, trimmed, "unknown macro")
            return fields(fields.split(' '), canonical)
        }
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.size == 6 || tokens.size == 7) {
            throw CronSyntaxException(null, trimmed, "a seconds or year field is not supported")
        }
        if (tokens.size != 5) {
            throw CronSyntaxException(null, trimmed, "expected 5 fields, found ${tokens.size}")
        }
        val upper = tokens.map { it.uppercase() }
        return fields(upper, upper.joinToString(" "))
    }

    private fun fields(tokens: List<String>, canonical: String): CronExpression {
        val (minute, hour, dayOfMonth, month, dayOfWeek) = tokens
        return CronExpression(
            canonical,
            minutes = values(MINUTE, minute).indices().toIntArray(),
            hours = values(HOUR, hour).indices().toIntArray(),
            months = values(MONTH, month),
            days = DayRule(
                if (dayOfMonth.isUnrestricted()) null else dayOfMonth.split(',').map { monthDay(it) },
                if (dayOfWeek.isUnrestricted()) null else dayOfWeek.split(',').map { weekDay(it) },
            ),
        )
    }

    private fun String.isUnrestricted() = this == "*" || this == "?"

    private fun BooleanArray.indices(): List<Int> = withIndex().filter { it.value }.map { it.index }

    /** The values [text] allows in [field], indexed by value. */
    private fun values(field: Field, text: String): BooleanArray {
        val allowed = BooleanArray(field.max + 1)
        for (part in text.split(',')) range(field, part, allowed)
        return allowed
    }

    private fun range(field: Field, part: String, allowed: BooleanArray) {
        if (part.isEmpty()) throw CronSyntaxException(field.name, part, "an empty list element")
        if (part == "?") throw CronSyntaxException(field.name, part, "? is allowed only alone in a day field")
        val slash = part.split('/')
        if (slash.size > 2) throw CronSyntaxException(field.name, part, "more than one step")
        val step = slash.getOrNull(1)?.let { text ->
            val span = field.max - field.min + 1
            text.toIntOrNull()?.takeIf { text.all(Char::isDigit) && it in 1..span }
                ?: throw CronSyntaxException(field.name, part, "the step must be a number from 1 to $span")
        }
        val base = slash[0]
        val (low, high) = when {
            base == "*" -> field.min to field.max

            '-' in base -> {
                val bounds = base.split('-')
                if (bounds.size != 2) throw CronSyntaxException(field.name, part, "a range has two ends")
                val low = value(field, bounds[0], part)
                val high = value(field, bounds[1], part).let {
                    if (field === DAY_OF_WEEK && it == 0 &&
                        low > 0
                    ) {
                        7
                    } else {
                        it
                    }
                }
                if (low > high) throw CronSyntaxException(field.name, part, "the range runs backwards")
                low to high
            }

            else -> value(field, base, part).let { it to if (step != null) field.max else it }
        }
        for (value in low..high step (step ?: 1)) {
            allowed[if (field === DAY_OF_WEEK) value % 7 else value] = true
        }
    }

    private fun value(field: Field, text: String, part: String): Int {
        field.names[text]?.let { return it }
        if (text.isEmpty() || !text.all(Char::isDigit)) {
            val reason = if (text.any(Char::isLetter)) "unknown name '$text'" else "'$text' is no number"
            throw CronSyntaxException(field.name, part, reason)
        }
        val value = text.toIntOrNull()
        if (value == null || value !in field.min..field.max) {
            throw CronSyntaxException(field.name, part, "out of range ${field.min}-${field.max}")
        }
        return value
    }

    private fun monthDay(part: String): MonthDayRule {
        val field = DAY_OF_MONTH
        return when {
            part == "L" -> MonthDayRule.LastDay(0)

            part == "LW" -> MonthDayRule.LastWeekday

            part.startsWith("L-") -> {
                val before = part.removePrefix("L-")
                MonthDayRule.LastDay(
                    before.toIntOrNull()?.takeIf { before.all(Char::isDigit) && it in 0..30 }
                        ?: throw CronSyntaxException(field.name, part, "L-n takes n from 0 to 30"),
                )
            }

            part.endsWith("W") -> MonthDayRule.NearestWeekday(value(field, part.removeSuffix("W"), part))

            else -> MonthDayRule.Days(values(field, part))
        }
    }

    private fun weekDay(part: String): WeekDayRule {
        val field = DAY_OF_WEEK
        return when {
            part == "L" -> throw CronSyntaxException(field.name, part, "L needs a day, as in 5L")

            part.endsWith("L") -> WeekDayRule.Last(value(field, part.removeSuffix("L"), part) % 7)

            '#' in part -> {
                val (day, nth) = part.split('#').takeIf { it.size == 2 }
                    ?: throw CronSyntaxException(field.name, part, "n#k has one #")
                val k = nth.toIntOrNull()?.takeIf { nth.all(Char::isDigit) && it in 1..5 }
                    ?: throw CronSyntaxException(field.name, part, "n#k takes k from 1 to 5")
                WeekDayRule.Nth(value(field, day, part) % 7, k)
            }

            else -> WeekDayRule.Days(values(field, part))
        }
    }
}
