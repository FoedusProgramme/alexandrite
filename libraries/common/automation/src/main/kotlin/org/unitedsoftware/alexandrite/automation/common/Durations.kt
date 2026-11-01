package org.unitedsoftware.alexandrite.automation.common

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The duration [text] writes in days, hours and minutes, such as `30m`, `2h`, `1d` or `1h30m`.
 *
 * @throws IllegalArgumentException when [text] writes none, with the reason
 */
@InternalAutomationApi
public fun parseDuration(text: String): Duration {
    val match = DURATION.matchEntire(text.lowercase().filterNot(Char::isWhitespace))
    require(match != null && match.value.isNotEmpty()) { "'$text' is no duration; $DURATIONS." }
    val (days, hours, minutes, seconds) = match.destructured
    require(seconds.isEmpty()) { "'$text' counts seconds; $DURATIONS." }
    val parts = listOf(days to 1.days, hours to 1.hours, minutes to 1.minutes).filter { it.first.isNotEmpty() }
    val total = parts.fold(Duration.ZERO) { sum, (count, unit) ->
        val value = requireNotNull(count.toLongOrNull()?.takeIf { it <= MAX_COUNT }) {
            "'$text' is too long a duration."
        }
        sum + unit * value.toInt()
    }
    require(total.isPositive()) { "'$text' is no positive duration; $DURATIONS." }
    require(total <= MAX_DURATION) { "'$text' is longer than ${MAX_DURATION.inWholeDays} days." }
    return total
}

private val DURATION = Regex("(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")
private const val DURATIONS = "write minutes and up, such as 30m, 2h, 1d or 1h30m"
private const val MAX_COUNT = 1_000_000L
private val MAX_DURATION = 3660.days
