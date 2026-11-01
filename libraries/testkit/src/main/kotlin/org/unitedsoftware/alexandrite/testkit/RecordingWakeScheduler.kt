package org.unitedsoftware.alexandrite.testkit

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import org.unitedsoftware.alexandrite.sdk.runtime.WakeScheduler
import java.time.Instant

/** A [WakeScheduler] that keeps each owner's wake and records every call. */
public class RecordingWakeScheduler : WakeScheduler {
    private val recorded = MutableStateFlow(emptyList<Wake>())

    /** The calls, in order. */
    public val calls: List<Wake> get() = recorded.value

    /** The wakes set now, by owner. */
    public val pending: Map<String, Instant>
        get() = buildMap {
            for (call in calls) if (call.at == null) remove(call.owner) else put(call.owner, call.at)
        }

    /** When the host would wake the runtime first, null when no wake is set. */
    public val earliest: Instant? get() = pending.values.minOrNull()

    /** Suspends until [count] calls were made, and returns them. */
    public suspend fun awaitCalls(count: Int): List<Wake> = recorded.first { it.size >= count }

    override fun wakeAt(owner: String, at: Instant?) {
        recorded.update { it + Wake(owner, at) }
    }

    /** A call that set [owner]'s wake to [at], or cleared it. */
    @Poko
    public class Wake(public val owner: String, public val at: Instant?)
}
