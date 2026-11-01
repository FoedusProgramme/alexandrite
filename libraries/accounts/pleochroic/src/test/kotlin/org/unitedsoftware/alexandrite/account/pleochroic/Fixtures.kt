package org.unitedsoftware.alexandrite.account.pleochroic

import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import org.unitedsoftware.alexandrite.http.common.HttpTimeouts
import org.unitedsoftware.alexandrite.http.common.HttpTransport
import kotlin.time.Duration.Companion.seconds

internal fun transport() = HttpTransport(HttpTimeouts(5.seconds, 5.seconds, 5.seconds), JdkHttpEngine())
