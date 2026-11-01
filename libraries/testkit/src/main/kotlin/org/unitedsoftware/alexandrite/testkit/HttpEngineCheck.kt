package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.testkit.net.CONNECTION_CHECKS
import org.unitedsoftware.alexandrite.testkit.net.HttpEngineRun
import org.unitedsoftware.alexandrite.testkit.net.HttpEngineServer
import org.unitedsoftware.alexandrite.testkit.net.REQUEST_CHECKS
import org.unitedsoftware.alexandrite.testkit.net.RESPONSE_CHECKS
import org.unitedsoftware.alexandrite.testkit.net.STREAM_CHECKS
import kotlin.time.Duration.Companion.seconds

/** The checks that every [HttpEngine] passes, each against a loopback server of its own. */
public val HTTP_ENGINE_CONTRACT: List<HttpEngineCheck> = REQUEST_CHECKS + RESPONSE_CHECKS + STREAM_CHECKS +
    CONNECTION_CHECKS

/** One check of the [HTTP_ENGINE_CONTRACT]. */
public class HttpEngineCheck internal constructor(
    public val name: String,
    private val body: suspend HttpEngineRun.() -> Unit,
) {
    /** Runs the check on [engine], which it leaves open, and throws an [AssertionError] when it breaks the contract. */
    public suspend fun run(engine: HttpEngine) {
        HttpEngineServer().use { server ->
            withTimeoutOrNull(TIMEOUT) { HttpEngineRun(engine, server).body() }
                ?: throw AssertionError("The check '$name' took longer than $TIMEOUT.")
        }
    }

    override fun toString(): String = name

    private companion object {
        val TIMEOUT = 60.seconds
    }
}
