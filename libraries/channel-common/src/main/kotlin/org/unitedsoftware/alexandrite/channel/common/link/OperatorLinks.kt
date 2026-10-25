package org.unitedsoftware.alexandrite.channel.common.link

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.pairing.PairingCodes
import org.unitedsoftware.alexandrite.internal.pairing.Redemption
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Recognizes an operator under another id in a chat, where the platform does not name them the same everywhere.
 *
 * An operator asks for a one-time code where they are already recognized, then spends it with a command in the chat
 * where the platform gives them a different id; that id becomes their alias there, for that chat alone, until
 * revoked.
 */
public class OperatorLinks(clock: Clock, records: Records, public val codeLifetime: Duration = 10.minutes) {
    private val codes = PairingCodes<UserAddress, Unit>(clock, codeLifetime, capacity = CODE_CAPACITY)
    private val links = records.collection<LinkRecord>(COLLECTION)
    private val deciding = Mutex()

    /** A one-time code [operator] may spend with a command in the chat where they want to be recognized. */
    public fun issue(operator: UserAddress): IssuedLinkCode {
        val issued = codes.issue(PURPOSE, operator, Unit)
        return IssuedLinkCode(issued.text, issued.expiresAt)
    }

    /** Links [alias], the id the platform names the spender of [code] by in [chat], to the operator who asked for it. */
    public suspend fun redeem(code: String, chat: ChatAddress, alias: String): LinkDecision = deciding.withLock {
        when (val redemption = codes.redeem(PURPOSE, code, UserAddress(chat.instance, alias))) {
            is Redemption.Redeemed -> link(chat, alias, redemption.owner)
            is Redemption.Rejected -> LinkDecision.Rejected(redemption.triesLeft)
            is Redemption.Locked -> LinkDecision.Locked(redemption.until)
            Redemption.OwnCode -> LinkDecision.OwnCode
            Redemption.Malformed -> LinkDecision.Malformed
        }
    }

    private suspend fun link(chat: ChatAddress, alias: String, operator: UserAddress): LinkDecision {
        val key = "$chat"
        val existing = try {
            links.get(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LinkDecision.Failed(e.message ?: e.toString())
        }
        val aliases = existing?.value?.aliases.orEmpty()
        if (alias !in aliases && aliases.size >= MAX_ALIASES_PER_CHAT) {
            return LinkDecision.Failed("$chat already has $MAX_ALIASES_PER_CHAT linked aliases")
        }
        return try {
            links.put(key, LinkRecord(aliases + (alias to operator)), operator)
            LinkDecision.Linked(operator)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LinkDecision.Failed(e.message ?: e.toString())
        }
    }

    /** The operator [alias] is recognized as in [chat], null when it names none. */
    public suspend fun resolve(chat: ChatAddress, alias: String): UserAddress? =
        links.get("$chat")?.value?.aliases?.get(alias)

    /** The links of [operator], across every chat, oldest first. */
    public suspend fun of(operator: UserAddress): List<OperatorLink> = links.list()
        .mapNotNull { record -> ChatAddress.parseOrNull(record.key)?.let { chat -> chat to record } }
        .flatMap { (chat, record) ->
            record.value.aliases.filterValues { it == operator }.keys.map { alias ->
                OperatorLink(chat, alias, operator, record.updatedAt)
            }
        }

    /** Revokes the link of [alias] in [chat], false when it names none. */
    public suspend fun unlink(chat: ChatAddress, alias: String): Boolean = deciding.withLock {
        val key = "$chat"
        val existing = links.get(key) ?: return@withLock false
        if (alias !in existing.value.aliases) return@withLock false
        val remaining = existing.value.aliases - alias
        try {
            if (remaining.isEmpty()) links.remove(key) else links.put(key, LinkRecord(remaining), null)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot unlink {} of {}: {}", alias, chat, e.toString())
            false
        }
    }

    private companion object {
        const val PURPOSE = "link"

        const val COLLECTION = "operator_links"

        /** The live codes kept, dropping the oldest. */
        const val CODE_CAPACITY = 1_000

        /** The aliases one chat may hold, so a chat nobody moderates cannot grow its record without bound. */
        const val MAX_ALIASES_PER_CHAT = 50

        val logger = LoggerFactory.getLogger(OperatorLinks::class.java)
    }
}

/** One chat's aliases, by the id the platform names their holder with there. */
@Serializable
internal class LinkRecord(val aliases: Map<String, UserAddress> = emptyMap())

/** A code [OperatorLinks.issue] gave out. */
@Poko
public class IssuedLinkCode(public val text: String, public val expiresAt: Instant)

/** An operator recognized under [alias] in [chat], since [linkedAt]. */
@Poko
public class OperatorLink(
    public val chat: ChatAddress,
    public val alias: String,
    public val operator: UserAddress,
    public val linkedAt: Instant,
)

/** What [OperatorLinks.redeem] made of a code. */
public sealed interface LinkDecision {
    @Poko
    public class Linked(public val operator: UserAddress) : LinkDecision

    /** An unknown, spent or expired code, or one of another purpose. */
    @Poko
    public class Rejected(public val triesLeft: Int) : LinkDecision

    /** Too many wrong codes. */
    @Poko
    public class Locked(public val until: Instant) : LinkDecision

    /** The spender's own live code, neither spent nor counted. */
    public data object OwnCode : LinkDecision

    /** Input that holds no code. */
    public data object Malformed : LinkDecision

    /** The code was spent, but the link could not be stored. */
    @Poko
    public class Failed(public val reason: String) : LinkDecision
}
