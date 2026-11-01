package org.unitedsoftware.alexandrite.automation.common

import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration

/** The wall clock the scheduler reads and sleeps on. */
@InternalAutomationApi
public interface TimeSource {
    public fun now(): Instant

    /** Suspends until [now] has reached [instant]. */
    public suspend fun sleepUntil(instant: Instant)

    public companion object {
        /** The system clock, re-read at least once a minute so a changed clock is noticed. */
        public val system: TimeSource = of(Clock.systemUTC())

        /** [clock], re-read at least once a minute so a changed clock is noticed. */
        public fun of(clock: Clock): TimeSource = DelayingTimeSource(clock::instant, poll = 1.minutes)

        /**
         * [clock] read for the time, slept on with `delay`.
         *
         * Under `runTest`, `delaying { origin.plusMillis(testScheduler.currentTime) }` puts both on virtual time.
         */
        public fun delaying(clock: () -> Instant): TimeSource = DelayingTimeSource(clock, poll = Duration.INFINITE)
    }
}

private class DelayingTimeSource(private val clock: () -> Instant, private val poll: Duration) : TimeSource {
    override fun now(): Instant = clock()

    override suspend fun sleepUntil(instant: Instant) {
        while (true) {
            val left = java.time.Duration.between(now(), instant).toKotlinDuration()
            if (!left.isPositive()) return
            delay(minOf(left, poll))
        }
    }
}
