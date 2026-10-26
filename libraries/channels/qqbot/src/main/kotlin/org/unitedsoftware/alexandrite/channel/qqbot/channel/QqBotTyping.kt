package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotChats
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Shows that the bot is writing in each single chat with an open reply, one ticker per chat.
 *
 * The platform serves the input state for single chats only; a group has none, and no ticker runs there. A tick says
 * the state lasts a minute, and the next one renews it while the reply is open.
 */
@ChannelInstanceScoped
internal class QqBotTyping(private val instance: ChannelInstance, private val sender: QqBotSender) : Lifecycle {
    private val tickers = HashMap<ChatAddress, Ticker>()
    private val ticking = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + ticking)

    /** Starts typing in [chat] for one more reply. */
    fun start(chat: ChatAddress) {
        if (QqBotChats.kind(chat) != ChatKind.DIRECT) return
        synchronized(tickers) {
            val ticker = tickers.getOrPut(chat, ::Ticker)
            ticker.replies++
            if (ticker.job?.isActive != true) {
                ticker.job = scope.launch(CoroutineName("$chat typing")) { tick(chat) }
            }
        }
    }

    /** Stops typing in [chat] for one reply, and for good after the last. */
    fun stop(chat: ChatAddress) {
        synchronized(tickers) {
            val ticker = tickers[chat] ?: return
            if (--ticker.replies > 0) return
            tickers.remove(chat)
            ticker.job?.cancel()
        }
    }

    override fun onStop() {
        ticking.cancel()
        synchronized(tickers) { tickers.clear() }
    }

    private suspend fun tick(chat: ChatAddress) {
        withTimeoutOrNull(LIMIT) {
            while (true) {
                try {
                    val delivery = sender.sendTyping(chat)
                    if (delivery is Delivery.NotDelivered) {
                        logger.debug("{}: no typing state in {}: {}", instance.id, chat, delivery)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.debug("{}: could not show typing in {}: {}", instance.id, chat, e.toString())
                }
                delay(INTERVAL)
            }
        }
    }

    private class Ticker {
        var replies = 0
        var job: Job? = null
    }

    private companion object {
        /** The platform's input state lasts a minute; a tick renews it before it runs out. */
        val INTERVAL = 50.seconds

        /** How long a ticker types at most. */
        val LIMIT = 30.minutes

        val logger = LoggerFactory.getLogger(QqBotTyping::class.java)
    }
}
