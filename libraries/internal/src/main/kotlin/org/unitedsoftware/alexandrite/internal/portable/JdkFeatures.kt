package org.unitedsoftware.alexandrite.internal.portable

/** Which JDK features beyond Android's API the process has, each probed once by whether its class is there. */
public object JdkFeatures {
    /** `java.lang.ProcessHandle`: process ids, descendants and exit futures. */
    public val processHandles: Boolean by lazy { present("java.lang.ProcessHandle") }

    /** Virtual threads (`Thread.ofVirtual`). */
    public val virtualThreads: Boolean by lazy { present("java.lang.Thread\$Builder\$OfVirtual") }

    /** `com.sun.net.httpserver`, the JDK's small HTTP server. */
    public val httpServer: Boolean by lazy { present("com.sun.net.httpserver.HttpServer") }

    /** `sun.misc.Signal`, the JVM's signal handlers. */
    public val signals: Boolean by lazy { present("sun.misc.Signal") }

    private fun present(name: String): Boolean = try {
        Class.forName(name, false, JdkFeatures::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
