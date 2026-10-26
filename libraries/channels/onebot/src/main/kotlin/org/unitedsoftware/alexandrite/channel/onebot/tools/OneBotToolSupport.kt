package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.api.OneBotApi
import org.unitedsoftware.alexandrite.channel.onebot.channel.OneBotApis
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

/** The API of the instance that serves [chat], which fails when no such instance runs. */
internal fun OneBotApis.serving(chat: ChatAddress): OneBotApi =
    of(chat.instance) ?: throw IllegalStateException("No OneBot channel instance serves ${chat.instance}.")

/** How much of an answer is shown, since an answer to a read may be far longer than a turn should carry. */
internal const val ANSWER_LIMIT: Int = 4_000

/**
 * How large a file a call carries in one request, above which the file goes to the implementation in pieces.
 *
 * The bytes are written the way the platform takes them, which grows them by about a third, and an implementation
 * refuses what is larger than it takes without saying so in a way a model can act on, so a call is refused here
 * instead, naming the limit it passed.
 */
internal const val FILE_LIMIT: Long = 8L * 1024 * 1024

/**
 * What [what] says when the call was taken, and what the implementation said when it was not.
 *
 * A call that only reads is answered with what the platform gave it, because what was asked for *is* that answer: a
 * call told only that its action was carried out leaves the model with nothing to read, which is what a read that
 * discards the answer amounts to. A call that changes something is answered with what it did, since the platform's
 * answer to it carries nothing the model can use.
 */
internal fun OneBotResult<*>.outcome(what: String, answered: Boolean = false): String = when (this) {
    is OneBotResult.Ok -> if (answered) answer() else what
    is OneBotResult.Async -> "$what The implementation answers later."
    is OneBotResult.Failed -> "The implementation refused it: ${message ?: "it gave no reason"} (retcode $retcode)."
    is OneBotResult.Unreachable -> "The implementation could not be reached: ${failure.message}."
    is OneBotResult.Malformed -> "The implementation answered something this channel cannot read."
}

/** What the platform answered, as the text it is, cut where it is longer than a turn should carry. */
private fun OneBotResult.Ok<*>.answer(): String {
    val shown = data?.toString().orEmpty()
    return when {
        shown.isEmpty() || shown == "null" -> "The implementation answered with nothing."
        shown.length <= ANSWER_LIMIT -> shown
        else -> shown.take(ANSWER_LIMIT) + " … (the answer was longer and stops here.)"
    }
}

/** The text of [name], null when the call gave none. */
internal fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** [value] as the number it is, or as the text it is when it names no number. */
internal fun JsonObjectBuilder.putId(name: String, value: String) {
    val number = value.toLongOrNull()
    if (number == null) put(name, JsonPrimitive(value)) else put(name, number)
}
