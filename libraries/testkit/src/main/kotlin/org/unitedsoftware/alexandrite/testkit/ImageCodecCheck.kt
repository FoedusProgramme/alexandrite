package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.testkit.media.IMAGE_CODEC_CHECKS
import org.unitedsoftware.alexandrite.testkit.media.ImageCodecRun

/** The checks that every [ImageCodec] passes, each on images of its own. */
public val IMAGE_CODEC_CONTRACT: List<ImageCodecCheck> = IMAGE_CODEC_CHECKS

/** One check of the [IMAGE_CODEC_CONTRACT]. */
public class ImageCodecCheck internal constructor(
    public val name: String,
    private val body: ImageCodecRun.() -> Unit,
) {
    /** Runs the check on [codec], and throws an [AssertionError] when it breaks the contract. */
    public fun run(codec: ImageCodec) {
        ImageCodecRun(codec).body()
    }

    override fun toString(): String = name
}
