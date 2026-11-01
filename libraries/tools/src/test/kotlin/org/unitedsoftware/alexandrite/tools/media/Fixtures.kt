package org.unitedsoftware.alexandrite.tools.media

import org.unitedsoftware.alexandrite.engine.awtimages.AlexandriteEngineAwtImagesIndex
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.testkit.boundBy

/** The codec the desktop host binds. */
internal val AWT_CODEC: ImageCodec = boundBy(AlexandriteEngineAwtImagesIndex())
