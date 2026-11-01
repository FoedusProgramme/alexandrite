package org.unitedsoftware.alexandrite.channel.telegram

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotApi
import org.unitedsoftware.alexandrite.http.common.HttpTimeouts
import org.unitedsoftware.alexandrite.sdk.config.Secret
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A token of the same grammar that [FakeBotApi] does not accept. */
const val OTHER_TOKEN = "987654321:BBAnotherFakeTokenForAlexandrite_9876543"

/** Timeouts that end a slow call within half a second. */
val QUICK = HttpTimeouts(connect = 2.seconds, firstByte = 500.milliseconds, idle = 500.milliseconds)

internal fun configOf(fake: FakeBotApi, token: String = fake.token): TelegramInstanceConfig =
    TelegramInstanceConfig(Secret(token), apiBaseUrl = fake.baseUrl)

/** The lines logged while [block] ran, as `LEVEL message` followed by the stack trace of a logged throwable. */
suspend fun logged(block: suspend () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val recorder = LineRecorder().apply { start() }
    logger.addAppender(recorder)
    try {
        block()
    } finally {
        logger.detachAppender(recorder)
    }
    return recorder.lines.toList()
}

private class LineRecorder : AppenderBase<ILoggingEvent>() {
    val lines = CopyOnWriteArrayList<String>()

    override fun append(event: ILoggingEvent) {
        val trace = event.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) }.orEmpty()
        lines += "${event.level} ${event.formattedMessage}$trace"
    }
}
