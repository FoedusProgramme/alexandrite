package org.unitedsoftware.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import java.time.Instant

/**
 * Something a channel was told that is not a message anybody wrote.
 *
 * A channel reports the messages its chats send as turns, and everything else it is told — somebody joined, somebody
 * left, a message was taken back, a friendship was asked for — as one of these. What a plugin does with one is its own
 * business, which is why the kind is a name rather than a closed set: every platform has events the others do not, and
 * a subscriber that does not know a kind can still see that something happened.
 *
 * The texts here are what the platform reported, escaped where they are shown to a model.
 */
@Poko
public class ChannelNotice(
    public val chat: ChatAddress,
    public val kind: ChannelNoticeKind,
    /** Who caused it, null when the platform did not say. */
    public val actor: ChatUser? = null,
    /** Whom it concerns besides [actor], such as the member who joined or was removed. */
    public val target: ChatUser? = null,
    /** The message it is about, for the kinds that name one, such as a message taken back. */
    public val message: ChannelMessageRef? = null,
    public val receivedAt: Instant,
    /** What else the platform said, in the order it said it. */
    public val facts: List<DisplayFact> = emptyList(),
)

/**
 * What kind of [ChannelNotice] this is.
 *
 * A channel reports the kinds it knows under these names, and may report one of its own under a name of its own, which
 * is what keeps a platform's whole vocabulary reachable without this list growing for every platform.
 */
@JvmInline
public value class ChannelNoticeKind(public val id: String) {
    override fun toString(): String = id

    public companion object {
        /** Somebody joined the chat. */
        public val MEMBER_JOINED: ChannelNoticeKind = ChannelNoticeKind("member.joined")

        /** Somebody left the chat, whether by leaving or by being removed. */
        public val MEMBER_LEFT: ChannelNoticeKind = ChannelNoticeKind("member.left")

        /** Somebody was silenced or unsilenced. */
        public val MEMBER_BANNED: ChannelNoticeKind = ChannelNoticeKind("member.banned")

        /** Somebody's standing in the chat changed, such as becoming an administrator. */
        public val ROLE_CHANGED: ChannelNoticeKind = ChannelNoticeKind("role.changed")

        /** A message was taken back. */
        public val MESSAGE_RECALLED: ChannelNoticeKind = ChannelNoticeKind("message.recalled")

        /** Somebody was added as a friend, or asked to be. */
        public val FRIEND_ADDED: ChannelNoticeKind = ChannelNoticeKind("friend.added")

        /** Somebody asked to join. */
        public val JOIN_REQUESTED: ChannelNoticeKind = ChannelNoticeKind("join.requested")

        /** Somebody asked to be a friend. */
        public val FRIEND_REQUESTED: ChannelNoticeKind = ChannelNoticeKind("friend.requested")

        /** The kinds this version knows. */
        public val entries: List<ChannelNoticeKind> = listOf(
            MEMBER_JOINED,
            MEMBER_LEFT,
            MEMBER_BANNED,
            ROLE_CHANGED,
            MESSAGE_RECALLED,
            FRIEND_ADDED,
            JOIN_REQUESTED,
            FRIEND_REQUESTED,
        )

        /** The kind of [id], which keeps an id this version does not know. */
        public fun of(id: String): ChannelNoticeKind = entries.firstOrNull { it.id == id } ?: ChannelNoticeKind(id)
    }
}
