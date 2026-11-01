package org.unitedsoftware.alexandrite.automation.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RestartsTest {
    @Test
    fun `the pause doubles up to its cap and starts over after a steady run`() = runTest {
        val time = virtualTime()
        val pauses = mutableListOf<Duration>()
        var runs = 0
        val job = launch {
            restarting(time, 1.seconds, 4.seconds, 1.minutes, { _, pause -> pauses += pause }) {
                if (++runs == 5) time.sleepUntil(now().plusSeconds(60))
                throw IOException("The disk is gone.")
            }
        }
        advanceTimeBy(80.seconds)
        job.cancelAndJoin()

        assertEquals(listOf(1.seconds, 2.seconds, 4.seconds, 4.seconds, 1.seconds, 2.seconds), pauses.take(6))
    }
}
