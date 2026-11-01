@file:OptIn(ExperimentalCompilerApi::class)

package org.unitedsoftware.alexandrite.sdk

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubclassOptInTest {
    @TempDir
    lateinit var workingDir: File

    private val implementations = """
        class Files : org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles {
            override val dataDir get() = TODO()
            override val cacheDir get() = TODO()
        }

        class Models : org.unitedsoftware.alexandrite.sdk.web.WebModels {
            override suspend fun answer(question: org.unitedsoftware.alexandrite.sdk.web.PageQuestion): String = TODO()

            override suspend fun search(
                model: org.unitedsoftware.alexandrite.sdk.transcript.ModelRef,
                query: org.unitedsoftware.alexandrite.sdk.web.WebQuery,
            ): List<org.unitedsoftware.alexandrite.sdk.web.WebSearchResult>? = TODO()
        }

        class Background : org.unitedsoftware.alexandrite.sdk.tool.BackgroundCall {
            override suspend fun update(result: org.unitedsoftware.alexandrite.sdk.tool.ToolResult): Boolean = TODO()
        }

        abstract class Index : org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex

        class Control : org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl {
            override fun stop(request: org.unitedsoftware.alexandrite.sdk.runtime.StopRequest) = TODO()
        }

        class Paths : org.unitedsoftware.alexandrite.sdk.runtime.HostPaths {
            override val dataRoot get() = TODO()
            override val pluginDataRoot get() = TODO()
            override val cacheRoot get() = TODO()
            override val configFile get() = TODO()
            override val configTarget get() = TODO()
            override val configBackup get() = TODO()
            override val protected get() = TODO()
        }

        class Firing : org.unitedsoftware.alexandrite.sdk.hook.Hooks {
            override suspend fun <P : Any> fire(
                point: org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint<P>,
                payload: P,
            ): org.unitedsoftware.alexandrite.sdk.hook.Interception<P> = TODO()

            override suspend fun <P : Any> fire(
                point: org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint<P>,
                payload: P,
            ): Unit = TODO()
        }

        class Context : org.unitedsoftware.alexandrite.sdk.tool.ToolContext {
            override val turn get() = TODO()
            override val call get() = TODO()
            override val workspace get() = TODO()
            override val conversationDirectory get() = TODO()
            override val clearance get() = TODO()
            override suspend fun openWorkspace() = TODO()
            override suspend fun openConversationDirectory() = TODO()
            override suspend fun conversationQuotaRefusal(path: java.nio.file.Path, bytes: Long) = TODO()
            override fun touched(path: java.nio.file.Path) = TODO()
            override fun untrusted(reason: String) = TODO()
        }

        class Preview : org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview

        class Contained : org.unitedsoftware.alexandrite.sdk.sandbox.Sandbox {
            override val environment get() = TODO()
            override val strict get() = TODO()
            override fun boundary(directories: List<java.nio.file.Path>) = TODO()
            override fun hostProblem(hosts: List<String>) = TODO()
            override suspend fun open(
                boundary: org.unitedsoftware.alexandrite.sdk.sandbox.Boundary,
                hosts: List<String>,
            ) = TODO()
        }

        class Lease : org.unitedsoftware.alexandrite.sdk.sandbox.NetworkLease {
            override val boundary get() = TODO()
            override val refused get() = TODO()
            override fun close() = TODO()
        }

        class Activity : org.unitedsoftware.alexandrite.sdk.channel.TurnEvent

        class ActivityContent : org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.Content

        class Answer : org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer

        class Floor : org.unitedsoftware.alexandrite.sdk.tool.HardFloor {
            override fun checkRead(
                path: java.nio.file.Path,
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?,
                conversation: org.unitedsoftware.alexandrite.sdk.chat.ConversationId?,
            ) = TODO()

            override fun checkWrite(
                path: java.nio.file.Path,
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?,
                conversation: org.unitedsoftware.alexandrite.sdk.chat.ConversationId?,
            ) = TODO()

            override fun checkTree(
                path: java.nio.file.Path,
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?,
                conversation: org.unitedsoftware.alexandrite.sdk.chat.ConversationId?,
            ) = TODO()
        }

        class States : org.unitedsoftware.alexandrite.sdk.chat.ChatStates {
            override fun <T : Any> state(
                name: String,
                serializer: kotlinx.serialization.KSerializer<T>,
                default: T,
            ): org.unitedsoftware.alexandrite.sdk.chat.ChatState<org.unitedsoftware.alexandrite.sdk.chat.ChatAddress, T> =
                TODO()

            override fun <T : Any> agentState(
                name: String,
                serializer: kotlinx.serialization.KSerializer<T>,
                default: T,
            ): org.unitedsoftware.alexandrite.sdk.chat.ChatState<org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey, T> =
                TODO()
        }

        class State : org.unitedsoftware.alexandrite.sdk.chat.ChatState<String, Int> {
            override suspend fun get(key: String) = TODO()
            override suspend fun getOwn(key: String) = TODO()
            override suspend fun update(key: String, transform: (Int) -> Int) = TODO()
            override suspend fun set(key: String, value: Int) = TODO()
            override suspend fun reset(key: String) = TODO()
        }

        class Kept : org.unitedsoftware.alexandrite.sdk.store.Records {
            override fun <T : Any> collection(
                name: String,
                serializer: kotlinx.serialization.KSerializer<T>,
            ): org.unitedsoftware.alexandrite.sdk.store.RecordCollection<T> = TODO()
        }

        class Collection : org.unitedsoftware.alexandrite.sdk.store.RecordCollection<Int> {
            override suspend fun get(key: String) = TODO()
            override suspend fun put(
                key: String,
                value: Int,
                by: org.unitedsoftware.alexandrite.sdk.chat.UserAddress?,
            ) = TODO()
            override suspend fun remove(key: String) = TODO()
            override suspend fun list() = TODO()
        }

        class Localized : org.unitedsoftware.alexandrite.sdk.i18n.Texts {
            override fun text(
                key: String,
                language: org.unitedsoftware.alexandrite.sdk.chat.LanguageTag?,
                vararg args: Pair<String, Any?>,
            ) = TODO()
        }

        class Snapshot : org.unitedsoftware.alexandrite.sdk.config.ConfigSnapshot {
            override fun <T : Any> section(key: org.unitedsoftware.alexandrite.sdk.di.Key<T>): T? = TODO()
        }

        class Host : org.unitedsoftware.alexandrite.sdk.config.HostConfig {
            override suspend fun get(path: String) = TODO()
            override suspend fun edit(block: org.unitedsoftware.alexandrite.sdk.config.ConfigEdit.() -> Unit) = TODO()
            override suspend fun replace(text: String) = TODO()
        }

        class Edit : org.unitedsoftware.alexandrite.sdk.config.ConfigEdit {
            override fun get(path: String) = TODO()
            override fun set(path: String, value: kotlinx.serialization.json.JsonElement) = TODO()
            override fun remove(path: String) = TODO()
        }


        class Instance : org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance {
            override val id get() = TODO()
            override val scope get() = TODO()
        }

        class Directory : org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory {
            override val instances get() = TODO()
            override fun channel(instance: org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId) = TODO()
        }

        class Ended : org.unitedsoftware.alexandrite.sdk.hook.Interception<Nothing>

        class Failure : org.unitedsoftware.alexandrite.sdk.hook.HookFailure

        class Entry : org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry {
            override val record get() = TODO()
            override fun withRecord(record: org.unitedsoftware.alexandrite.sdk.transcript.EntryRecord) = TODO()
        }

        class Origin : org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin

        class Piece : org.unitedsoftware.alexandrite.sdk.transcript.Part

        class Input : org.unitedsoftware.alexandrite.sdk.transcript.UserPart

        class Output : org.unitedsoftware.alexandrite.sdk.transcript.ToolOutputPart

        class Reply : org.unitedsoftware.alexandrite.sdk.transcript.AssistantPart

        class Source : org.unitedsoftware.alexandrite.sdk.transcript.MediaSource

        class Outcome : org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome {
            override val isError get() = TODO()
        }

        class Event : org.unitedsoftware.alexandrite.sdk.model.ModelEvent

        class Choice : org.unitedsoftware.alexandrite.sdk.model.ToolChoice

        class Channels : org.unitedsoftware.alexandrite.sdk.channel.ChannelControl {
            override fun state(instance: org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId) = TODO()
            override suspend fun stop(
                instance: org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
        }

        class Submitter : org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter {
            override fun submit(submission: org.unitedsoftware.alexandrite.sdk.turn.Submission) = TODO()
        }

        class Ticket : org.unitedsoftware.alexandrite.sdk.turn.TurnTicket {
            override val turn get() = TODO()
            override suspend fun outcome() = TODO()
            override fun cancel() = TODO()
        }

        class Ending : org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome {
            override val replayable get() = TODO()
        }

        class Initiator : org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator {
            override fun initiate(turn: org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn) = TODO()
        }

        class Initiation : org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation {
            override fun initiate(plugin: String, turn: org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn) = TODO()
        }

        class Route : org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute

        class CommandRun : org.unitedsoftware.alexandrite.sdk.turn.CommandContext {
            override val turn get() = TODO()
            override suspend fun reply(
                text: String,
                markup: org.unitedsoftware.alexandrite.sdk.channel.Markup,
                forModel: Boolean,
            ) = TODO()
        }

        class Turns : org.unitedsoftware.alexandrite.sdk.turn.TurnControl {
            override suspend fun turns(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress?) = TODO()
            override fun cancel(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?,
            ) = TODO()
            override fun cancelTurn(
                turn: org.unitedsoftware.alexandrite.sdk.chat.TurnId,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override fun cancelRun(
                run: org.unitedsoftware.alexandrite.sdk.chat.RunId,
                tree: Boolean,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override fun jobs(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress?) = TODO()
            override fun stopJob(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress?,
                job: org.unitedsoftware.alexandrite.sdk.chat.RunId,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
        }

        class Conversations : org.unitedsoftware.alexandrite.sdk.turn.ConversationControl {
            override suspend fun newConversation(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun compact(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                focus: String?,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun context(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress) = TODO()
            override suspend fun untaint(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?,
            ) = TODO()
            override suspend fun untaintHeartbeat(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?,
            ) = TODO()
        }

        class Settings : org.unitedsoftware.alexandrite.sdk.turn.ChatSettings {
            override suspend fun snapshot(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress) = TODO()
            override suspend fun update(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                update: org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
        }

        class Links : org.unitedsoftware.alexandrite.sdk.turn.ChatLinks {
            override suspend fun link(
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId,
                anchor: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                member: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun unlink(
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId,
                member: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override fun groups(agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?) = TODO()
        }

        class Trust : org.unitedsoftware.alexandrite.sdk.turn.TrustControl {
            override suspend fun folders(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress) = TODO()
            override suspend fun trustFolder(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                folder: java.nio.file.Path,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun untrustFolder(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                folder: java.nio.file.Path,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun rules(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress) = TODO()
            override suspend fun trustRule(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                rule: String,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun untrustRule(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                rule: String,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun people(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress) = TODO()
            override suspend fun trustPerson(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                person: org.unitedsoftware.alexandrite.sdk.chat.ChatUser,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun untrustPerson(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                person: org.unitedsoftware.alexandrite.sdk.chat.ChatUser,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
            override suspend fun taint(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress) = TODO()
        }

        class Rules : org.unitedsoftware.alexandrite.sdk.turn.PermissionRules {
            override suspend fun allowRules(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress) = TODO()
            override suspend fun revokeAllowRule(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                number: Int,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
        }

        class Catalog : org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog {
            override fun agents(instance: org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId) = TODO()
            override suspend fun profiles() = TODO()
            override fun endpoints() = TODO()
            override suspend fun models(endpoint: org.unitedsoftware.alexandrite.sdk.transcript.EndpointId) = TODO()
        }

        class Usage : org.unitedsoftware.alexandrite.sdk.turn.UsageReports {
            override suspend fun report(chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress, everyAgent: Boolean) =
                TODO()
        }

        class History : org.unitedsoftware.alexandrite.sdk.turn.ChatHistory {
            override suspend fun chats(agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId) = TODO()
            override suspend fun entries(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                after: org.unitedsoftware.alexandrite.sdk.transcript.EntryId?,
                limit: Int,
                agent: org.unitedsoftware.alexandrite.sdk.chat.AgentId?,
            ) = TODO()
        }

        class Questions : org.unitedsoftware.alexandrite.sdk.turn.SideQuestions {
            override suspend fun ask(
                chat: org.unitedsoftware.alexandrite.sdk.chat.ChatAddress,
                question: String,
                by: org.unitedsoftware.alexandrite.sdk.chat.ChatUser?,
            ) = TODO()
        }
    """.trimIndent()

    /** `when`s over open hierarchies and enumerations, each with an `ELSE` line. */
    private val consumers = """
        import org.unitedsoftware.alexandrite.sdk.model.*

        fun event(event: ModelEvent): Int = when (event) {
            is ModelEvent.ResponseStarted -> 0
            is ModelEvent.TextDelta -> 1
            is ModelEvent.ReasoningDelta -> 2
            is ModelEvent.ReasoningSealed -> 3
            is ModelEvent.ToolCallStarted -> 4
            is ModelEvent.ToolArgumentsDelta -> 5
            is ModelEvent.PartCompleted -> 6
            is ModelEvent.UsageUpdated -> 7
            is ModelEvent.Completed -> 8
            ELSE
        }

        fun choice(choice: ToolChoice): Int = when (choice) {
            ToolChoice.Auto -> 0
            ToolChoice.None -> 1
            ToolChoice.Required -> 2
            is ToolChoice.Named -> 3
            ELSE
        }

        fun effort(effort: ReasoningEffort): Int = when (effort) {
            ReasoningEffort.NONE -> 0
            ReasoningEffort.MINIMAL -> 1
            ReasoningEffort.LOW -> 2
            ReasoningEffort.MEDIUM -> 3
            ReasoningEffort.HIGH -> 4
            ReasoningEffort.XHIGH -> 5
            ReasoningEffort.MAX -> 6
            ELSE
        }

        fun finish(kind: FinishKind): Int = when (kind) {
            FinishKind.END_TURN -> 0
            FinishKind.TOOL_USE -> 1
            FinishKind.STOP_SEQUENCE -> 2
            FinishKind.MAX_OUTPUT_TOKENS -> 3
            FinishKind.CONTEXT_WINDOW_EXCEEDED -> 4
            FinishKind.PAUSED -> 5
            FinishKind.REFUSAL -> 6
            FinishKind.OTHER -> 7
            ELSE
        }

        fun error(kind: ModelErrorKind): Int = when (kind) {
            ModelErrorKind.AUTHENTICATION -> 0
            ModelErrorKind.PERMISSION_DENIED -> 1
            ModelErrorKind.QUOTA_EXHAUSTED -> 2
            ModelErrorKind.RATE_LIMITED -> 3
            ModelErrorKind.OVERLOADED -> 4
            ModelErrorKind.SERVER_ERROR -> 5
            ModelErrorKind.TIMEOUT -> 6
            ModelErrorKind.CONNECTION -> 7
            ModelErrorKind.INVALID_REQUEST -> 8
            ModelErrorKind.CONTEXT_WINDOW_EXCEEDED -> 9
            ModelErrorKind.MODEL_NOT_FOUND -> 10
            ModelErrorKind.CONTENT_FILTERED -> 11
            ModelErrorKind.UNSUPPORTED -> 12
            ModelErrorKind.PROTOCOL -> 13
            ModelErrorKind.MALFORMED_OUTPUT -> 14
            ELSE
        }

        fun failure(kind: org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure): Int = when (kind) {
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.FORBIDDEN -> 0
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.CHAT_GONE -> 1
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.WINDOW_CLOSED -> 2
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.RATE_LIMITED -> 3
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.TOO_LONG -> 4
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.UNSUPPORTED -> 5
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.TRANSIENT -> 6
            org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure.UNKNOWN -> 7
            ELSE
        }

        fun markup(markup: org.unitedsoftware.alexandrite.sdk.channel.Markup): Int = when (markup) {
            org.unitedsoftware.alexandrite.sdk.channel.Markup.PLAIN -> 0
            org.unitedsoftware.alexandrite.sdk.channel.Markup.MARKDOWN -> 1
            ELSE
        }

        fun end(end: org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd): Int = when (end) {
            org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd.CANCELLED -> 0
            org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd.FAILED -> 1
            org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd.SHUTDOWN -> 2
            ELSE
        }

        fun instance(state: org.unitedsoftware.alexandrite.sdk.channel.InstanceState): Int = when (state) {
            org.unitedsoftware.alexandrite.sdk.channel.InstanceState.STARTING -> 0
            org.unitedsoftware.alexandrite.sdk.channel.InstanceState.OPEN -> 1
            org.unitedsoftware.alexandrite.sdk.channel.InstanceState.STOPPING -> 2
            org.unitedsoftware.alexandrite.sdk.channel.InstanceState.STOPPED -> 3
            ELSE
        }

        fun outcome(outcome: org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome): Int = when (outcome) {
            is org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome.Completed -> 0
            is org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome.Absorbed -> 1
            org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome.TakenBack -> 2
            org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome.Cancelled -> 3
            is org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome.Failed -> 4
            is org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome.ShutDown -> 5
            ELSE
        }

        fun route(route: org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute): Int = when (route) {
            org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute.None -> 0
            is org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute.ToChat -> 1
            is org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute.AgentHomes -> 2
            is org.unitedsoftware.alexandrite.sdk.turn.ReplyRoute.Broadcast -> 3
            ELSE
        }

        fun refusal(reason: org.unitedsoftware.alexandrite.sdk.turn.RefusalReason): Int = when (reason) {
            org.unitedsoftware.alexandrite.sdk.turn.RefusalReason.QUEUE_FULL -> 0
            org.unitedsoftware.alexandrite.sdk.turn.RefusalReason.SHUTTING_DOWN -> 1
            org.unitedsoftware.alexandrite.sdk.turn.RefusalReason.UNKNOWN_CHAT -> 2
            org.unitedsoftware.alexandrite.sdk.turn.RefusalReason.NO_AGENT -> 3
            ELSE
        }

        fun phase(phase: org.unitedsoftware.alexandrite.sdk.turn.TurnPhase): Int = when (phase) {
            org.unitedsoftware.alexandrite.sdk.turn.TurnPhase.QUEUED -> 0
            org.unitedsoftware.alexandrite.sdk.turn.TurnPhase.RUNNING -> 1
            org.unitedsoftware.alexandrite.sdk.turn.TurnPhase.FINISHING -> 2
            ELSE
        }

        fun source(source: org.unitedsoftware.alexandrite.sdk.turn.AgentSource): Int = when (source) {
            org.unitedsoftware.alexandrite.sdk.turn.AgentSource.SELECTED -> 0
            org.unitedsoftware.alexandrite.sdk.turn.AgentSource.HOME -> 1
            org.unitedsoftware.alexandrite.sdk.turn.AgentSource.DEFAULT -> 2
            ELSE
        }

        fun refusal(refusal: org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal): Int = when (refusal) {
            org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.NO_FILE -> 0
            org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.READ_ONLY -> 1
            org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.INVALID_FILE -> 2
            org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.INVALID_PATH -> 3
            org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.INVALID_RESULT -> 4
            org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.SECRET -> 5
            org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.IO_ERROR -> 6
            ELSE
        }

        fun state(state: org.unitedsoftware.alexandrite.sdk.store.ConversationState): Int = when (state) {
            org.unitedsoftware.alexandrite.sdk.store.ConversationState.ACTIVE -> 0
            org.unitedsoftware.alexandrite.sdk.store.ConversationState.SEALED -> 1
            ELSE
        }

        fun mode(mode: org.unitedsoftware.alexandrite.sdk.permission.PermissionMode): Int = when (mode) {
            org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.PLAN -> 0
            org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.MANUAL -> 1
            org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.ASK -> 2
            org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.ALLOW_EDIT -> 3
            org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.AUTO -> 4
            org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.BYPASS -> 5
            ELSE
        }

        fun clearance(kind: org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind): Int = when (kind) {
            org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind.NONE -> 0
            org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind.MODE -> 1
            org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind.RULE -> 2
            org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind.JUDGE -> 3
            org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind.HUMAN -> 4
            ELSE
        }

        fun preview(preview: org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview): Int = when (preview) {
            is org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview.Diff -> 0
            is org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview.Command -> 1
            is org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview.Text -> 2
            ELSE
        }

        fun answer(answer: org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer): Int = when (answer) {
            is org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer.Allowed -> 0
            is org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer.Denied -> 1
            org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer.Expired -> 2
            is org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer.Unavailable -> 3
            ELSE
        }

        fun turnEvent(event: org.unitedsoftware.alexandrite.sdk.channel.TurnEvent): Int = when (event) {
            is org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.ToolCallStarted -> 0
            is org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.ToolCallUpdated -> 1
            is org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.ThoughtChunk -> 2
            is org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.UsageUpdated -> 3
            is org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.Compacted -> 4
            ELSE
        }

        fun eventContent(content: org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.Content): Int = when (content) {
            is org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.Content.Preview -> 0
            is org.unitedsoftware.alexandrite.sdk.channel.TurnEvent.Content.Output -> 1
            ELSE
        }

        fun admission(admission: org.unitedsoftware.alexandrite.sdk.turn.Admission): Int = when (admission) {
            is org.unitedsoftware.alexandrite.sdk.turn.Admission.Accepted -> 0
            is org.unitedsoftware.alexandrite.sdk.turn.Admission.Refused -> 1
            ELSE
        }

        fun floor(check: org.unitedsoftware.alexandrite.sdk.tool.FloorCheck): Int = when (check) {
            is org.unitedsoftware.alexandrite.sdk.tool.FloorCheck.Allowed -> 0
            is org.unitedsoftware.alexandrite.sdk.tool.FloorCheck.Ask -> 1
            is org.unitedsoftware.alexandrite.sdk.tool.FloorCheck.Denied -> 2
            ELSE
        }

        fun network(access: org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess): Int = when (access) {
            org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess.None -> 0
            is org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess.Proxy -> 1
            ELSE
        }

        fun root(kind: org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind): Int = when (kind) {
            org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind.FOLDER -> 0
            org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind.DOMAIN -> 1
            org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind.PROGRAM -> 2
            org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind.TOOL_SOURCE -> 3
            org.unitedsoftware.alexandrite.sdk.channel.TrustRootKind.PERSON -> 4
            ELSE
        }

        fun activity(activity: org.unitedsoftware.alexandrite.sdk.tool.ToolActivity): Int = when (activity) {
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.READ -> 0
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.LIST -> 1
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.SEARCH -> 2
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.EDIT -> 3
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.DELETE -> 4
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.MOVE -> 5
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.EXECUTE -> 6
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.FETCH -> 7
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.THINK -> 8
            org.unitedsoftware.alexandrite.sdk.tool.ToolActivity.OTHER -> 9
            ELSE
        }

        fun callStatus(status: org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus): Int = when (status) {
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.PENDING -> 0
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.WAITING_APPROVAL -> 1
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.RUNNING -> 2
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.BACKGROUNDED -> 3
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.COMPLETED -> 4
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.FAILED -> 5
            org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus.NOT_RUN -> 6
            ELSE
        }

        fun eventKind(kind: org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind): Int = when (kind) {
            org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind.TOOL_CALL_STARTED -> 0
            org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind.TOOL_CALL_UPDATED -> 1
            org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind.THOUGHT_CHUNK -> 2
            org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind.USAGE_UPDATED -> 3
            org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind.COMPACTED -> 4
            ELSE
        }
    """.trimIndent()

    /** A channel as a third-party plugin writes it. */
    private val channel = """
        import org.unitedsoftware.alexandrite.sdk.channel.*
        import org.unitedsoftware.alexandrite.sdk.chat.*

        class Console : Channel {
            override suspend fun capabilities(chat: ChatAddress) =
                ChannelCapabilities.builder().streaming(true).markups(setOf(Markup.PLAIN, Markup.MARKDOWN)).build()

            override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup) = 1

            override suspend fun openReply(request: ReplyRequest): ReplySink = object : ReplySink {
                override suspend fun preview(segment: Int, text: String) = print(text)

                override suspend fun startSegment(segment: Int) = println()

                override suspend fun complete(message: OutboundMessage): Delivery =
                    Delivery.Delivered(listOfNotNull(request.trigger))

                override suspend fun abandon(end: ReplyEnd, vouched: Boolean) = println(end)
            }

            override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery =
                if (message.kind == MessageKind.REPLY || message.vouched) Delivery.Delivered(emptyList())
                else Delivery.NotDelivered(DeliveryFailure.UNSUPPORTED, "notices are not shown")
            override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer {
                println("Allow " + request.tool + " " + request.arguments + "? (y/n)")
                val operator = request.turn.actor ?: return ApprovalAnswer.Unavailable("nobody sits at the console")
                return when (readlnOrNull()) {
                    "y" -> ApprovalAnswer.Allowed(operator, always = request.alwaysRules.isNotEmpty())
                    "t" -> ApprovalAnswer.Allowed(
                        operator,
                        trust = if (request.roots.isEmpty()) ApprovalTrust.OUTPUT_ONCE else ApprovalTrust.ROOTS,
                    )
                    "n" -> ApprovalAnswer.Denied(operator)
                    null -> ApprovalAnswer.Expired
                    else -> ApprovalAnswer.Unavailable("the answer was neither y nor n")
                }
            }
        }

        object Silent : ReplySink {
            override suspend fun preview(segment: Int, text: String) {}

            override suspend fun complete(message: OutboundMessage): Delivery = Delivery.Delivered(emptyList())

            override suspend fun abandon(end: ReplyEnd, vouched: Boolean) {}
        }

        val content = MediaContent { maxBytes -> ByteArray(minOf(maxBytes, 4L).toInt()) }
    """.trimIndent()

    /** A model provider as a third-party plugin writes it. */
    private val provider = """
        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.flow
        import org.unitedsoftware.alexandrite.sdk.model.*
        import org.unitedsoftware.alexandrite.sdk.transcript.*

        class Echo : ModelEndpoint {
            override val id = EndpointId("echo")

            override suspend fun models() =
                listOf(ModelInfo.builder("echo-1", Dialect("echo")).nativeTools(true).streaming(true).build())

            override fun stream(request: ModelRequest): Flow<ModelEvent> = flow {
                if (request.tools.isEmpty()) {
                    throw ModelException(ModelError.builder(ModelErrorKind.UNSUPPORTED, "No tools.").build())
                }
                emit(ModelEvent.ResponseStarted(null, request.model.model, emptyList()))
                emit(ModelEvent.TextDelta(0, "Hi"))
                emit(ModelEvent.PartCompleted(0, TextPart("Hi")))
                val call = ToolCallPart(request.ids.callId(1), request.tools.first().name, "{}")
                emit(ModelEvent.ToolCallStarted(1, call.id, call.name))
                emit(ModelEvent.PartCompleted(1, call))
                val usage = Usage.builder().inputTokens(3).outputTokens(2).contextTokens(3).build()
                val message = AssistantEntry(null, listOf(TextPart("Hi"), call), request.model)
                emit(ModelEvent.Completed(message, FinishReason(FinishKind.TOOL_USE, "tool_calls", null), usage))
            }

            override fun turnContextMode(model: String, options: ModelOptions, trust: Trust) =
                if (trust == Trust.TRUSTED) TurnContextMode.TRANSIENT else TurnContextMode.NOT_SUPPORTED
        }

        class EchoProvider : ModelProvider {
            override val endpoints = listOf(Echo())
        }
    """.trimIndent()

    /** A store backend as a third-party plugin writes it. */
    private val store = """
        import org.unitedsoftware.alexandrite.sdk.chat.*
        import org.unitedsoftware.alexandrite.sdk.di.*
        import org.unitedsoftware.alexandrite.sdk.store.*
        import org.unitedsoftware.alexandrite.sdk.transcript.*
        import java.time.Instant

        @Singleton @Binds(ConversationStore::class)
        class Conversations : ConversationStore {
            override suspend fun current(key: AgentChatKey) = info(key, ConversationKind.USER_LANE)
            override suspend fun heartbeatBase(key: AgentChatKey) = info(key, ConversationKind.of("heartbeat_base"))
            override suspend fun newConversation(key: AgentChatKey) = Rotation(null, info(key, ConversationKind.USER_LANE))
            override suspend fun createDelegated(key: AgentChatKey, lineage: TurnLineage, fork: ForkPoint?) =
                info(key, ConversationKind.DELEGATED).toBuilder().lineage(lineage).fork(fork).build()
            override suspend fun sealDelegated(id: ConversationId) = false
            override suspend fun conversation(id: ConversationId): ConversationInfo? = null
            override suspend fun chats(agent: AgentId) = listOf(ChatAddress.parse("t:a:b"))
            override suspend fun startTurn(turn: TurnInfo) {}
            override suspend fun endTurn(id: TurnId, end: TurnEndKind) = end in TurnEndKind.entries
            override suspend fun turn(id: TurnId): TurnRecord? =
                TurnRecord.builder(id, ConversationId("c"), AgentChatKey.parse("main@t:a:b"), TurnKind.of("x"), Instant.EPOCH)
                    .end(TurnEndKind.of("completed"))
                    .build()
            override suspend fun unendedTurns() = listOfNotNull(turn(TurnId("t")))
            override suspend fun markTainted(conversation: ConversationId, reason: String, at: Instant) = false
            override suspend fun taint(conversation: ConversationId) = ConversationTaint(Instant.EPOCH, "a test")
            override suspend fun clearTaint(conversation: ConversationId): ConversationTaint? = null

            private fun info(key: AgentChatKey, kind: ConversationKind) =
                ConversationInfo.builder(ConversationId("c"), kind, key, ConversationState.ACTIVE, Instant.EPOCH).build()
        }

        @Singleton @Binds(TranscriptStore::class)
        class Transcripts : TranscriptStore {
            override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>) = entries.mapIndexed { index, it ->
                it.withRecord(EntryRecord(EntryId(index + 1L), ConversationId("c"), turn, Instant.EPOCH))
            }
            override suspend fun entries(conversation: ConversationId) = listOf(
                TranscriptCodec.decode("{\"type\":\"poll\"}"),
                UnknownEntry(null, "poll", kotlinx.serialization.json.JsonObject(emptyMap())),
            )
            override suspend fun entriesAfter(conversation: ConversationId, after: EntryId) =
                entries(conversation).filter { (it.record?.id?.value ?: 0) > after.value }
            override suspend fun tail(conversation: ConversationId, count: Int) = entries(conversation).takeLast(count)
            override suspend fun turnEntries(turn: TurnId) = emptyList<TranscriptEntry>()
            override suspend fun entry(id: EntryId): TranscriptEntry? = null
            override suspend fun entries(message: ChannelMessageRef) = emptyList<TranscriptEntry>()
            override suspend fun deleteMessage(message: ChannelMessageRef) = 0
        }

        @Singleton @Binds(MediaStore::class)
        class Media : MediaStore {
            override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String) = StoredMedia(MediaId("m"))
            override suspend fun read(id: MediaId): ByteArray? = null
            override suspend fun info(id: MediaId) = MediaInfo(id, MediaKind.of("image"), "image/png", 0, "", Instant.EPOCH)
        }

        @Singleton @Binds(ChatStateStore::class)
        class States : ChatStateStore {
            override suspend fun read(plugin: String, name: String, agent: AgentId?, chat: ChatAddress): String? = null
            override suspend fun write(plugin: String, name: String, agent: AgentId?, chat: ChatAddress, json: String?) {}
        }

        @Singleton @Binds(RecordStore::class)
        class Kept : RecordStore {
            override suspend fun get(plugin: String, collection: String, key: String): StoredRecord<String>? = null
            override suspend fun put(plugin: String, collection: String, key: String, json: String, by: UserAddress?) =
                StoredRecord(key, json, Instant.EPOCH, by, Instant.EPOCH, by)
            override suspend fun remove(plugin: String, collection: String, key: String) = false
            override suspend fun list(plugin: String, collection: String) = emptyList<StoredRecord<String>>()
        }

        suspend fun inline(media: MediaStore, entry: TranscriptEntry) = media.storeInline(entry)
    """.trimIndent()

    /** A command plugin, a hook and a channel's submission as a third-party plugin writes them. */
    private val turns = """
        import org.unitedsoftware.alexandrite.sdk.channel.IncomingMessage
        import org.unitedsoftware.alexandrite.sdk.turn.AgentSource
        import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
        import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
        import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
        import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
        import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
        import org.unitedsoftware.alexandrite.sdk.i18n.Texts
        import org.unitedsoftware.alexandrite.sdk.model.TurnContextItem
        import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
        import org.unitedsoftware.alexandrite.sdk.turn.*

        class Commands(
            private val conversations: ConversationControl,
            private val settings: ChatSettings,
            private val catalog: AgentCatalog,
            private val links: ChatLinks,
            private val initiator: TurnInitiator,
            private val texts: Texts,
        ) : CommandHandler {
            override val commands = listOf(CommandSpec.builder("new", "Starts a new conversation.").build())

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
                if (!invocation.issuer.isOperator) {
                    context.reply(texts.text("new.operators_only", context.turn.language, "command" to invocation.name))
                    return
                }
                val conversation = conversations.newConversation(invocation.chat, invocation.issuer)
                context.reply("Started ${'$'}conversation.")
                val greeting = InitiatedTurn.builder(invocation.chat, TurnKind.HEARTBEAT, "Greet the chat.")
                    .route(ReplyRoute.ToChat(invocation.chat))
                    .build()
                when (val admission = initiator.initiate(greeting)) {
                    is Admission.Accepted -> admission.ticket.outcome().replayable
                    is Admission.Refused -> admission.reason == RefusalReason.QUEUE_FULL
                }
                val home = ChatSettingsUpdate.builder().home().build()
                val homed = try {
                    settings.update(invocation.chat, home, invocation.issuer).home
                } catch (e: ControlRefusedException) {
                    context.reply(e.message ?: "Refused.")
                    false
                }
                if (!homed) return
                val snapshot = settings.snapshot(invocation.chat)
                val agent = snapshot.agent
                val described: AgentSummary? = catalog.agents(invocation.chat.instance).firstOrNull { it.id == agent }
                val model = snapshot.model ?: snapshot.defaultModel
                val models = catalog.endpoints().flatMap { catalog.models(it) }.map { it.id }
                if (snapshot.effectiveLanguage == null && models.isEmpty()) return
                val chosenDefault = snapshot.agentSource == AgentSource.SELECTED && described?.default == true
                if (chosenDefault) context.reply("${'$'}model")
                if (snapshot.effectiveMode == org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.ASK) {
                    val bypass = org.unitedsoftware.alexandrite.sdk.permission.PermissionMode.BYPASS
                    val until = java.time.Instant.now().plusSeconds(600)
                    settings.update(invocation.chat, ChatSettingsUpdate.builder().mode(bypass, until).build(), null)
                }
                val group: ChatLinkGroup = links.link(agent, invocation.chat, invocation.chat, invocation.issuer)
                if (group in links.groups(agent)) links.unlink(agent, group.members.first(), null)
            }
        }

        class ReadOnly : InterceptorHook<TurnStart> {
            override val point = TurnPoints.TURN_START

            override suspend fun intercept(payload: TurnStart): HookDecision<TurnStart> =
                HookDecision.Replace(payload.withTools(payload.tools.filter { it.risk == ToolRisk.READ_ONLY }))
        }

        class Careful : InterceptorHook<ToolCallCheck> {
            override val point = TurnPoints.TOOL_BEFORE

            override suspend fun intercept(payload: ToolCallCheck): HookDecision<ToolCallCheck> =
                if (payload.risk.severity < ToolRisk.PERSISTENT_STATE.severity) {
                    HookDecision.Continue
                } else {
                    HookDecision.Replace(payload.withRequireApproval("Lasting changes need a person."))
                }
        }

        class Recall : InterceptorHook<TurnContext> {
            override val point = TurnPoints.CONTEXT_INJECT

            override suspend fun intercept(payload: TurnContext): HookDecision<TurnContext> =
                HookDecision.Replace(payload + TurnContextItem("recall", "Ada likes tea."))
        }

        fun accepted(submitter: TurnSubmitter, message: IncomingMessage): Boolean =
            submitter.submit(Submission.Message(message)) is Admission.Accepted
    """.trimIndent()

    /** A file tool as a third-party plugin writes it. */
    private val fileTool = """
        import kotlinx.serialization.json.JsonObject
        import kotlinx.serialization.json.jsonPrimitive
        import org.unitedsoftware.alexandrite.sdk.chat.AgentId
        import org.unitedsoftware.alexandrite.sdk.tool.*
        import java.nio.file.Files
        import java.nio.file.Path

        class Read(private val floor: HardFloor) : Tool {
            override val definition =
                ToolDefinition("files.read", "Reads a file.", JsonObject(emptyMap()), ToolRisk.READ_ONLY)

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
                val given = Path.of(arguments.getValue("path").jsonPrimitive.content)
                val path = if (given.isAbsolute) given else context.workspace?.resolve(given) ?: given
                return when (val check = floor.checkRead(path, context.turn.agent)) {
                    is FloorCheck.Allowed -> {
                        context.touched(check.canonical)
                        ToolResult(Files.readString(check.canonical))
                    }
                    is FloorCheck.Ask -> ToolResult("Ask a person: ${'$'}{check.reason}.", isError = true)
                    is FloorCheck.Denied -> ToolResult("Refused: ${'$'}{check.reason}.", isError = true)
                    else -> ToolResult("Refused.", isError = true)
                }
            }
        }

        fun allowedToAll(floor: HardFloor, path: Path): Boolean = floor.checkRead(path) is FloorCheck.Allowed

        class Write(private val floor: HardFloor) : ReviewedTool {
            override val definition =
                ToolDefinition("files.write", "Writes a file.", JsonObject(emptyMap()), ToolRisk.WORKSPACE_WRITE)

            override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
                val path = Path.of(arguments.getValue("path").jsonPrimitive.content)
                val check = floor.checkWrite(path, context.turn.agent)
                if (check is FloorCheck.Denied) return CallReview.builder()
                    .refusal("Refused: ${'$'}{check.reason}.")
                    .build()
                val person = (check as? FloorCheck.Ask)?.let { "${'$'}path needs a person (${'$'}{it.reason})" }
                val risk = if (Files.exists(path)) ToolRisk.WORKSPACE_WRITE else ToolRisk.EXEC
                val rule = RuleSubject("files.write", listOf("${'$'}path"), "writes to ${'$'}path")
                return CallReview.builder()
                    .risk(risk)
                    .preview(ReviewPreview.Diff(path, "+text"))
                    .snapshot("absent")
                    .rule(rule)
                    .person(person)
                    .build()
            }

            override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
                val clearance = context.clearance ?: return ToolResult("Not cleared.", isError = true)
                if (clearance.kind == org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind.NONE) {
                    context.untrusted("wrote without a clearance")
                }
                return ToolResult("Written after ${'$'}{clearance.snapshot}.")
            }
        }

        val previews = listOf(
            ReviewPreview.Command(listOf("ls"), Path.of("/srv"), Path.of("/bin/ls"), opaque = false),
            ReviewPreview.Text("Sends a message."),
        )

        fun writable(floor: HardFloor, path: Path, agent: AgentId?): Boolean =
            floor.checkWrite(path, agent) is FloorCheck.Allowed && floor.checkTree(path) is FloorCheck.Allowed
    """.trimIndent()

    /** A host component that changes the config file as an operator would. */
    private val configEdits = """
        import kotlinx.serialization.json.JsonPrimitive
        import org.unitedsoftware.alexandrite.sdk.config.*

        class Operator(private val config: HostConfig) {
            suspend fun model(agent: String, model: String): List<String>? = try {
                val changes = config.edit { set("agent.agents.${'$'}agent.model", JsonPrimitive(model)) }
                config.get("agent.agents.${'$'}agent")
                config.check { remove("agent.floor") }?.restart
                changes.changed + changes.restart
            } catch (e: ConfigEditException) {
                listOfNotNull(e.message, e.refusal.id.takeIf { e.refusal !in ConfigEditRefusal.entries })
            }
        }
    """.trimIndent()

    /** The errors of compiling [source]. */
    private fun errors(source: String): List<String> = KotlinCompilation().apply {
        workingDir = this@SubclassOptInTest.workingDir
        sources = listOf(SourceFile.kotlin("Sample.kt", source))
        inheritClassPath = true
        jvmTarget = "21"
        messageOutputStream = OutputStream.nullOutputStream()
    }.compile().messages.lines().filter { it.startsWith("e: ") }

    @Test
    fun `only code that opts in to the internal API implements the types Alexandrite implements`() {
        val classes = implementations.lines().filter { it.startsWith("class ") || it.startsWith("abstract class ") }
        val errors = errors(implementations)

        assertEquals(classes.size, errors.size, errors.joinToString("\n"))
        assertTrue(errors.all { "Internal Alexandrite API" in it }, errors.joinToString("\n"))
        val optIn = "@file:OptIn(org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi::class)"
        assertEquals(emptyList(), errors("$optIn\n\n$implementations"))
    }

    @Test
    fun `a when over an open hierarchy or enumeration compiles only with an else branch`() {
        val whens = consumers.lines().count { it.trim() == "ELSE" }
        val errors = errors(consumers.replace("ELSE", ""))

        assertEquals(whens, errors.size, errors.joinToString("\n"))
        assertTrue(errors.all { "exhaustive" in it }, errors.joinToString("\n"))
        assertEquals(emptyList(), errors(consumers.replace("ELSE", "else -> -1")))
    }

    @Test
    fun `a plugin implements a model provider without the internal API`() {
        assertEquals(emptyList(), errors(provider))
    }

    @Test
    fun `a plugin implements a channel and its reply sink without the internal API`() {
        assertEquals(emptyList(), errors(channel))
    }

    @Test
    fun `a plugin implements a store backend without the internal API`() {
        assertEquals(emptyList(), errors(store))
    }

    @Test
    fun `a plugin handles commands, starts turns and hooks into them without the internal API`() {
        assertEquals(emptyList(), errors(turns))
    }

    @Test
    fun `a plugin's tool checks its paths against the hard floor without the internal API`() {
        assertEquals(emptyList(), errors(fileTool))
    }

    @Test
    fun `a plugin implements web search capabilities without the internal API`() {
        val source = """
            class Search : org.unitedsoftware.alexandrite.sdk.web.WebSearchBackend {
                override val id = "search"
                override suspend fun search(query: org.unitedsoftware.alexandrite.sdk.web.WebQuery):
                    List<org.unitedsoftware.alexandrite.sdk.web.WebSearchResult> = listOf(org.unitedsoftware.alexandrite.sdk.web.WebSearchResult("title", "url", query.text))
            }
        """.trimIndent()

        assertEquals(emptyList(), errors(source))
    }

    @Test
    fun `a host component edits the config without the internal API`() {
        assertEquals(emptyList(), errors(configEdits))
    }

    @Test
    fun `only Alexandrite's own plugins take changes of their config while the runtime runs`() {
        val live = """
            class Limits : org.unitedsoftware.alexandrite.sdk.config.LiveConfig {
                override val paths = listOf("limit")

                override fun prepare(config: org.unitedsoftware.alexandrite.sdk.config.ConfigSnapshot): () -> Unit = {}
            }
        """.trimIndent()

        val errors = errors(live)

        assertTrue(errors.isNotEmpty() && errors.all { "Internal Alexandrite API" in it }, errors.joinToString("\n"))
    }

    @Test
    fun `only Alexandrite refuses config edits`() {
        val forged = """
            fun forged() = org.unitedsoftware.alexandrite.sdk.config.ConfigEditException(
                org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal.SECRET,
                "No.",
            )
        """.trimIndent()

        val errors = errors(forged)

        assertEquals(1, errors.size, errors.joinToString("\n"))
        assertTrue("Internal Alexandrite API" in errors.single(), errors.single())
    }

    @Test
    fun `only the agent builds hook payloads`() {
        val forged = """
            fun forged(turn: org.unitedsoftware.alexandrite.sdk.chat.TurnInfo) =
                org.unitedsoftware.alexandrite.sdk.turn.TurnStart(turn, null, emptyList())
        """.trimIndent()

        val errors = errors(forged)

        assertEquals(1, errors.size, errors.joinToString("\n"))
        assertTrue("Internal Alexandrite API" in errors.single(), errors.single())
    }
}
