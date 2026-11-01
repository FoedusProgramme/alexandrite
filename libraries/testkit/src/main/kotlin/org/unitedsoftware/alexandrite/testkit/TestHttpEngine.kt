package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.internal.http.JdkHttpEngine
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine

/** The JDK engine the runtime binds, shared by every caller, for a test that builds what needs an [HttpEngine]. */
public fun testHttpEngine(): HttpEngine = SHARED

private val SHARED: HttpEngine by lazy { JdkHttpEngine() }
