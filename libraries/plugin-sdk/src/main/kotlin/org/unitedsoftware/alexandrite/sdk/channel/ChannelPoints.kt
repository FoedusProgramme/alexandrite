package org.unitedsoftware.alexandrite.sdk.channel

import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint

/**
 * The hook points of the channels.
 *
 * A channel fires these for what its chats were told besides messages. A subscriber contributes an [ObserverHook] of
 * the point, and the runtime hands it every notice of every channel that fires it, which is what lets a plugin act on
 * an event of a platform it was not written for.
 */
public object ChannelPoints {
    /**
     * Fires for every report that is not a message, in the order the channel read them.
     *
     * A notice is not a turn and nothing waits for a subscriber: one that fails is logged and the channel goes on.
     */
    public val NOTICE: ObserverPoint<ChannelNotice> = ObserverPoint("channel.notice")
}
