package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.internal.http.HttpException
import org.unitedsoftware.alexandrite.internal.http.HttpFailureKind
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.rebuild

internal fun modelException(kind: ModelErrorKind, message: String, outputStarted: Boolean): ModelException =
    ModelException(ModelError.builder(kind, message).outputStarted(outputStarted).build())

/** Throws a [ModelErrorKind.PROTOCOL] failure with [message] before any output started. */
internal fun protocol(message: String): Nothing = throw modelException(ModelErrorKind.PROTOCOL, message, false)

/**
 * This failure as the model error it means for a call that had [outputStarted], its kind the one [classify] gives the
 * error body where it gives one.
 */
internal fun HttpException.toModelException(
    outputStarted: Boolean,
    classify: (ErrorDetail) -> ModelErrorKind? = { null },
): ModelException {
    val detail = body?.let(::errorDetail)
    val kind = refine(detail?.let(classify) ?: kindOf(kind), status, detail)
    val error = ModelError.builder(kind, message ?: kind.toString())
        .retryAfter(retryAfter)
        .status(status)
        .requestId(requestId)
        .rawType(detail?.type)
        .outputStarted(outputStarted)
        .build()
    return ModelException(error, this)
}

/** The failure that an `error` object in a stream reports, its kind the one [classify] gives where it gives one. */
internal fun streamError(
    error: JsonElement,
    outputStarted: Boolean,
    classify: (ErrorDetail) -> ModelErrorKind? = { null },
): ModelException {
    val detail = errorDetail(error)
    val status = detail.code?.toIntOrNull()?.takeIf { it in 400..599 }
    val reported = classify(detail) ?: status?.let { kindOf(HttpFailureKind.of(it)) }
    val kind = refine(reported ?: ModelErrorKind.SERVER_ERROR, status, detail)
    val message = "The response broke off: ${detail.message ?: detail.type ?: "the backend gave no reason"}"
    val error = ModelError.builder(kind, message)
        .rawType(detail.type ?: detail.code)
        .outputStarted(outputStarted)
        .build()
    return ModelException(error)
}

/** This failure with [mask] applied to its message and raw type, itself when they hold nothing to mask. */
internal fun ModelException.masked(mask: (String) -> String): ModelException {
    val message = mask(error.message)
    val rawType = error.rawType?.let(mask)
    if (message == error.message && rawType == error.rawType) return this
    return ModelException(error.rebuild { message(message).rawType(rawType) }, cause)
        .also { it.stackTrace = stackTrace }
}

/** This failure with a sentence that its model takes only the Responses API, when its HTTP 400 says so. */
internal fun ModelException.pointedToResponses(): ModelException {
    val body = (cause as? HttpException)?.body.orEmpty()
    if (error.status != 400 || RESPONSES_PATH !in body.lowercase()) return this
    val message = "${error.message.trimEnd()} This model takes only the Responses API (/v1/responses): configure " +
        "it on an endpoint that speaks that API."
    return ModelException(error.rebuild { message(message) }, cause).also { it.stackTrace = stackTrace }
}

private const val RESPONSES_PATH = "v1/responses"

/** What an error object or body says, its parts null where it says nothing. */
internal class ErrorDetail(val message: String?, val type: String?, val code: String?)

private fun errorDetail(body: String): ErrorDetail? = try {
    errorDetail(Json.parseToJsonElement(body))
} catch (e: SerializationException) {
    null
}

private fun errorDetail(element: JsonElement): ErrorDetail {
    val error = (element as? JsonObject)?.get("error") ?: element
    if (error is JsonPrimitive) return ErrorDetail(error.content, null, null)
    val fields = error as? JsonObject ?: return ErrorDetail(null, null, null)
    val metadata = fields.obj("metadata")
    return ErrorDetail(
        fields.string("message"),
        fields.string("type") ?: metadata?.string("error_type"),
        (fields["code"] as? JsonPrimitive)?.content?.takeUnless { it == "null" },
    )
}

private fun kindOf(kind: HttpFailureKind): ModelErrorKind = when (kind) {
    HttpFailureKind.AUTHENTICATION -> ModelErrorKind.AUTHENTICATION
    HttpFailureKind.PERMISSION_DENIED -> ModelErrorKind.PERMISSION_DENIED
    HttpFailureKind.QUOTA_EXHAUSTED -> ModelErrorKind.QUOTA_EXHAUSTED
    HttpFailureKind.NOT_FOUND -> ModelErrorKind.MODEL_NOT_FOUND
    HttpFailureKind.REQUEST_TOO_LARGE -> ModelErrorKind.CONTEXT_WINDOW_EXCEEDED
    HttpFailureKind.INVALID_REQUEST -> ModelErrorKind.INVALID_REQUEST
    HttpFailureKind.RATE_LIMITED -> ModelErrorKind.RATE_LIMITED
    HttpFailureKind.OVERLOADED -> ModelErrorKind.OVERLOADED
    HttpFailureKind.SERVER_ERROR -> ModelErrorKind.SERVER_ERROR
    HttpFailureKind.TIMEOUT -> ModelErrorKind.TIMEOUT
    HttpFailureKind.CONNECTION -> ModelErrorKind.CONNECTION
    HttpFailureKind.PROTOCOL -> ModelErrorKind.PROTOCOL
}

/** [kind] made precise by what the backend says about a request it refused with [status]. */
private fun refine(kind: ModelErrorKind, status: Int?, detail: ErrorDetail?): ModelErrorKind {
    if (detail == null) return kind
    val words = listOfNotNull(detail.type, detail.code).map { it.lowercase() }
    val message = detail.message.orEmpty().lowercase()
    val refused = status == null || status in 400..499
    return when {
        words.any { it in QUOTA } -> ModelErrorKind.QUOTA_EXHAUSTED

        words.any { it in CONTENT_FILTER } -> ModelErrorKind.CONTENT_FILTERED

        refused && (words.any { it in CONTEXT } || CONTEXT_MESSAGES.any { it in message }) ->
            ModelErrorKind.CONTEXT_WINDOW_EXCEEDED

        refused && ("model_not_found" in words || ("model" in message && MISSING.any { it in message })) ->
            ModelErrorKind.MODEL_NOT_FOUND

        else -> kind
    }
}

private val QUOTA = setOf("insufficient_quota", "insufficient_balance", "billing_error")

private val CONTENT_FILTER = setOf("content_filter", "content_policy_violation")

private val CONTEXT = setOf("context_length_exceeded", "context_window_exceeded", "request_too_large")

private val CONTEXT_MESSAGES = listOf("maximum context length", "context length", "context window", "too many tokens")

private val MISSING = listOf("does not exist", "not exist", "not found")
