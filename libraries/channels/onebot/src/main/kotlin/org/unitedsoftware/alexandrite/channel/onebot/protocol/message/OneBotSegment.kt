package org.unitedsoftware.alexandrite.channel.onebot.protocol.message

import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.onebot.protocol.MessageId
import org.unitedsoftware.alexandrite.channel.onebot.protocol.UserId

/**
 * One segment of an OneBot message.
 *
 * A segment names what it is in `type` and carries its own fields in `data`. A type this version does not know is
 * kept as [Unknown] with its `data` untouched, so a message never loses content while it crosses the plugin.
 *
 * [OneBotSegmentCodec] encodes and decodes the shapes the standard describes, so a `type` field that is also a
 * parameter of a segment, as in an image, never collides with the discriminator.
 */
public sealed interface OneBotSegment {
    /** The `type` of the segment. */
    public val type: String

    /** Plain text. */
    public data class Text(public val text: String) : OneBotSegment {
        override val type: String get() = OneBotSegment.TEXT
    }

    /** A QQ emoji. */
    public data class Face(public val id: String) : OneBotSegment {
        override val type: String get() = OneBotSegment.FACE
    }

    /** An image. */
    public data class Image(
        public val file: String? = null,
        /** `flash` for a self-destructing image, null for a plain one. */
        public val subtype: String? = null,
        public val url: String? = null,
        public val cache: Boolean? = null,
        public val proxy: Boolean? = null,
        public val timeout: Int? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.IMAGE
    }

    /** A voice message. */
    public data class Record(
        public val file: String? = null,
        /** Whether the record is sent with a changed voice. */
        public val magic: Boolean? = null,
        public val url: String? = null,
        public val cache: Boolean? = null,
        public val proxy: Boolean? = null,
        public val timeout: Int? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.RECORD
    }

    /** A short video. */
    public data class Video(
        public val file: String? = null,
        public val url: String? = null,
        public val cache: Boolean? = null,
        public val proxy: Boolean? = null,
        public val timeout: Int? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.VIDEO
    }

    /**
     * A file, which an implementation that offers the extension carries as a segment of its own.
     *
     * The standard sends a file through the actions for files, apart from messages, so this is a segment the standard
     * does not declare: an implementation that reports one names the file and what to call it, and may name more
     * about it than this version carries.
     */
    public data class File(
        /** How the implementation knows the file, which is also what an upload takes. */
        public val file: String,
        /** What the file is called. */
        public val name: String? = null,
        /** Where the file can be read, when the implementation names a place. */
        public val url: String? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.FILE
    }

    /** A mention of one member, or of everyone when [userId] is [At.ALL]. */
    public data class At(public val userId: String) : OneBotSegment {
        override val type: String get() = OneBotSegment.AT

        /** Whether the mention names everyone in the group. */
        public val isAll: Boolean get() = userId == ALL

        public companion object {
            /** The `qq` value that mentions everyone. */
            public const val ALL: String = "all"
        }
    }

    /** The rock-paper-scissors magic emoji. */
    public data object Rps : OneBotSegment {
        override val type: String get() = OneBotSegment.RPS
    }

    /** The dice magic emoji. */
    public data object Dice : OneBotSegment {
        override val type: String get() = OneBotSegment.DICE
    }

    /** The window shake, the simplest kind of a poke. */
    public data object Shake : OneBotSegment {
        override val type: String get() = OneBotSegment.SHAKE
    }

    /** A poke. */
    public data class Poke(
        /** The poke kind. */
        public val pokeType: String? = null,
        public val id: String? = null,
        /** The poke's name, which an implementation reports but does not take. */
        public val name: String? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.POKE
    }

    /** A message sent without a name. */
    public data class Anonymous(
        /** Whether the message is sent anyway when the platform refuses anonymity. */
        public val ignore: Boolean? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.ANONYMOUS
    }

    /** A shared link. */
    public data class Share(
        public val url: String,
        public val title: String,
        public val content: String? = null,
        public val image: String? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.SHARE
    }

    /** A recommended friend or group. */
    public data class Contact(
        /** `qq` for a friend, `group` for a group. */
        public val contactType: String,
        public val id: String,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.CONTACT
    }

    /** A location. */
    public data class Location(
        public val lat: String,
        public val lon: String,
        public val title: String? = null,
        public val content: String? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.LOCATION
    }

    /** A shared piece of music, either from a provider named by [musicType] or described by [url] and [audio]. */
    public data class Music(
        /** `qq`, `163`, `xm` for a provider, or `custom` for a share the sender described itself. */
        public val musicType: String,
        public val id: String? = null,
        public val url: String? = null,
        public val audio: String? = null,
        public val title: String? = null,
        public val content: String? = null,
        public val image: String? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.MUSIC
    }

    /** A reply to an earlier message. */
    public data class Reply(public val id: MessageId) : OneBotSegment {
        override val type: String get() = OneBotSegment.REPLY
    }

    /** A forwarded message, whose content only `get_forward_msg` returns. */
    public data class Forward(public val id: String) : OneBotSegment {
        override val type: String get() = OneBotSegment.FORWARD
    }

    /** A node of a forwarded message, either an existing message named by [id] or one the sender described. */
    public data class Node(
        public val id: MessageId? = null,
        public val userId: UserId? = null,
        public val nickname: String? = null,
        /** A message in any of the three shapes the API accepts, null when the node names a message by [id]. */
        public val content: OneBotMessage? = null,
    ) : OneBotSegment {
        override val type: String get() = OneBotSegment.NODE
    }

    /** An XML message. */
    public data class Xml(public val data: String) : OneBotSegment {
        override val type: String get() = OneBotSegment.XML
    }

    /** A JSON message. */
    public data class Json(public val data: String) : OneBotSegment {
        override val type: String get() = OneBotSegment.JSON
    }

    /** A segment of a type this version does not know, with the value of its `data` object untouched. */
    public data class Unknown(
        override val type: String,
        /** The `data` object as the implementation sent it, empty when the segment carried none. */
        public val data: JsonObject = JsonObject(emptyMap()),
    ) : OneBotSegment

    /** The names of the segment types OneBot v11 defines. */
    public companion object {
        public const val TEXT: String = "text"
        public const val FACE: String = "face"
        public const val IMAGE: String = "image"
        public const val RECORD: String = "record"
        public const val VIDEO: String = "video"
        public const val FILE: String = "file"
        public const val AT: String = "at"
        public const val RPS: String = "rps"
        public const val DICE: String = "dice"
        public const val SHAKE: String = "shake"
        public const val POKE: String = "poke"
        public const val ANONYMOUS: String = "anonymous"
        public const val SHARE: String = "share"
        public const val CONTACT: String = "contact"
        public const val LOCATION: String = "location"
        public const val MUSIC: String = "music"
        public const val REPLY: String = "reply"
        public const val FORWARD: String = "forward"
        public const val NODE: String = "node"
        public const val XML: String = "xml"
        public const val JSON: String = "json"

        /** The names of every segment type, in the order the standard lists them. */
        public val KNOWN_TYPES: List<String> = listOf(
            TEXT,
            FACE,
            IMAGE,
            RECORD,
            VIDEO,
            AT,
            RPS,
            DICE,
            SHAKE,
            POKE,
            ANONYMOUS,
            SHARE,
            CONTACT,
            LOCATION,
            MUSIC,
            REPLY,
            FORWARD,
            NODE,
            XML,
            JSON,
        )
    }
}
