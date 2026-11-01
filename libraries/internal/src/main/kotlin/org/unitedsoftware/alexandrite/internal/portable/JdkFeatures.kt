package org.unitedsoftware.alexandrite.internal.portable

/** Which JDK features beyond Android's API the process has. */
public object JdkFeatures {
    /** `java.lang.ProcessHandle`. */
    public val processHandles: Boolean by lazy { has("java.lang.ProcessHandle", "current") }

    /** Virtual threads (`Thread.ofVirtual`). */
    public val virtualThreads: Boolean by lazy { has("java.lang.Thread", "ofVirtual") }

    /** `com.sun.net.httpserver`. */
    public val httpServer: Boolean by lazy { has("com.sun.net.httpserver.HttpServer", "create") }

    /** `sun.misc.Signal`. */
    public val signals: Boolean by lazy { has("sun.misc.Signal", "handle") }

    private fun has(className: String, method: String): Boolean = try {
        Class.forName(className, false, JdkFeatures::class.java.classLoader).methods.any { it.name == method }
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
