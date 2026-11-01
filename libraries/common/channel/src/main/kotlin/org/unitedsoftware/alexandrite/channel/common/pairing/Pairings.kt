package org.unitedsoftware.alexandrite.channel.common.pairing

import dev.drewhamilton.poko.Poko
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import org.unitedsoftware.alexandrite.internal.pairing.PairingCodes
import org.unitedsoftware.alexandrite.internal.pairing.isPairingCode
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.sdk.store.collection
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A [PairingDesk] over the channel instances of a channel plugin, offering an unpaired chat one code per lifetime. */
public class Pairings(private val clock: Clock, records: Records, public val codeLifetime: Duration = 10.minutes) :
    PairingDesk {
    private val approvals = records.collection<Approval>(COLLECTION)
    private val codes = PairingCodes<ChatAddress, ChatAddress>(clock, codeLifetime, capacity = CAPACITY)
    private val lock = Any()
    private val instances = LinkedHashMap<ChannelInstanceId, InstancePairing>()
    private val requests = LinkedHashMap<ChatAddress, PairingRequest>()

    /** When each chat that was offered a code may get the next one. */
    private val offered = BoundedMap<ChatAddress, Instant>(OFFERED, accessOrder = false)
    private val deciding = Mutex()

    /** Opens the pairing of [instance], whose config declares the chats [declared] paired. */
    public suspend fun open(instance: ChannelInstanceId, declared: Collection<ChatAddress>): InstancePairing {
        require(declared.all { it.instance == instance }) { "The paired chats of $instance hold another instance's." }
        return deciding.withLock {
            val approved = approvals.list().mapNotNull(::byChat).filter { (chat, _) -> chat.instance == instance }
            synchronized(lock) {
                require(instance !in instances) { "The pairing of $instance is open already." }
                val paired = declared.mapTo(LinkedHashSet()) { it.parent }
                val pairing = InstancePairing(instance, this, paired, approved.toMap(LinkedHashMap()))
                instances[instance] = pairing
                pairing
            }
        }
    }

    /** Closes the pairing of [instance] and drops its requests. */
    public fun close(instance: ChannelInstanceId) {
        synchronized(lock) {
            instances.remove(instance) ?: return
            requests.keys.filter { it.instance == instance }.forEach(::drop)
            offered.keys.removeIf { it.instance == instance }
        }
    }

    override fun pending(): List<PairingRequest> = synchronized(lock) {
        purge(clock.instant())
        requests.values.sortedBy { it.issuedAt }
    }

    override fun holds(code: String): Boolean = synchronized(lock) { codes.holds(PURPOSE, code) }

    override suspend fun approve(code: String, by: ChatUser?): PairingDecision =
        decide(code) { request, pairing -> pair(request, pairing, by) }

    override suspend fun deny(code: String, by: ChatUser?): PairingDecision = decide(code) { request, _ ->
        logger.info("The request of chat {} to be paired is denied by {}", request.chat, who(by))
        PairingDecision.Denied(request)
    }

    private suspend fun pair(request: PairingRequest, pairing: InstancePairing, by: ChatUser?): PairingDecision {
        val approval = Approval(request.kind, request.title, request.requester?.let(::Requester))
        val record = try {
            approvals.put("${request.chat}", approval, by?.address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val waiting = reissue(request)
            logger.warn("Cannot pair chat {}, whose request now holds a new code: {}", request.chat, e.toString())
            return PairingDecision.Failed(waiting, e.message ?: e.toString())
        }
        synchronized(lock) { pairing.approved[request.chat] = record }
        logger.info("Chat {} is paired by {}", request.chat, who(by))
        return PairingDecision.Approved(request)
    }

    override suspend fun paired(): List<PairedChat> {
        val recorded = approvals.list().mapNotNull(::byChat)
        return synchronized(lock) {
            val running = instances.values.flatMap { pairing ->
                val declared = pairing.declared.map { PairedChat(it, true, pairing.approved[it]?.let(::approval)) }
                declared + pairing.approved.filterKeys { it !in pairing.declared }.map { (chat, record) ->
                    PairedChat(chat, false, approval(record))
                }
            }
            running + recorded.filter { (chat, _) -> chat.instance !in instances }.map { (chat, record) ->
                PairedChat(chat, false, approval(record), instanceRunning = false)
            }
        }
    }

    override suspend fun unpair(chat: ChatAddress): Unpairing = deciding.withLock {
        val key = chat.parent
        val pairing = synchronized(lock) { instances[key.instance] }
        val declared = pairing != null && key in pairing.declared
        if (pairing != null && synchronized(lock) { key !in pairing.approved }) {
            return@withLock if (declared) Unpairing.Declared(approvalRemoved = false) else Unpairing.NotPaired
        }
        val removed = try {
            approvals.remove("$key")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot unpair chat {}: {}", key, e.toString())
            return@withLock Unpairing.Failed(e.message ?: e.toString())
        }
        if (pairing != null) synchronized(lock) { pairing.approved -= key }
        when {
            declared -> {
                logger.info("The approval of chat {} is removed, and the config file still pairs it", key)
                Unpairing.Declared(approvalRemoved = true)
            }

            pairing == null && !removed -> Unpairing.NotPaired

            else -> {
                logger.info("Chat {} is unpaired", key)
                Unpairing.Unpaired
            }
        }
    }

    internal fun isPaired(pairing: InstancePairing, chat: ChatAddress): Boolean =
        synchronized(lock) { chat.parent in pairing.declared || chat.parent in pairing.approved }

    internal fun request(
        pairing: InstancePairing,
        chat: ChatAddress,
        kind: ChatKind,
        title: String?,
        requester: ChatUser?,
    ): PairingCode? = synchronized(lock) {
        val key = chat.parent
        if (isPaired(pairing, key) || instances[pairing.instance] !== pairing) return null
        val now = clock.instant()
        purge(now)
        if (key in offered) return null
        val issued = codes.issue(PURPOSE, key, key)
        offered[key] = issued.expiresAt
        requests.remove(key)
        requests[key] = PairingRequest(key, kind, title, requester, issued.text, now, issued.expiresAt)
        while (requests.size > CAPACITY) drop(requests.keys.first())
        PairingCode(issued.text, issued.expiresAt)
    }

    /** What [onRequest] makes of the request that holds [code], one decision at a time. */
    private suspend fun decide(
        code: String,
        onRequest: suspend (PairingRequest, InstancePairing) -> PairingDecision,
    ): PairingDecision = deciding.withLock {
        val found = synchronized(lock) {
            purge(clock.instant())
            val chat = codes.take(PURPOSE, code)?.payload
                ?: return@withLock if (isPairingCode(code)) PairingDecision.Unknown else PairingDecision.Malformed
            checkNotNull(requests.remove(chat)) to checkNotNull(instances[chat.instance])
        }
        onRequest(found.first, found.second)
    }

    /** [request] under a new code, waiting again. */
    private fun reissue(request: PairingRequest): PairingRequest = synchronized(lock) {
        val issued = codes.issue(PURPOSE, request.chat, request.chat)
        val waiting = PairingRequest(
            request.chat,
            request.kind,
            request.title,
            request.requester,
            issued.text,
            clock.instant(),
            issued.expiresAt,
        )
        requests[request.chat] = waiting
        waiting
    }

    private fun purge(now: Instant) {
        requests.values.removeIf { !now.isBefore(it.expiresAt) }
        offered.values.removeIf { !now.isBefore(it) }
    }

    private fun drop(chat: ChatAddress) {
        requests.remove(chat)
        codes.revoke(PURPOSE, chat)
    }

    private companion object {
        const val PURPOSE = "pair"

        const val COLLECTION = "pairings"

        /** The requests kept, dropping the oldest. */
        const val CAPACITY = 1_000

        /** The chats whose last offer is kept, dropping the least recent. */
        const val OFFERED = 4096

        val logger = LoggerFactory.getLogger(Pairings::class.java)
    }
}

/** The pairing of one channel instance's chats. */
public class InstancePairing internal constructor(
    public val instance: ChannelInstanceId,
    private val desk: Pairings,
    internal val declared: Set<ChatAddress>,
    internal val approved: MutableMap<ChatAddress, StoredRecord<Approval>>,
) {
    /** Whether [chat], or the chat whose thread it is, is paired. */
    public fun isPaired(chat: ChatAddress): Boolean = desk.isPaired(this, chat)

    /** A code for the unpaired [chat], null when it is paired or was offered one within a code's lifetime. */
    public fun request(chat: ChatAddress, kind: ChatKind, title: String?, requester: ChatUser?): PairingCode? =
        desk.request(this, chat, kind, title, requester)
}

/** A code offered to an unpaired chat. */
@Poko
public class PairingCode(public val text: String, public val expiresAt: Instant)

/** An approved request as its record keeps it. */
@Serializable
internal class Approval(val kind: ChatKind, val title: String? = null, val requester: Requester? = null)

/** Who asked for a code, as their channel saw them then. */
@Serializable
internal class Requester(
    val address: UserAddress,
    val name: String,
    val username: String? = null,
    val bot: Boolean = false,
    @SerialName("admin") val operator: Boolean = false,
) {
    constructor(user: ChatUser) : this(user.address, user.displayName, user.username, user.isBot, user.isOperator)

    fun user(): ChatUser = ChatUser(address, name, username, bot, operator)
}

private fun who(by: ChatUser?): String = by?.address?.toString() ?: "the host"

/** [record] by the chat it approves, null when its key names none. */
private fun byChat(record: StoredRecord<Approval>): Pair<ChatAddress, StoredRecord<Approval>>? =
    ChatAddress.parseOrNull(record.key)?.takeIf { it.thread == null }?.let { it to record }

private fun approval(record: StoredRecord<Approval>): PairingApproval = PairingApproval(
    record.value.kind,
    record.value.title,
    record.value.requester?.user(),
    record.createdAt,
    record.createdBy,
)
