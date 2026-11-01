package org.unitedsoftware.alexandrite.tools.web

import org.unitedsoftware.alexandrite.engine.swinghtml.AlexandriteEngineSwingHtmlIndex
import org.unitedsoftware.alexandrite.sdk.text.HtmlParser
import org.unitedsoftware.alexandrite.testkit.boundBy

/** The HTML parser the desktop host binds. */
internal val SWING_HTML: HtmlParser = boundBy(AlexandriteEngineSwingHtmlIndex())
