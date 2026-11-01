package org.unitedsoftware.alexandrite.automation.common

import dev.drewhamilton.poko.Poko
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** When something is due: once, at a fixed interval, or by a cron expression. */
@InternalAutomationApi
public sealed interface When {
    /** The first occurrence strictly after [after] with local times in [zone], null when there is none. */
    public fun next(after: Instant, zone: ZoneId): Instant?

    /** Once, at [at] as [ZonedDateTime.of] resolves it in the zone. */
    @Poko
    public class Once(public val at: LocalDateTime) : When {
        override fun next(after: Instant, zone: ZoneId): Instant? =
            ZonedDateTime.of(at, zone).toInstant().takeIf { it > after }
    }

    /** At [from] and every [interval] after it, in absolute time. */
    @Poko
    public class Every(public val interval: Duration, public val from: Instant) : When {
        init {
            require(interval.isPositive()) { "The interval must be positive, not $interval." }
        }

        override fun next(after: Instant, zone: ZoneId): Instant? {
            if (after < from) return from
            return stepAfter(from, interval.toJavaDuration(), after)
        }
    }

    @Poko
    public class Cron(public val expression: CronExpression) : When {
        override fun next(after: Instant, zone: ZoneId): Instant? = expression.next(after, zone)
    }
}

/** The first of the instants every [step] from [from] that comes after [after], null past the end of time. */
internal fun stepAfter(from: Instant, step: java.time.Duration, after: Instant): Instant? = try {
    from.plus(step.multipliedBy(java.time.Duration.between(from, after).dividedBy(step) + 1))
} catch (_: ArithmeticException) {
    null
} catch (_: DateTimeException) {
    null
}
