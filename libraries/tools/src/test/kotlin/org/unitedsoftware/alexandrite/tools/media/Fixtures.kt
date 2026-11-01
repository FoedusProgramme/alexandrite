package org.unitedsoftware.alexandrite.tools.media

import org.unitedsoftware.alexandrite.engine.awtimages.AlexandriteEngineAwtImagesIndex
import org.unitedsoftware.alexandrite.engine.swinghtml.AlexandriteEngineSwingHtmlIndex
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.engine.boundBy

/** The codec the desktop host binds. */
val AWT_CODEC: ImageCodec = boundBy(AlexandriteEngineAwtImagesIndex())

/** The HTML parser the desktop host binds. */
val SWING_HTML: HtmlParser = boundBy(AlexandriteEngineSwingHtmlIndex())
