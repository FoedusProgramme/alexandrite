package org.unitedsoftware.alexandrite.internal.portable

/** Which JDK features beyond Android's API the process has, each probed once by whether its class is there. */
public object JdkFeatures {
    /** `java.lang.ProcessHandle`: process ids, descendants and exit futures. */
    public val processHandles: Boolean by lazy { present("java.lang.ProcessHandle") }

    /** Virtual threads (`Thread.ofVirtual`). */
    public val virtualThreads: Boolean by lazy { present("java.lang.Thread\$Builder\$OfVirtual") }

    /** `java.net.http`, the JDK's HTTP client. */
    public val httpClient: Boolean by lazy { present("java.net.http.HttpClient") }

    /** `com.sun.net.httpserver`, the JDK's small HTTP server. */
    public val httpServer: Boolean by lazy { present("com.sun.net.httpserver.HttpServer") }

    /** AWT images and ImageIO. */
    public val awtImages: Boolean by lazy {
        present("java.awt.image.BufferedImage") && present("javax.imageio.ImageIO")
    }

    /** Swing's HTML parser. */
    public val swingHtml: Boolean by lazy { present("javax.swing.text.html.parser.ParserDelegator") }

    private fun present(name: String): Boolean = try {
        Class.forName(name, false, JdkFeatures::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
