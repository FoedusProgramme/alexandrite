package org.unitedsoftware.alexandrite.sdk.tool

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.ContributedSpi
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.sandbox.Sandbox
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart
import java.nio.file.Path
import kotlin.time.Duration

/** A function the model can call. */
@ContributedSpi
public interface Tool {
    public val definition: ToolDefinition

    public suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/** What the model and the permission layer know about a [Tool]. */
@Poko
public class ToolDefinition private constructor(
    public val name: String,
    public val description: String,
    /** JSON Schema of the arguments. */
    public val parameters: JsonObject,
    public val risk: ToolRisk,
    /**
     * A tool that bounds its own calls declares the longest; the agent then waits that long plus a grace instead of
     * its default.
     */
    public val timeout: Duration?,
    /** What kind of effect a call has, for a channel to show as it runs. */
    public val activity: ToolActivity,
    /** The arguments that title a call: every one present, in order, joined with a space, an array with spaces too. */
    public val titleArguments: List<String>,
) {
    init {
        require(ToolNames.PATTERN.matches(name)) {
            "Malformed tool name '$name': it must be lowercase words of letters, digits and '_', each starting with " +
                "a letter, joined by dots, 64 characters at most, such as \"notes.add\"."
        }
        require(timeout == null || timeout.isPositive()) { "Tool '$name' declares a timeout that is not positive." }
    }

    public companion object {
        public operator fun invoke(
            name: String,
            description: String,
            parameters: JsonObject,
            risk: ToolRisk = ToolRisk.EXEC,
            timeout: Duration? = null,
            activity: ToolActivity = ToolActivity.OTHER,
            titleArguments: List<String> = emptyList(),
        ): ToolDefinition = ToolDefinition(
            name,
            description,
            parameters,
            risk,
            timeout,
            activity,
            titleArguments.toList(),
        )
    }
}

/** Where a tool call runs, whose members throw [IllegalStateException] once the call ended. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface ToolContext {
    public val turn: TurnInfo
    public val call: ToolCallId

    /** The model executing this call, null outside a model turn. */
    public val model: ModelRef? get() = null

    /** The message of the channel this turn answers, which a tool that acts on it is given. */
    public val trigger: ChannelMessageRef? get() = null

    /** The directory where the turn's agent keeps its own files, which may not exist yet, null when it has none. */
    public val workspace: Path?

    /**
     * [workspace], created when it does not exist yet.
     *
     * Throws [IllegalStateException] when the agent has no workspace, and an [java.io.IOException] when it cannot be
     * created.
     */
    public suspend fun openWorkspace(): Path

    /** The directory where the turn's own conversation keeps its own files, which may not exist yet. */
    public val conversationDirectory: Path

    /** [conversationDirectory], created when it does not exist yet. Throws an [java.io.IOException] when it cannot be. */
    public suspend fun openConversationDirectory(): Path

    /** Null when adding [bytes] to [conversationDirectory] through [path] keeps it at or under its quota. */
    public suspend fun conversationQuotaRefusal(path: Path, bytes: Long): String?

    /**
     * The background job that this call runs as, null unless its review asked for one ([CallReview.background]).
     *
     * Waits until the job is set up, which follows the call's start closely.
     */
    public suspend fun background(): BackgroundCall? = null

    /** Reports that the call read or listed [path]. */
    public fun touched(path: Path)

    /** What let the call run, null while it is being reviewed. */
    public val clearance: Clearance?

    /** Reports that the call brought untrusted content into the conversation, for [reason]. */
    public fun untrusted(reason: String)

    /** Whether the turn's conversation took in untrusted content, so far as the agent knows when it is read. */
    public val tainted: Boolean get() = false

    /**
     * A [Mandate] for turns that the tool's plugin initiates later at this turn's chat to run as the turn's operator,
     * null unless this is an operator's own turn in a conversation that took in nothing untrusted. The agent keeps it
     * until the plugin revokes it (`TurnInitiator.revoke`).
     */
    public suspend fun mandate(): Mandate? = null

    /** The sandbox that the call's commands run in, null when they run on the host. */
    public val sandbox: Sandbox? get() = null
}

/** A tool call that runs as a background job from its start, whose results come in later turns. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface BackgroundCall {
    /**
     * Wakes the agent with an intermediate [result] of the call, and returns false when it is not taken because the
     * agent has not yet taken the previous one or the job is being stopped; the call may send it again later.
     */
    public suspend fun update(result: ToolResult): Boolean
}

/** What a tool call returns to the model. */
@Poko
public class ToolResult private constructor(public val content: List<ToolOutputPart>, public val isError: Boolean) {
    public constructor(text: String, isError: Boolean = false) : this(listOf(TextPart(text)), isError)

    public companion object {
        public operator fun invoke(content: List<ToolOutputPart>, isError: Boolean = false): ToolResult =
            ToolResult(content.toList(), isError)
    }
}
