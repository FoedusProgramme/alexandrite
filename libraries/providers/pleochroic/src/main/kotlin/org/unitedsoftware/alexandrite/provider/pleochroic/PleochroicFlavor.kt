package org.unitedsoftware.alexandrite.provider.pleochroic

import org.unitedsoftware.alexandrite.provider.common.OpenRouterShapedListing
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect

/** The wire dialect of models reached through the Pleochroic gateway or a self-hosted `standalone-service`. */
internal val PLEOCHROIC: Dialect = Dialect("pleochroic")

/**
 * Pleochroic's model relay is the standard Chat Completions API: requests ask for a reasoning effort with the flat
 * `reasoning_effort` field and responses carry thinking in `reasoning_content`, which [ChatFlavor.STANDARD]'s
 * defaults already send and read, so this flavor only swaps in the model listing, whose capabilities `GET /v1/models`
 * shapes the way OpenRouter's does.
 */
internal val PLEOCHROIC_FLAVOR: ChatFlavor = ChatFlavor(PLEOCHROIC, listing = PleochroicListing)

internal object PleochroicListing : OpenRouterShapedListing()
