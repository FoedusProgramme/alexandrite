package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.channel.common.link.IssuedLinkCode
import org.unitedsoftware.alexandrite.channel.common.link.LinkDecision
import org.unitedsoftware.alexandrite.channel.common.link.OperatorLink
import org.unitedsoftware.alexandrite.channel.common.link.OperatorLinks
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.Records
import java.time.Clock
import kotlin.time.Duration

/** The links of every QQ bot instance between an operator and the ids the platform names them by in a group. */
@Singleton
internal class QqBotOperatorLinks private constructor(private val links: OperatorLinks) {
    constructor(clock: Clock, records: Records) : this(OperatorLinks(clock, records))

    val codeLifetime: Duration get() = links.codeLifetime

    fun issue(operator: UserAddress): IssuedLinkCode = links.issue(operator)

    suspend fun redeem(code: String, chat: ChatAddress, alias: String): LinkDecision = links.redeem(code, chat, alias)

    suspend fun resolve(chat: ChatAddress, alias: String): UserAddress? = links.resolve(chat, alias)

    suspend fun of(operator: UserAddress): List<OperatorLink> = links.of(operator)

    suspend fun unlink(chat: ChatAddress, alias: String): Boolean = links.unlink(chat, alias)
}
