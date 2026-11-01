package org.unitedsoftware.alexandrite.provider.common.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.CHAT_CALL_IDS
import org.unitedsoftware.alexandrite.provider.common.ModelListing
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.putToolChoice
import org.unitedsoftware.alexandrite.provider.common.unsupportedEffort
import org.unitedsoftware.alexandrite.provider.common.wireToolName
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.FinishKind
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart

/**
 * How a family of backends speaks Chat Completions: its wire dialect and, one part per concern, what it does its own
 * way. This library may change between releases more freely than the SDK.
 */
public class ChatFlavor(
    /** The wire dialect of the flavor's models and reasoning seals. */
    public val dialect: Dialect,
    public val listing: ModelListing = ModelListing(),
    public val reasoning: ChatReasoning = ChatReasoning(),
    public val tools: ChatTools = ChatTools(),
    public val caching: ChatCaching = ChatCaching(),
    public val finishReasons: ChatFinishReasons = ChatFinishReasons(),
    public val text: ChatText = ChatText(),
) {
    public companion object {
        /** The dialect of the standard API. */
        public val OPENAI_CHAT: Dialect = Dialect("openai-chat")

        /** The standard API. */
        public val STANDARD: ChatFlavor = ChatFlavor(OPENAI_CHAT)
    }
}

/** How a backend asks a model to reason and gets the model's reasoning back to it. */
public open class ChatReasoning {
    /** Adds the fields that ask [info]'s model to reason with [effort] to [body], or returns why it adds none. */
    public open fun request(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
        if (effort !in info.reasoningEfforts) return unsupported(effort, info)
        body.put("reasoning_effort", effort.id)
        return null
    }

    /** Adds to [body] the fields of a request that sets no effort and so leaves [info]'s model its default. */
    public open fun requestNoEffort(info: ModelInfo, body: JsonObjectBuilder) {}

    /** Adds the reasoning of [parts] that [request]'s model must get back to the assistant [message]. */
    public open fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {}

    /** A reader of the next reasoning part that [origin] streams. */
    public open fun reader(origin: ModelRef): ChatReasoningReader = ChatReasoningReader()

    /** The warning that [info]'s model does not accept [effort], so the request leaves the effort out. */
    protected fun unsupported(effort: ReasoningEffort, info: ModelInfo): Warning = unsupportedEffort(effort, info)
}

/**
 * Reads one reasoning part from the raw `delta` objects of a response, beyond the text of `reasoning_content` and
 * `reasoning`.
 */
public open class ChatReasoningReader {
    /** The reasoning that the raw [delta] adds of its own, null when it adds none. */
    public open fun read(delta: JsonObject): ChatReasoningDelta? = null

    /** The seal of the part's reasoning [text], null when the model needs none of it back. */
    public open fun seal(text: String): ReasoningSeal? = null
}

/** Reasoning that one delta adds, whose [text] and [summary] are null where it shows none. */
public class ChatReasoningDelta(public val text: String? = null, public val summary: String? = null)

/** How a backend's models write the text of a response. */
public open class ChatText {
    /** A reader of the next text part that a response streams. */
    public open fun reader(): ChatTextReader = ChatTextReader()
}

/** Reads one text part as it streams, holding back text until it knows what the text is. */
public open class ChatTextReader {
    /** What the part shows of the text held back and the [text] that follows it. */
    public open fun read(text: String): ChatTextDelta = ChatTextDelta(text)

    /** The text held back, which the part shows as it ends. */
    public open fun release(): String = ""
}

/** What one delta lets a text part show. */
public class ChatTextDelta(
    public val shown: String,
    /** Why the model's output is malformed, null while it is not. */
    public val malformed: String? = null,
)

/** How a backend takes tools and names the calls of its models. */
public open class ChatTools {
    /** Whether the backend takes the `parallel_tool_calls` field. */
    public open val acceptsParallelToolCalls: Boolean = true

    /** Adds the field that asks for [request]'s tool choice to [body], or returns why it adds none. */
    public open fun toolChoice(request: ModelRequest, body: JsonObjectBuilder): Warning? =
        body.putToolChoice(request.toolChoice) { choice ->
            putJsonObject("function") { put("name", wireName(choice.name)) }
        }

    /** The tool that a call naming [wire] calls, given the [offered] tools: the inverse of [wireName]. */
    public open fun callName(wire: String, offered: List<ToolDefinition>): String =
        offered.firstOrNull { wireName(it.name) == wire }?.name ?: ToolNames.fromWire(wire) ?: wire

    /** The name that the tool [name] and its stored calls go out with, one for each name. */
    public open fun wireName(name: String): String = wireToolName(name)

    /** The id that the stored call [id] and its result go out with, one for each id. */
    public open fun wireCallId(id: ToolCallId): String = CHAT_CALL_IDS.of(id.value)

    /** The fields of the raw tool call [call] that the model needs back with it, null when it needs none. */
    public open fun readCall(call: JsonObject): JsonObject? = null

    /** Adds to [element], the object of [call] in a request's `tool_calls`, the fields that [readCall] [kept]. */
    public open fun writeCall(call: ToolCallPart, kept: JsonObject, element: JsonObjectBuilder) {}
}

/** How a backend keeps requests on one prompt cache and counts what the cache held. */
public open class ChatCaching {
    /** Adds the fields that keep the requests that share [key] on one prompt cache to [body]. */
    public open fun key(key: String, body: JsonObjectBuilder) {
        body.put("prompt_cache_key", key)
    }

    /** The prompt tokens read from the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheReadTokens(usage: JsonObject): Long? =
        usage.obj("prompt_tokens_details")?.long("cached_tokens")

    /** The prompt tokens written to the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheWriteTokens(usage: JsonObject): Long? = null
}

/** How a backend says why a response ended. */
public open class ChatFinishReasons {
    /** The kind of an end for the reason [raw]. */
    public open fun kind(raw: String): FinishKind = when (raw) {
        "stop" -> FinishKind.END_TURN
        "length" -> FinishKind.MAX_OUTPUT_TOKENS
        "tool_calls", "function_call" -> FinishKind.TOOL_USE
        "content_filter" -> FinishKind.REFUSAL
        else -> FinishKind.OTHER
    }

    /** The kind of failure that an end for the reason [raw] is, null when it is none. */
    public open fun failure(raw: String): ModelErrorKind? = null
}
