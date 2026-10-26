package org.unitedsoftware.alexandrite.channel.onebot.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A QQ number.
 *
 * OneBot prints it as a JSON number, but implementations also send it as a string, so [OneBotJson] decodes both.
 * The value is kept as the string the platform used, so a number outside `Long` still round-trips.
 */
@JvmInline
@Serializable(with = UserIdSerializer::class)
public value class UserId(public val value: String) {
    init {
        require(value.isNotEmpty() && value.all(Char::isDigit)) { "A user id is a non-empty number, was '$value'." }
    }

    /** The number, null when it does not fit a [Long]. */
    public val number: Long? get() = value.toLongOrNull()

    override fun toString(): String = value
}

/** A group number, kept as [UserId] keeps a user number. */
@JvmInline
@Serializable(with = GroupIdSerializer::class)
public value class GroupId(public val value: String) {
    init {
        require(value.isNotEmpty() && value.all(Char::isDigit)) { "A group id is a non-empty number, was '$value'." }
    }

    public val number: Long? get() = value.toLongOrNull()

    override fun toString(): String = value
}

/** The message id of one platform message, kept as [UserId] keeps a number. */
@JvmInline
@Serializable(with = MessageIdSerializer::class)
public value class MessageId(public val value: String) {
    init {
        require(value.isNotEmpty() && value.all(Char::isDigit)) { "A message id is a non-empty number, was '$value'." }
    }

    public val number: Long? get() = value.toLongOrNull()

    override fun toString(): String = value
}

/** The QQ number an implementation reports as its own. */
@JvmInline
@Serializable(with = SelfIdSerializer::class)
public value class SelfId(public val value: String) {
    init {
        require(value.isNotEmpty() && value.all(Char::isDigit)) { "A self id is a non-empty number, was '$value'." }
    }

    public val number: Long? get() = value.toLongOrNull()

    override fun toString(): String = value
}

internal object UserIdSerializer : KSerializer<UserId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OneBotUserId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UserId) {
        encoder.encodeNumber(value.value, value.number)
    }

    override fun deserialize(decoder: Decoder): UserId = UserId(decoder.numberText())
}

internal object GroupIdSerializer : KSerializer<GroupId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OneBotGroupId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: GroupId) {
        encoder.encodeNumber(value.value, value.number)
    }

    override fun deserialize(decoder: Decoder): GroupId = GroupId(decoder.numberText())
}

internal object MessageIdSerializer : KSerializer<MessageId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OneBotMessageId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MessageId) {
        encoder.encodeNumber(value.value, value.number)
    }

    override fun deserialize(decoder: Decoder): MessageId = MessageId(decoder.numberText())
}

internal object SelfIdSerializer : KSerializer<SelfId> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OneBotSelfId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SelfId) {
        encoder.encodeNumber(value.value, value.number)
    }

    override fun deserialize(decoder: Decoder): SelfId = SelfId(decoder.numberText())
}

/** Prints [number] as a JSON number when it fits one, and the id as a string when it does not. */
private fun Encoder.encodeNumber(id: String, number: Long?) {
    if (number == null) encodeString(id) else encodeLong(number)
}

/** The number [this] holds, whether the platform printed it as a JSON number or as a string. */
private fun Decoder.numberText(): String {
    val text = decodeString()
    require(text.isNotEmpty() && text.all(Char::isDigit)) { "Expected a number, got '$text'." }
    return text
}
