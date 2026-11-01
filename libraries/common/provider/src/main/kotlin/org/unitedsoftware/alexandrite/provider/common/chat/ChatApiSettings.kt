package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointSettings

/** What an endpoint of the Chat Completions API does beyond its [EndpointSettings]. */
@Serializable
public class ChatApiSettings(
    /** Whether requests carry the agent's cache key. */
    public val promptCacheKey: Boolean = false,
)
