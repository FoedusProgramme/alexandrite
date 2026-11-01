package org.unitedsoftware.alexandrite.internal.portable

/** Which JDK features beyond Android's API the process has. */
public object JdkFeatures {
    /** `java.lang.ProcessHandle`. */
    public val processHandles: Boolean by lazy { present("java.lang.ProcessHandle") }

    /** Virtual threads (`Thread.ofVirtual`). */
    public val virtualThreads: Boolean by lazy { present("java.lang.Thread\$Builder\$OfVirtual") }

    /** `com.sun.net.httpserver`. */
    public val httpServer: Boolean by lazy { present("com.sun.net.httpserver.HttpServer") }

    private fun present(name: String): Boolean = try {
        Class.forName(name, false, JdkFeatures::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
