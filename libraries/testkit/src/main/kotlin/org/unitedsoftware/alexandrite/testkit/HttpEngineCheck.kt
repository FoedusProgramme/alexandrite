package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.withTimeoutOrNull
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine
import org.unitedsoftware.alexandrite.testkit.engine.CONNECTION_CHECKS
import org.unitedsoftware.alexandrite.testkit.engine.EngineRun
import org.unitedsoftware.alexandrite.testkit.engine.EngineServer
import org.unitedsoftware.alexandrite.testkit.engine.REQUEST_CHECKS
import org.unitedsoftware.alexandrite.testkit.engine.RESPONSE_CHECKS
import org.unitedsoftware.alexandrite.testkit.engine.STREAM_CHECKS
import kotlin.time.Duration.Companion.seconds

/** The checks that every [HttpEngine] passes, each against a loopback server of its own. */
public val HTTP_ENGINE_CONTRACT: List<HttpEngineCheck> = REQUEST_CHECKS + RESPONSE_CHECKS + STREAM_CHECKS +
    CONNECTION_CHECKS

/** One check of the [HTTP_ENGINE_CONTRACT]. */
public class HttpEngineCheck internal constructor(
    public val name: String,
    private val body: suspend EngineRun.() -> Unit,
) {
    /** Runs the check on [engine], which it leaves open, and throws an [AssertionError] when it breaks the contract. */
    public suspend fun run(engine: HttpEngine) {
        EngineServer().use { server ->
            withTimeoutOrNull(TIMEOUT) { EngineRun(engine, server).body() }
                ?: throw AssertionError("The check '$name' took longer than $TIMEOUT.")
        }
    }

    override fun toString(): String = name

    private companion object {
        val TIMEOUT = 60.seconds
    }
}
