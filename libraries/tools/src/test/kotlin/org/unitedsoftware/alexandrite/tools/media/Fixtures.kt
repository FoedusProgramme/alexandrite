package org.unitedsoftware.alexandrite.tools.media

import org.unitedsoftware.alexandrite.engine.awtimages.AwtImageCodec
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec

/** The codec the desktop host binds. */
internal val AWT_CODEC: ImageCodec = AwtImageCodec()
