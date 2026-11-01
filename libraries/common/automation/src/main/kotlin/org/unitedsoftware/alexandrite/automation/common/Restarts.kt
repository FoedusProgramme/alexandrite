package org.unitedsoftware.alexandrite.automation.common

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * Runs [block] again and again until cancelled, each time after a pause that starts at [first] and doubles up to
 * [last], back to [first] after a run of [steady] or longer.
 *
 * [failed] hears each failure of [block] with the pause before the next run.
 */
@InternalAutomationApi
public suspend fun restarting(
    time: TimeSource,
    first: Duration,
    last: Duration,
    steady: Duration,
    failed: (Exception, Duration) -> Unit,
    block: suspend () -> Unit,
): Nothing {
    var pause = first
    while (true) {
        val started = time.now()
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (java.time.Duration.between(started, time.now()).toKotlinDuration() >= steady) pause = first
            failed(e, pause)
        }
        time.sleepUntil(time.now().plus(pause.toJavaDuration()))
        pause = (pause * 2).coerceAtMost(last)
    }
}
