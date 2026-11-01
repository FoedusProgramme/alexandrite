package org.unitedsoftware.alexandrite.sdk.runtime

import org.unitedsoftware.alexandrite.sdk.di.BoundSpi
import java.time.Instant

/**
 * Wakes the runtime when work of its plugins comes due, also after the host's process has ended in between. Optional:
 * a host whose runtime keeps running binds none.
 *
 * - Each owner, the plugin id of its caller, holds at most one wake, apart from every other owner's.
 * - An owner's latest call replaces its wake; an owner calls again after each start and whenever its next due instant
 *   changes.
 * - The host has the runtime running at or after a wake's instant, never before, and may be late; a wake outlasts the
 *   runtime's stop, the end of its process and the device's restart, and is spent once the runtime runs at or after
 *   its instant.
 * - Once woken, the runtime runs what is due through its owners' handling of missed work; the host runs nothing of its
 *   own for a wake.
 */
@BoundSpi
public interface WakeScheduler {
    /** Sets the wake of [owner] to [at], or clears it when [at] is null; returns quickly, on any thread. */
    public fun wakeAt(owner: String, at: Instant?)
}
