package org.unitedsoftware.alexandrite.channel.common.pairing

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.channel.common.InternalChannelCommonApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi
import java.time.Instant

/**
 * The pairing of one channel plugin's chats, which an app approves and revokes for the operator.
 *
 * New members come with default bodies only.
 */
@ContributedSpi
public interface PairingDesk {
    /** The requests of unpaired chats whose codes are still valid, oldest first. */
    public fun pending(): List<PairingRequest>

    /** Whether a request of this desk holds [code]. */
    public fun holds(code: String): Boolean

    /** Pairs the chat that got [code] at once, approved by [by], null for the host. */
    public suspend fun approve(code: String, by: ChatUser?): PairingDecision

    /** Drops the request of the chat that got [code], denied by [by], null for the host. */
    public suspend fun deny(code: String, by: ChatUser?): PairingDecision

    /** The paired chats, and the approved chats of channel instances that do not run. */
    public suspend fun paired(): List<PairedChat>

    /** Removes the approval of [chat] whenever it has one, then tells whether the config file still pairs it. */
    public suspend fun unpair(chat: ChatAddress): Unpairing
}

/** A paired chat. */
@Poko
public class PairedChat(
    public val chat: ChatAddress,
    /** Whether the config file declares the chat. */
    public val declared: Boolean,
    /** How the chat's request was approved, null when it was not. */
    public val approval: PairingApproval?,
    /** Whether the chat's channel instance runs. */
    public val instanceRunning: Boolean = true,
)

/** An approved request to be paired. */
@Poko
public class PairingApproval(
    public val kind: ChatKind,
    public val title: String?,
    /** Who sent the message that got the code, null when the platform did not say. */
    public val requester: ChatUser?,
    public val approvedAt: Instant,
    /** Who approved the code, null for the host. */
    public val approvedBy: UserAddress?,
)

/** An unpaired chat's request to be paired. */
@Poko
public class PairingRequest(
    public val chat: ChatAddress,
    public val kind: ChatKind,
    public val title: String?,
    /** Who sent the message, null when the platform did not say. */
    public val requester: ChatUser?,
    /** The code the chat was offered. */
    public val code: String,
    public val issuedAt: Instant,
    public val expiresAt: Instant,
)

/** What a [PairingDesk] made of a code. */
@SubclassOptInRequired(InternalChannelCommonApi::class)
public interface PairingDecision {
    @OptIn(InternalChannelCommonApi::class)
    @Poko
    public class Approved(public val request: PairingRequest) : PairingDecision

    @OptIn(InternalChannelCommonApi::class)
    @Poko
    public class Denied(public val request: PairingRequest) : PairingDecision

    /** The chat could not be kept as paired, and its request now holds a new code. */
    @OptIn(InternalChannelCommonApi::class)
    @Poko
    public class Failed(public val request: PairingRequest, public val reason: String) : PairingDecision

    /** No request holds the code. */
    @OptIn(InternalChannelCommonApi::class)
    public data object Unknown : PairingDecision

    /** Input that holds no code. */
    @OptIn(InternalChannelCommonApi::class)
    public data object Malformed : PairingDecision
}

/** What [PairingDesk.unpair] did. */
@SubclassOptInRequired(InternalChannelCommonApi::class)
public interface Unpairing {
    @OptIn(InternalChannelCommonApi::class)
    public data object Unpaired : Unpairing

    @OptIn(InternalChannelCommonApi::class)
    public data object NotPaired : Unpairing

    /** The config file declares the chat, which stays paired. */
    @OptIn(InternalChannelCommonApi::class)
    @Poko
    public class Declared(
        /** Whether the chat's approval was removed. */
        public val approvalRemoved: Boolean,
    ) : Unpairing

    @OptIn(InternalChannelCommonApi::class)
    @Poko
    public class Failed(public val reason: String) : Unpairing
}
