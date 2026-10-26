package org.unitedsoftware.alexandrite.channel.qqbot.net

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * The calls of a bot to the open platform.
 *
 * A call that cannot get a token must answer `Failed` like any other failed call: throwing from here used to end the
 * gateway's reconnect loop, which left the bot without a gateway until the runtime was restarted.
 */
class QqBotApiTest {
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `a token the platform cannot sell answers the call as failed instead of throwing`() = runBlocking<Unit> {
        val server = FakeApiServer()
        server.on(TOKEN_PATH) { PlatformAnswer(500, """{"message":"token endpoint unavailable"}""") }
        val config = testConfig(server.base)
        val http = QqBotHttp(config)
        try {
            val api = QqBotApi(http, QqBotTokens(config, http, clock))
            assertIs<QqBotAnswer.Failed>(api.get("/users/@me"))
        } finally {
            http.close()
            server.close()
        }
    }

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"
    }
}
