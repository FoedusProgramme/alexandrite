package org.unitedsoftware.alexandrite.channel.onebot

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/** The lines logged while [block] ran, as `LEVEL message`. */
internal suspend fun logged(block: suspend () -> Unit): List<String> {
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
        lines += "${event.level} ${event.formattedMessage}"
    }
}
