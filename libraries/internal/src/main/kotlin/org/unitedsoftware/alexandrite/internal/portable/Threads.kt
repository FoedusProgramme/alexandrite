package org.unitedsoftware.alexandrite.internal.portable

/** Threads for blocking work: virtual where the JDK has them, else daemon platform threads. */
public object Threads {
    public fun unstarted(name: String, task: Runnable): Thread =
        if (JdkFeatures.virtualThreads) virtual(name, task) else Thread(task, name).apply { isDaemon = true }

    public fun start(name: String, task: Runnable): Thread = unstarted(name, task).apply { start() }

    @JdkOnly
    private fun virtual(name: String, task: Runnable): Thread = Thread.ofVirtual().name(name).unstarted(task)
}
