package org.unitedsoftware.alexandrite.channel.common

/** Marks channel-common API that only channel-common itself may use. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal channel-common API: only channel-common itself may use it.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class InternalChannelCommonApi
