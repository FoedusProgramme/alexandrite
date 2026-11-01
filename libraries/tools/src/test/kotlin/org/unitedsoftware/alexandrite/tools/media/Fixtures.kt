package org.unitedsoftware.alexandrite.tools.media

import org.unitedsoftware.alexandrite.engine.awtimages.AwtImageCodec
import org.unitedsoftware.alexandrite.engine.swinghtml.SwingHtmlParser
import org.unitedsoftware.alexandrite.sdk.media.ImageCodec
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser

/** The codec the desktop host binds. */
val AWT_CODEC: ImageCodec = AwtImageCodec()

/** The HTML parser the desktop host binds. */
val SWING_HTML: HtmlParser = SwingHtmlParser()
