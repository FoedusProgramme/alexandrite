package org.unitedsoftware.alexandrite.channel.onebot.pairing

import org.unitedsoftware.alexandrite.channel.common.pairing.InstancePairing
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesk
import org.unitedsoftware.alexandrite.channel.common.pairing.Pairings
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import java.time.Clock
import kotlin.time.Duration

/** The pairing of the chats of every OneBot channel instance. */
@Singleton
@Contribute(PairingDesk::class)
internal class OneBotPairingDesk private constructor(private val pairings: Pairings) : PairingDesk by pairings {
    constructor(clock: Clock, records: Records) : this(Pairings(clock, records))

    val codeLifetime: Duration get() = pairings.codeLifetime

    suspend fun open(instance: ChannelInstanceId, declared: Collection<ChatAddress>): InstancePairing =
        pairings.open(instance, declared)

    fun close(instance: ChannelInstanceId) = pairings.close(instance)
}
