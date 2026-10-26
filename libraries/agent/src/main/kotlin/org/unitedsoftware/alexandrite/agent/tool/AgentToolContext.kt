package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.permission.Principal
import org.unitedsoftware.alexandrite.agent.sandbox.CallSandboxes
import org.unitedsoftware.alexandrite.agent.trust.CallTrust
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.sandbox.Sandbox
import org.unitedsoftware.alexandrite.sdk.tool.BackgroundCall
import org.unitedsoftware.alexandrite.sdk.tool.Mandate
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** The context of the call [call] in [turn], cleared by [clearance], which holds only until [close]. */
internal class AgentToolContext(
    turn: TurnInfo,
    call: ToolCallId,
    private val workspaces: Workspaces,
    private val directories: ConversationDirectories,
    clearance: Clearance?,
    val taint: TurnTaint,
    /** The message of the channel this turn answers, which a tool that acts on it is given. */
    trigger: ChannelMessageRef?,
    /** What a sub-agent run that the call starts takes from its turn, null outside a turn. */
    val delegating: DelegatingTurn? = null,
    /** Whether the call's review asked for it to run as a background job from its start. */
    val startsInBackground: Boolean = false,
    /** Grants the call's plugin a mandate of the turn's operator, null when the call may get none. */
    private val granting: (suspend () -> Mandate?)? = null,
    /** How far the call's output is trusted. */
    val trust: CallTrust = CallTrust.NONE,
    /** The file that the call writes, which brings nothing into the conversation, null when it writes none. */
    private val written: Path? = null,
    sandboxes: CallSandboxes? = null,
) : ToolContext {
    @Volatile
    private var open = true

    private val job = CompletableDeferred<BackgroundCall>()

    override suspend fun background(): BackgroundCall? {
        checkOpen()
        if (!startsInBackground) return null
        return job.await().also { checkOpen() }
    }

    /**
     * Sets up the call's background job, whose updates go to [updates] and whose untrusted content goes to [untrusted]
     * as the call reports it from then on.
     */
    fun attachBackground(updates: suspend (ToolResult) -> Boolean, untrusted: (String) -> Unit = {}) {
        detached = untrusted
        job.complete(
            object : BackgroundCall {
                override suspend fun update(result: ToolResult): Boolean {
                    checkOpen()
                    return updates(result)
                }
            },
        )
    }

    private val reported = CopyOnWriteArrayList<Path>()

    override val turn: TurnInfo = turn
        get() = field.also { checkOpen() }

    override val call: ToolCallId = call
        get() = field.also { checkOpen() }

    override val model: ModelRef?
        get() = delegating?.model.also { checkOpen() }

    override val workspace: Path?
        get() = workspaces.paths[turn.agent]

    override val trigger: ChannelMessageRef? = trigger

    override val conversationDirectory: Path
        get() = directories.path(turn)

    override val clearance: Clearance? = clearance
        get() = field.also { checkOpen() }

    override val sandbox: Sandbox? = sandboxes?.sandbox(turn, clearance)
        get() = field.also { checkOpen() }

    override suspend fun openWorkspace(): Path {
        val agent = turn.agent
        val path = withContext(Dispatchers.IO) { workspaces.open(agent) }
        return checkNotNull(path) { "Agent '$agent' has no workspace." }
    }

    override suspend fun openConversationDirectory(): Path = withContext(Dispatchers.IO) { directories.open(turn) }

    override suspend fun conversationQuotaRefusal(path: Path, bytes: Long): String? =
        directories.quotaRefusal(conversationDirectory, path, bytes)

    /** The paths that the call reported as touched, each once, in order. */
    val touchedPaths: List<Path> get() = reported.distinct()

    /** The [touchedPaths] that may taint the conversation, the written file left out; none for a call trusted whole. */
    val taintingPaths: List<Path>
        get() = if (trust == CallTrust.WHOLE) emptyList() else touchedPaths.filterNot { it.isWritten(written) }

    private val loading = CopyOnWriteArrayList<UserEntry>()

    /** The trusted instructions that the call loads, added to the conversation once its round ends. */
    val loads: List<UserEntry> get() = loading.toList()

    /** Loads [entry], trusted instructions that Alexandrite wrote, into the conversation once the round ends. */
    fun load(entry: UserEntry) {
        checkOpen()
        loading.add(entry)
    }

    override fun touched(path: Path) {
        checkOpen()
        reported.add(path)
    }

    override fun untrusted(reason: String) {
        checkOpen()
        if (trust != CallTrust.NONE) return
        report("call $call reported untrusted content: $reason")
    }

    override val tainted: Boolean
        get() = taint.tainted.also { checkOpen() }

    private val own = AtomicReference<String?>(null)

    @Volatile
    private var detached: ((String) -> Unit)? = null

    /** The first untrusted content that the call took in itself, null when it took in none. */
    val reportedUntrusted: String? get() = own.get()

    /** Counts [reason] as untrusted content that the call took in, whatever its trust. */
    fun report(reason: String) {
        taint.note(reason)
        if (own.compareAndSet(null, reason)) detached?.invoke(reason)
    }

    override suspend fun mandate(): Mandate? {
        checkOpen()
        val principal = Principal.of(turn)
        if (principal != Principal.OPERATOR || turn.lineage != null || turn.kind != TurnKind.MESSAGE) return null
        if (taint.tainted) return null
        return granting?.invoke()
    }

    fun close() {
        open = false
    }

    private fun checkOpen() {
        check(open) { "A tool context is valid only while its call runs." }
    }
}

/** Whether this path is [written], the file that a call writes. */
internal fun Path.isWritten(written: Path?): Boolean = written != null && normalize() == written.normalize()
