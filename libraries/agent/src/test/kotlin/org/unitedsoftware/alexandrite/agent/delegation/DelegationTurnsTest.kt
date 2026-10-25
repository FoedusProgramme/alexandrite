package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.directory.CONVERSATION_DIRECTORY_SECTION
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.job.JobRecord
import org.unitedsoftware.alexandrite.agent.job.JobRecords
import org.unitedsoftware.alexandrite.agent.media.MEDIA_SECTION
import org.unitedsoftware.alexandrite.agent.permission.Refusals
import org.unitedsoftware.alexandrite.agent.project.PROJECT_SECTION
import org.unitedsoftware.alexandrite.agent.prompt.BASE_SECTION
import org.unitedsoftware.alexandrite.agent.prompt.DELEGATION_SECTION
import org.unitedsoftware.alexandrite.agent.startingInBackground
import org.unitedsoftware.alexandrite.agent.tool.CANCELLED_RESULT
import org.unitedsoftware.alexandrite.agent.tool.TOOLS_SECTION
import org.unitedsoftware.alexandrite.agent.workspace.WORKSPACE_SECTION
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.DelegatedRun
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.channel.ToolCallStatus
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.permission.Clearance
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.store.ConversationKind
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ForkPoint
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AgentRunLifecycle
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.turn.JobLifecycle
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.RunWay
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DelegationTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val lifecycleEvents = CopyOnWriteArrayList<String>()
    private val jobsEnded = ConcurrentHashMap<RunId, CompletableDeferred<Unit>>()
    private var readGate: CompletableDeferred<Unit>? = null
    private val read = recordingTool(
        ToolDefinition(
            "test.read",
            "Reads a file.",
            JsonObject(mapOf("type" to JsonPrimitive("object"))),
            ToolRisk.READ_ONLY,
            activity = ToolActivity.READ,
            titleArguments = listOf("path"),
        ),
    ) { _, _ ->
        readGate?.await()
        ToolResult("The notes.")
    }
    private val cleared = CopyOnWriteArrayList<Clearance?>()
    private val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE) { _, context ->
        cleared += context.clearance
        ToolResult("Written.")
    }
    private val exec = recordingTool("command.exec", ToolRisk.EXEC)
    private val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ) { _, context ->
        context.untrusted("a web page")
        ToolResult("Page.")
    }

    private fun harness(coder: String = "", maxBackgroundRuns: Int = 4, runsPerChat: Int = 4): PluginHarness =
        agentHarness(
            """
        {
          "previewIntervalMillis": 0,
          "maxJobsPerChat": 8,
          "maxBackgroundRuns": $maxBackgroundRuns,
          "maxBackgroundRunsPerChat": $runsPerChat,
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {
            "coder": {
              "model": "scripted/test-model",
              "channels": {"test:main": {}},
              "tools": {"forGuests": ["agent.delegate", "test.read", "fs.write", "command.exec"]}$coder
            },
            "reviewer": {"description": "Reviews.", "instructions": ["$REVIEWER"], "tools": {"allow": ["test.read"]}},
            "writer": {
              "description": "Writes.",
              "instructions": ["$WRITER"],
              "tools": {"allow": ["test.read", "fs.write"]}
            },
            "planner": {
              "description": "Plans.",
              "instructions": ["$PLANNER"],
              "tools": {"allow": ["fs.write"]},
              "permission": {"mode": "plan"}
            },
            "doer": {
              "description": "Does.",
              "instructions": ["$DOER"],
              "tools": {"allow": ["fs.write"]},
              "permission": {"mode": "auto"}
            },
            "runner": {"description": "Runs.", "instructions": ["$RUNNER"], "tools": {"allow": ["command.exec"]}},
            "reader": {"description": "Reads the web.", "instructions": ["$READER"], "tools": {"allow": ["web.fetch"]}},
            "hurried": {
              "description": "Hurries.",
              "instructions": ["$HURRIED"],
              "tools": {"allow": ["agent.delegate", "test.read"]},
              "maxRounds": 1
            },
            "nester": {
              "description": "Delegates.",
              "instructions": ["$NESTER"],
              "tools": {"allow": ["agent.delegate", "test.read"]}
            }
          }
        }
            """.trimIndent(),
            store,
        ) {
            channel(
                operators = setOf("operator"),
            ).model(model).tool(read).tool(write).tool(fetch).tool(startingInBackground(exec))
            for (point in listOf(TurnPoints.AGENT_SPAWN, TurnPoints.AGENT_RUN_ENDED)) {
                hook(object : ObserverHook<AgentRunLifecycle> {
                    override val point: ObserverPoint<AgentRunLifecycle> = point
                    override suspend fun observe(payload: AgentRunLifecycle) {
                        lifecycleEvents += "${point.id}:${payload.run}"
                    }
                })
            }
            for (point in listOf(TurnPoints.JOB_STARTED, TurnPoints.JOB_ENDED, TurnPoints.TOOL_BACKGROUNDED)) {
                hook(object : ObserverHook<JobLifecycle> {
                    override val point: ObserverPoint<JobLifecycle> = point
                    override suspend fun observe(payload: JobLifecycle) {
                        lifecycleEvents += "${point.id}:${payload.run}"
                        if (point == TurnPoints.JOB_ENDED) jobEnded(payload.run).complete(Unit)
                    }
                })
            }
        }

    private fun jobEnded(run: RunId): CompletableDeferred<Unit> =
        jobsEnded.computeIfAbsent(run) { CompletableDeferred() }

    private fun PluginHarness.Running.ask(text: String = "Go.", user: String = "operator", chat: String = "chat") =
        channel().receive(text, channel().chat(chat), channel().user(user)) as Admission.Accepted

    private suspend fun PluginHarness.Running.mode(mode: PermissionMode?) {
        val update = ChatSettingsUpdate.builder().apply { if (mode == null) resetMode() else mode(mode) }.build()
        get<ChatSettings>().update(channel().chat(), update, channel().user("operator"))
    }

    /** The tool results of the current conversation of [key]. */
    private suspend fun rootResults(key: AgentChatKey = this.key): List<ToolResultEntry> =
        store.transcripts.entries(store.conversations.current(key).id).filterIsInstance<ToolResultEntry>()

    private suspend fun results(conversation: ConversationId): List<ToolResultEntry> =
        store.transcripts.entries(conversation).filterIsInstance<ToolResultEntry>()

    /** The requests of the runs whose profile has [instructions]. */
    private fun runs(instructions: String): List<ModelRequest> = model.requests.filter { it.ofProfile(instructions) }

    /** The lineage of the run that made [request]. */
    private suspend fun lineage(request: ModelRequest): TurnLineage =
        checkNotNull(store.conversations.conversation(request.ids.conversation)?.lineage)

    /** The text of the result, without the line that ends a run's result with its id. */
    private fun ToolResultEntry.text(): String =
        content.map { (it as TextPart).text }.filterNot { it.startsWith("\n[alexandrite:run ") }.joinToString("")

    /** The run whose id ends the result. */
    private fun ToolResultEntry.run(): RunId =
        RunId(content.map { (it as TextPart).text }.last().removePrefix("\n[alexandrite:run ").removeSuffix("]"))

    private fun ToolResultEntry.shown(): Pair<ToolOutcome, String> = outcome to text()

    // What runs and what the turn above gets.

    @Test
    fun `a review delegated to a read-only profile returns its reply, and the profile's write tool is refused`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "notes.md"}""") }
            .reply(profile(REVIEWER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(profile(REVIEWER)) { text("Looks fine.") }
            .reply(ROOT) { text("The reviewer says it looks fine.") }

        harness().execute {
            val turn = ask("Have notes.md reviewed.")
            assertIs<TurnOutcome.Completed>(turn.ticket.outcome())

            val runs = runs(REVIEWER)
            assertEquals(List(3) { listOf("test.read") }, runs.map { request -> request.tools.map { it.name } })
            assertEquals(
                listOf(
                    BASE_SECTION,
                    DELEGATION_SECTION,
                    "agent.instructions.1",
                    TOOLS_SECTION,
                    WORKSPACE_SECTION,
                    CONVERSATION_DIRECTORY_SECTION,
                    PROJECT_SECTION,
                    MEDIA_SECTION,
                ),
                runs[0].instructions.map { it.id },
            )
            val opening = runs[0].history.single() as UserEntry
            assertEquals(UserOrigin.SystemWritten(DELEGATION_SOURCE), opening.origin)
            assertEquals("Review notes.md.", (opening.parts.last() as TextPart).text)
            val child = runs[0].ids.conversation
            assertEquals(
                listOf(
                    ToolOutcome.Succeeded to "The notes.",
                    ToolOutcome.NotRun(NotRunReason.NOT_PERMITTED) to "This turn may not call 'fs.write'.",
                ),
                results(child).map { it.shown() },
            )
            assertEquals(emptyList(), write.calls)
            assertEquals(listOf(ToolOutcome.Succeeded to "Looks fine."), rootResults().map { it.shown() })
            assertEquals("The reviewer says it looks fine.", channel().reply(turn.ticket.turn).completed?.text)

            val root = store.conversations.current(key).id
            val call = store.transcripts.entries(root).filterIsInstance<AssistantEntry>().first()
                .parts.filterIsInstance<ToolCallPart>().single().id
            val conversation = checkNotNull(store.conversations.conversation(child))
            assertEquals(
                listOf(ConversationKind.DELEGATED, ConversationState.SEALED, key, null),
                listOf(conversation.kind, conversation.state, conversation.key, conversation.fork),
            )
            val lineage = TurnLineage(lineage(runs[0]).run, turn.ticket.turn, root, call, turn.ticket.turn, root, 1)
            assertEquals(lineage, conversation.lineage)
            val record = checkNotNull(store.conversations.turn(runs[0].ids.turn))
            assertEquals(
                listOf(TurnKind.DELEGATED, TurnEndKind.COMPLETED, channel().chat(), channel().user("operator").address),
                listOf(record.kind, record.end, record.chat, record.actor),
            )
            assertTrue(lineage.run.value.startsWith("r-"), "${lineage.run}")
        }
    }

    @Test
    fun `an implementation delegated to a write-capable profile asks the root chat, naming the chain`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(profile(WRITER)) { text("Notes written.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator))
            ask("Write the notes.").ticket.outcome()

            val request = channel().approvals.single()
            assertEquals(listOf(AgentId("coder"), AgentId("writer")), request.chain)
            assertEquals(
                listOf(channel().chat(), TurnKind.DELEGATED, ReplyTarget.CALLER, operator, "the chat is in ask mode"),
                listOf(
                    request.turn.chat,
                    request.turn.kind,
                    request.turn.replyTarget,
                    request.turn.actor,
                    request.reason,
                ),
            )
            assertEquals(listOf(Clearance(ClearanceKind.HUMAN, operator)), cleared.toList())
            assertEquals(listOf(ToolOutcome.Succeeded to "Notes written."), rootResults().map { it.shown() })
        }
    }

    @Test
    fun `a run that fails gives the turn above a failed result that names its notice`() {
        val broken = ModelError.builder(ModelErrorKind.INVALID_REQUEST, "Broken.").retryable(false).build()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review it.")) }
            .fail(broken, profile(REVIEWER))
            .reply(ROOT) { text("The review failed.") }

        harness().execute {
            ask().ticket.outcome()

            val result = rootResults().single()
            assertEquals(ToolOutcome.Failed, result.outcome)
            val child = checkNotNull(model.requests.firstOrNull { it.ofProfile(REVIEWER) }).ids.conversation
            val notice = store.transcripts.entries(child).filterIsInstance<NoticeEntry>().single()
            assertEquals(NoticeKind.FAILED, notice.kind)
            val run = lineage(model.requests.first { it.ofProfile(REVIEWER) }).run
            assertEquals(
                "Sub-agent run $run of reviewer failed: ${notice.text}. Its conversation $child keeps what it did.",
                result.text(),
            )
        }
    }

    @Test
    fun `an unknown profile, a malformed background flag and a bad context are refused as invalid arguments`() {
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, delegate("nobody", "Do it."))
            toolCall(DELEGATE_TOOL, """{"profile": "reviewer", "task": "Do it.", "background": "yes"}""")
            toolCall(DELEGATE_TOOL, """{"profile": "reviewer", "task": "Do it.", "context": "all"}""")
        }.reply(ROOT) { text("Nothing ran.") }

        harness().execute {
            ask().ticket.outcome()

            val invalid = ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS)
            assertEquals(
                setOf(
                    invalid to
                        "No profile 'nobody'. Choose one of reviewer, writer, planner, doer, runner, reader, " +
                        "hurried, nester, general.",
                    invalid to "'background' must be true or false.",
                    invalid to "'context' is \"fresh\" or \"fork\".",
                ),
                rootResults().map { it.shown() }.toSet(),
            )
        }
    }

    @Test
    fun `the tool's description lists the profiles that a turn may run`() {
        model.reply(ROOT) { text("Hello.") }

        harness().execute {
            ask().ticket.outcome()

            val description = model.requests.single().tools.single { it.name == DELEGATE_TOOL }.description
            assertContains(description, "\n\nProfiles:\n- reviewer (reviewer): Reviews.\n- writer (writer): Writes.\n")
            assertContains(description, "- general (General): A general-purpose sub-agent")
        }
    }

    @Test
    fun `a delegation to the built-in general profile runs with the delegating agent's model and tools`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("general", "Summarize notes.md.")) }
            .reply(profile(GENERAL_INSTRUCTIONS)) { toolCall("test.read", """{"path": "notes.md"}""") }
            .reply(profile(GENERAL_INSTRUCTIONS)) { text("It says hi.") }
            .reply(ROOT) { text("It says hi.") }

        harness().execute {
            ask().ticket.outcome()

            assertEquals(listOf(ToolOutcome.Succeeded to "It says hi."), rootResults().map { it.shown() })
        }
    }

    // Inheritance.

    @Test
    fun `a profile only ever tightens the mode, so plan under a bypass plans and auto under ask asks`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("planner", "Write a.")) }
            .reply(profile(PLANNER)) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(profile(PLANNER)) { text("Planned.") }
            .reply(ROOT) { text("Planned.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("doer", "Write b.")) }
            .reply(profile(DOER)) { toolCall("fs.write", """{"path": "b"}""") }
            .reply(profile(DOER)) { text("Asked.") }
            .reply(ROOT) { text("Asked.") }

        harness().execute {
            mode(PermissionMode.BYPASS)
            ask("Plan it.").ticket.outcome()
            mode(null)
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("operator")))
            ask("Ask for it.").ticket.outcome()

            val planned = results(runs(PLANNER)[0].ids.conversation).single()
            assertEquals(ToolOutcome.NotRun(NotRunReason.PLAN_MODE), planned.outcome)
            val request = channel().approvals.single()
            assertEquals("the chat is in ask mode", request.reason)
            assertEquals(
                ToolOutcome.NotRun(NotRunReason.DENIED, request.id),
                results(runs(DOER)[0].ids.conversation).single().outcome,
            )
            assertEquals(emptyList(), write.calls)
            assertTrue(model.requests.none { it.isJudge() })
        }
    }

    @Test
    fun `a run that a guest's turn delegates stays the guest's, so its write asks an operator`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(profile(WRITER)) { text("Not written.") }
            .reply(ROOT) { text("Not written.") }

        harness().execute {
            mode(PermissionMode.BYPASS)
            ask(user = "guest").ticket.outcome()

            val request = channel().approvals.single()
            assertEquals(channel().user("guest") to "a guest's call", request.turn.actor to request.reason)
            assertEquals(listOf(AgentId("coder"), AgentId("writer")), request.chain)
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `in auto mode the judge sees what the operator typed and the delegated task, labelled`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write the plan into notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
            .reply(JUDGE) { text("""{"verdict": "allow", "reason": "the operator asked for it"}""") }
            .reply(profile(WRITER)) { text("Written.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            mode(PermissionMode.AUTO)
            ask("Please put the plan in notes.md.").ticket.outcome()

            val judged = model.requests.single { it.isJudge() }
            val prompt = (judged.history.single() as UserEntry).parts.joinToString("") { (it as TextPart).text }
            assertContains(prompt, "one block per message:\n<<<\nPlease put the plan in notes.md.\n>>>\n")
            assertContains(prompt, "not the operator:\ncoder → writer:\n<<<\nWrite the plan into notes.md.\n>>>\n")
            assertEquals(listOf(Clearance(ClearanceKind.JUDGE)), cleared.toList())
        }
    }

    @Test
    fun `a run of a tainted turn starts tainted, and a run that takes in untrusted content taints the turn above`() {
        model.reply(ROOT) { toolCall("web.fetch") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review it.")) }
            .reply(profile(REVIEWER)) { text("Fine.") }
            .reply(ROOT) { text("Fine.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reader", "Read the page.")) }
            .reply(profile(READER)) { toolCall("web.fetch") }
            .reply(profile(READER)) { text("It says hello.") }
            .reply(ROOT) { text("It says hello.") }

        harness().execute {
            ask().ticket.outcome()
            ask(chat = "other").ticket.outcome()

            val tainted = store.conversations.current(key).id
            val down = runs(REVIEWER).single()
            assertEquals(
                "run ${lineage(down).run} was delegated by a turn of tainted conversation $tainted (a call of " +
                    "web.fetch, which reads the network)",
                store.conversations.taint(down.ids.conversation)?.reason,
            )
            val other = AgentChatKey.parse("coder@test:main:other")
            val up = runs(READER).first()
            assertEquals(
                "sub-agent run ${lineage(up).run} (reader) took in untrusted content: a call of web.fetch, which " +
                    "reads the network",
                store.conversations.taint(store.conversations.current(other).id)?.reason,
            )
        }
    }

    @Test
    fun `a background run that takes in untrusted content taints its parent's conversation before its report`() {
        readGate = CompletableDeferred()
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, """{"profile":"reader","task":"Read the page.","background":true}""")
        }.reply(ROOT) { text("Started.") }
            .reply(profile(READER)) { toolCall("web.fetch") }
            .reply(profile(READER)) {
                pause { readGate?.await() }
                text("It says hello.")
            }
            .reply(ROOT) { text("It says hello.") }

        harness().execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            val conversation = store.conversations.current(key).id
            withTimeout(5_000) { while (runs(READER).size < 2) delay(5) }
            withTimeout(5_000) { while (store.conversations.taint(conversation) == null) delay(5) }
            val reader = runs(READER).first()
            val reason = "sub-agent run ${lineage(reader).run} (reader) took in untrusted content: a call of " +
                "web.fetch, which reads the network"
            assertEquals(reason, store.conversations.taint(conversation)?.reason)
            readGate?.complete(Unit)
            withTimeout(5_000) { while (jobResultCount() < 1) delay(5) }
            assertEquals(reason, store.conversations.taint(conversation)?.reason)
        }
    }

    @Test
    fun `a chat runs at most maxBackgroundRunsPerChat background runs, and the next is refused saying why`() {
        readGate = CompletableDeferred()
        model.reply(ROOT) {
            repeat(2) { toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Review $it","background":true}""") }
        }.reply(ROOT) { text("Started.") }
            .reply(profile(REVIEWER)) { toolCall("test.read", "{}") }
            .reply(profile(REVIEWER)) { text("Review done.") }
            .reply(ROOT) { text("Report received.") }

        harness(runsPerChat = 1).execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            val results = rootResults()
            assertEquals(1, results.count { it.outcome is ToolOutcome.Detached })
            assertEquals(
                ToolOutcome.NotRun(NotRunReason.BACKGROUND_FULL) to
                    "No sub-agent was started: the chat already runs 1 background sub-agent runs.",
                results.single { it.outcome is ToolOutcome.NotRun }.shown(),
            )
            readGate?.complete(Unit)
            withTimeout(5_000) { while (jobResultCount() < 1) delay(5) }
        }
    }

    @Test
    fun `a resumed run goes on from the earlier run's history with the new task, under a new run id`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md.")) }
            .reply(profile(REVIEWER)) { text("Notes look fine.") }
            .reply(ROOT) { text("Reviewed.") }

        harness().execute {
            ask().ticket.outcome()
            val first = rootResults().single { it.toolName == DELEGATE_TOOL }
            val earlier = first.run()
            assertEquals("Notes look fine.", first.text())

            model.reply(ROOT) {
                toolCall(DELEGATE_TOOL, """{"resume":"$earlier","task":"Now check the tests too."}""")
            }.reply(profile(REVIEWER)) { text("Tests look fine as well.") }
                .reply(ROOT) { text("Both reviewed.") }
            ask().ticket.outcome()

            val resumed = rootResults().filter { it.toolName == DELEGATE_TOOL }.last()
            assertEquals(ToolOutcome.Succeeded to "Tests look fine as well.", resumed.shown())
            assertTrue(resumed.run() != earlier)
            val request = runs(REVIEWER).last()
            val said = request.history.flatMap { entry ->
                when (entry) {
                    is UserEntry -> entry.parts.filterIsInstance<TextPart>().map { it.text }
                    is AssistantEntry -> entry.parts.filterIsInstance<TextPart>().map { it.text }
                    else -> emptyList()
                }
            }
            assertEquals(listOf("Review notes.md.", "Notes look fine.", "Now check the tests too."), said)
            val sealed = store.conversations.conversation(runs(REVIEWER).first().ids.conversation)
            assertEquals(ConversationState.SEALED, sealed?.state)
        }
    }

    @Test
    fun `a run resumed in the background without its profile named names the profile and is titled by it`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md.")) }
            .reply(profile(REVIEWER)) { text("Notes look fine.") }
            .reply(ROOT) { text("Reviewed.") }

        harness().execute {
            ask().ticket.outcome()
            val earlier = rootResults().single { it.toolName == DELEGATE_TOOL }.run()
            readGate = CompletableDeferred()
            model.reply(ROOT) {
                toolCall(DELEGATE_TOOL, """{"resume":"$earlier","task":"Check the tests.","background":true}""")
            }.reply(ROOT) { text("Started.") }
                .reply(profile(REVIEWER)) { toolCall("test.read", "{}") }
                .reply(profile(REVIEWER)) { text("Tests look fine.") }
                .reply(ROOT) { text("Report received.") }
            ask().ticket.outcome()

            val started = rootResults().filter { it.toolName == DELEGATE_TOOL }.last()
            val run = (started.outcome as ToolOutcome.Detached).job
            assertEquals(
                "[alexandrite:background] Run $run of profile reviewer started; its report will arrive later.",
                started.text(),
            )
            assertEquals(listOf("reviewer"), get<TurnControl>().jobs(channel().chat()).map { it.title })
            readGate?.complete(Unit)
            withTimeout(5_000) { while (jobResultCount() < 1) delay(5) }
        }
    }

    @Test
    fun `a resume names a run of this conversation, by its id and with its own profile and history`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review.")) }
            .reply(profile(REVIEWER)) { text("Fine.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            ask().ticket.outcome()
            val earlier = rootResults().single { it.toolName == DELEGATE_TOOL }.run()
            model.reply(ROOT) {
                toolCall(DELEGATE_TOOL, """{"resume":"r-nothere","task":"Go on."}""")
                toolCall(DELEGATE_TOOL, """{"resume":"$earlier","profile":"writer","task":"Go on."}""")
                toolCall(DELEGATE_TOOL, """{"resume":"$earlier","context":"fork","task":"Go on."}""")
                toolCall(DELEGATE_TOOL, """{"resume":"not a run","task":"Go on."}""")
            }.reply(ROOT) { text("Refused.") }
                .reply(ROOT) { toolCall(DELEGATE_TOOL, """{"resume":"$earlier","task":"Go on."}""") }
                .reply(ROOT) { text("Refused.") }
            ask().ticket.outcome()
            ask(chat = "other").ticket.outcome()

            val invalid = ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS)
            assertEquals(
                setOf(
                    invalid to "No run r-nothere of this conversation to resume.",
                    invalid to "Run $earlier ran profile reviewer.",
                    invalid to "A resumed run goes on from its own history.",
                    invalid to "'resume' must be the id of an earlier run, such as r-abc123.",
                ),
                rootResults().drop(1).map { it.shown() }.toSet(),
            )
            assertEquals(
                invalid to "No run $earlier of this conversation to resume.",
                rootResults(AgentChatKey.parse("coder@test:main:other")).single().shown(),
            )
            assertEquals(1, runs(REVIEWER).size)
        }
    }

    @Test
    fun `a forked run starts from the turn's history up to its call, a fresh one from its task alone`() {
        model.reply(ROOT) { text("Hello.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review what we said.", "fork")) }
            .reply(profile(REVIEWER)) { text("Forked.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review it fresh.")) }
            .reply(profile(REVIEWER)) { text("Fresh.") }
            .reply(ROOT) { text("Both done.") }

        harness().execute {
            ask("Hi.").ticket.outcome()
            ask("Review us.").ticket.outcome()

            val (forked, fresh) = runs(REVIEWER)
            val root = store.conversations.current(key).id
            val before = store.transcripts.entries(root).takeWhile { entry ->
                entry !is AssistantEntry || entry.parts.none { it is ToolCallPart }
            }
            assertEquals(3, before.size)
            assertEquals(before, forked.history.dropLast(1))
            assertEquals(listOf(UserEntry::class), fresh.history.map { it::class })
            assertEquals(
                listOf(ForkPoint(root, checkNotNull(before.last().record).id), null),
                listOf(forked, fresh).map { store.conversations.conversation(it.ids.conversation)?.fork },
            )
            assertEquals(
                listOf(ToolOutcome.Succeeded to "Forked.", ToolOutcome.Succeeded to "Fresh."),
                rootResults().map { it.shown() },
            )
        }
    }

    // Limits.

    @Test
    fun `runs delegate three levels deep at most, the last level is offered no delegation and its call is refused`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 1.")) }
            .reply(task("Level 1.")) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 2.")) }
            .reply(task("Level 2.")) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 3.")) }
            .reply(task("Level 3.")) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 4.")) }
            .reply(task("Level 3.")) { text("Three.") }
            .reply(task("Level 2.")) { text("Two.") }
            .reply(task("Level 1.")) { text("One.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            val turn = ask()
            turn.ticket.outcome()

            val levels = listOf("Level 1.", "Level 2.", "Level 3.").map { level ->
                model.requests.first { it.task() == level }
            }
            assertEquals(
                listOf(listOf(DELEGATE_TOOL, "test.read"), listOf(DELEGATE_TOOL, "test.read"), listOf("test.read")),
                levels.map { request -> request.tools.map { it.name } },
            )
            val lineages = levels.map { lineage(it) }
            assertEquals(listOf(1, 2, 3), lineages.map { it.depth })
            assertEquals(List(3) { turn.ticket.turn }, lineages.map { it.rootTurn })
            assertEquals(
                ToolOutcome.NotRun(NotRunReason.DELEGATION_LIMIT) to DELEGATION_LIMIT_RESULT,
                results(levels[2].ids.conversation).single().shown(),
            )
            assertEquals(
                listOf("Two.", "Three."),
                levels.take(2).map { results(it.ids.conversation).single().text() },
            )
        }
    }

    @Test
    fun `the root turn's token budget covers its runs, which sum up once it is used up`() {
        val used = Usage.builder().inputTokens(1100).outputTokens(100).build()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review a and b.")) }
            .reply(profile(REVIEWER)) {
                toolCall("test.read", """{"path": "a"}""")
                usage(used)
            }
            .reply(profile(REVIEWER)) { text("Read a; b is left.") }
            .reply(ROOT) { text("Half reviewed.") }

        harness(""", "maxTurnTokens": 1000""").execute {
            ask().ticket.outcome()

            assertEquals(listOf(ToolChoice.Auto, ToolChoice.None), runs(REVIEWER).map { it.toolChoice })
            assertEquals(ToolChoice.None, model.requests.last().toolChoice)
            val child = runs(REVIEWER)[0].ids.conversation
            assertEquals(
                listOf(NoticeKind.BUDGET),
                store.transcripts.entries(child).filterIsInstance<NoticeEntry>().map { it.kind },
            )
            assertEquals(listOf(ToolOutcome.Succeeded to "Read a; b is left."), rootResults().map { it.shown() })
        }
    }

    @Test
    fun `a run's long call stays in the foreground, so that its result comes back within the run`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Read a.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "a"}""") }
            .reply(profile(REVIEWER)) { text("Read a.") }
            .reply(ROOT) { text("Read a.") }

        harness(""", "foregroundSeconds": 1""").execute {
            val turn = ask()
            read.awaitCalls(1)
            delay(1_500)
            assertEquals(emptyList(), get<TurnControl>().jobs(channel().chat()))
            read.release()
            turn.ticket.outcome()

            assertEquals(listOf(ToolOutcome.Succeeded), results(runs(REVIEWER)[0].ids.conversation).map { it.outcome })
            assertEquals(listOf(ToolOutcome.Succeeded to "Read a."), rootResults().map { it.shown() })
        }
    }

    // Messages to runs.

    @Test
    fun `an operator's message to a running run is taken in after its call, and the run answers with it in view`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Read a.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "a"}""") }
            .reply(profile(REVIEWER)) { text("Read a and b.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            val turn = ask()
            read.awaitCalls(1)
            val run = get<TurnControl>().turns().single { it.turn.kind == TurnKind.DELEGATED }.turn
            val message = channel().message("Also read b.", sender = channel().user("operator"))
            val submission = Submission.ToRun(checkNotNull(run.lineage).run, message)
            val sent = assertIs<Admission.Accepted>(channel().submit(submission))
            read.release()

            assertEquals(run.id, assertIs<TurnOutcome.Absorbed>(sent.ticket.outcome()).into)
            turn.ticket.outcome()
            val said = runs(REVIEWER).last().history.filterIsInstance<UserEntry>()
                .flatMap { entry -> entry.parts.filterIsInstance<TextPart>().map { it.text } }
            assertTrue(said.any { "Also read b." in it }, "The run was not shown the message: $said")
            assertEquals(listOf(ToolOutcome.Succeeded to "Read a and b."), rootResults().map { it.shown() })
        }
    }

    @Test
    fun `a message to a run is refused from a guest, to an unknown run, and once the run ended`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Read a.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "a"}""") }
            .reply(profile(REVIEWER)) { text("Read a.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            val turn = ask()
            read.awaitCalls(1)
            val run = get<TurnControl>().turns().single { it.turn.kind == TurnKind.DELEGATED }.turn
            val id = checkNotNull(run.lineage).run
            fun refusal(to: RunId, user: String = "operator"): RefusalReason? {
                val message = channel().message("Hello.", sender = channel().user(user))
                return (channel().submit(Submission.ToRun(to, message)) as? Admission.Refused)?.reason
            }

            assertEquals(RefusalReason.NOT_PERMITTED, refusal(id, user = "guest"))
            assertEquals(RefusalReason.UNKNOWN_RUN, refusal(RunId("r-unknown")))
            read.release()
            turn.ticket.outcome()
            assertEquals(RefusalReason.RUN_ENDED, refusal(id))
        }
    }

    @Test
    fun `a message that a run did not take in before it was stopped is taken back`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Read a.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "a"}""") }
            .reply(ROOT) { text("Stopped.") }

        harness().execute {
            val turn = ask()
            read.awaitCalls(1)
            val control = get<TurnControl>()
            val id = checkNotNull(control.turns().single { it.turn.kind == TurnKind.DELEGATED }.turn.lineage).run
            val message = channel().message("Also read b.", sender = channel().user("operator"))
            val sent = assertIs<Admission.Accepted>(channel().submit(Submission.ToRun(id, message)))

            assertTrue(control.cancelRun(id, tree = true, channel().user("operator")))

            assertEquals(TurnOutcome.TakenBack, sent.ticket.outcome())
            turn.ticket.outcome()
        }
    }

    @Test
    fun `an operator resumes an ended run, which goes on from its conversation, and the agent keeps its report`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md.")) }
            .reply(profile(REVIEWER)) { text("Notes look fine.") }
            .reply(ROOT) { text("Fine.") }
            .reply(profile(REVIEWER)) { text("Tests look fine as well.") }

        harness().execute {
            val capabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild {
                runReplies(true)
                events(TurnEventKind.entries.toSet())
            }
            channel().scriptCapabilities(capabilities)
            ask().ticket.outcome()
            val earlier = lineage(runs(REVIEWER)[0]).run
            val message = channel().message("Now check the tests too.", sender = channel().user("operator"))
            val sent = assertIs<Admission.Accepted>(
                channel().submit(Submission.ToRun(earlier, message, RunWay.RESUME)),
            )

            assertIs<TurnOutcome.Completed>(sent.ticket.outcome())
            val said = runs(REVIEWER).last().history.flatMap { entry ->
                when (entry) {
                    is UserEntry -> entry.parts.filterIsInstance<TextPart>().map { it.text }
                    is AssistantEntry -> entry.parts.filterIsInstance<TextPart>().map { it.text }
                    else -> emptyList()
                }
            }
            assertEquals(listOf("Review notes.md.", "Notes look fine.", "Now check the tests too."), said)
            val resumed = rootResults().last { it.toolName == DELEGATE_TOOL }
            assertEquals(ToolOutcome.Succeeded to "Tests look fine as well.", resumed.shown())
            assertTrue(resumed.run() != earlier)
            assertEquals(earlier, channel().replies.last { it.request.run != null }.request.run?.from)
            // The turn asks its model nothing: neither before the call nor about the report, which its next turn reads.
            assertEquals(ReplyEnd.COMPLETED, channel().reply(sent.ticket.turn).awaitEnd().abandoned)
            assertEquals(4, model.requests.size)
            val call = channel().reply(sent.ticket.turn).activity.filterIsInstance<TurnEvent.ToolCallStarted>().single()
            assertEquals("reviewer $earlier", call.title)
        }
    }

    @Test
    fun `a run is resumed only by an operator, and only once it ended`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Read a.")) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "a"}""") }
            .reply(profile(REVIEWER)) { text("Read a.") }
            .reply(ROOT) { text("Done.") }

        harness().execute {
            val turn = ask()
            read.awaitCalls(1)
            val id = checkNotNull(
                get<TurnControl>().turns().single {
                    it.turn.kind == TurnKind.DELEGATED
                }.turn.lineage,
            ).run
            fun refusal(user: String, way: RunWay): RefusalReason? {
                val message = channel().message("Again.", sender = channel().user(user))
                return (channel().submit(Submission.ToRun(id, message, way)) as? Admission.Refused)?.reason
            }

            assertEquals(RefusalReason.RUN_RUNNING, refusal("operator", RunWay.RESUME))
            assertEquals(RefusalReason.RUN_RUNNING, refusal("operator", RunWay.FORK))
            read.release()
            turn.ticket.outcome()
            assertEquals(RefusalReason.NOT_PERMITTED, refusal("guest", RunWay.RESUME))
        }
    }

    // Approvals, cancel and denials.

    @Test
    fun `a cancel of the root turn stops its run and the approval that the run waits for`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }

        harness().execute {
            channel().holdApprovals()
            val turn = ask()
            val request = channel().awaitApprovals(1).single()

            assertTrue(get<TurnControl>().cancel(channel().chat(), channel().user("operator")))

            assertEquals(TurnOutcome.Cancelled, turn.ticket.outcome())
            channel().awaitEvent { it == RecordingChannel.Event.ApprovalWithdrawn(request.id) }
            val child = request.turn
            assertEquals(TurnEndKind.CANCELLED, store.conversations.turn(child.id)?.end)
            assertEquals(
                listOf(ToolOutcome.NotRun(NotRunReason.CANCELLED, request.id) to Refusals.CANCELLED),
                results(child.conversation).map { it.shown() },
            )
            assertEquals(listOf(ToolOutcome.Cancelled to CANCELLED_RESULT), rootResults().map { it.shown() })
            assertEquals(ConversationState.SEALED, store.conversations.conversation(child.conversation)?.state)
            assertTrue(get<TurnControl>().turns().none { it.turn.kind == TurnKind.DELEGATED })
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `a shutdown cuts the run off, its waiting call answered as interrupted and the delegate call as cancelled`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write notes.md.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "notes.md"}""") }
        lateinit var turn: Admission.Accepted
        lateinit var request: ApprovalRequest

        harness().execute {
            channel().holdApprovals()
            turn = ask()
            request = channel().awaitApprovals(1).single()
            stop()
        }

        assertEquals(TurnOutcome.ShutDown(replayable = false), blocking { turn.ticket.outcome() })
        val run = request.turn
        assertEquals(
            listOf(ToolOutcome.NotRun(NotRunReason.INTERRUPTED, request.id) to Refusals.INTERRUPTED),
            blocking { results(run.conversation) }.map { it.shown() },
        )
        assertEquals(listOf(ToolOutcome.Cancelled to CANCELLED_RESULT), blocking { rootResults() }.map { it.shown() })
        assertEquals(TurnEndKind.SHUT_DOWN, blocking { store.conversations.turn(run.id) }?.end)
        assertEquals(ConversationState.SEALED, blocking { store.conversations.conversation(run.conversation) }?.state)
    }

    @Test
    fun `cancelling a run with its tree stops the runs below it, and the turn above goes on`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 1.")) }
            .reply(task("Level 1.")) {
                text("Handing level 2\non.")
                toolCall(DELEGATE_TOOL, delegate("nester", "Level 2."))
            }
            .reply(task("Level 2.")) { toolCall("test.read", """{"path": "a"}""") }
            .reply(ROOT) { text("The run was stopped.") }

        harness().execute {
            val turn = ask()
            read.awaitCalls(1)
            val control = get<TurnControl>()
            val runs = control.turns().filter { it.turn.kind == TurnKind.DELEGATED }
            assertEquals(listOf(1, 2), runs.map { checkNotNull(it.turn.lineage).depth }.sorted())
            assertEquals(List(2) { TurnPhase.RUNNING to channel().chat() }, runs.map { it.phase to it.turn.chat })
            val first = runs.single { it.turn.lineage?.depth == 1 }.turn

            assertTrue(control.cancelRun(checkNotNull(first.lineage).run, tree = true, channel().user("operator")))

            assertIs<TurnOutcome.Completed>(turn.ticket.outcome())
            assertEquals(1, read.cancelled.size)
            val stopped = "[alexandrite:stopped] Operator stopped Sub-agent run ${first.lineage?.run} of nester " +
                "before it ended. What it wrote last: Handing level 2 on. " +
                "Its conversation ${first.conversation} keeps what it did."
            assertEquals(listOf(ToolOutcome.Cancelled to stopped), rootResults().map { it.shown() })
            assertEquals(TurnEndKind.CANCELLED, store.conversations.turn(first.id)?.end)
            assertEquals("The run was stopped.", channel().reply(turn.ticket.turn).completed?.text)
        }
    }

    @Test
    fun `an operator's denial in a run refuses the same call again anywhere in the tree without asking`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("writer", "Write a.")) }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(profile(WRITER)) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(profile(WRITER)) { text("Not written.") }
            .reply(ROOT) { toolCall("fs.write", """{"path": "a"}""") }
            .reply(ROOT) { text("Not written either.") }

        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("operator")))
            ask().ticket.outcome()

            val request = channel().approvals.single()
            assertEquals(lineage(runs(WRITER)[0]).run, request.turn.lineage?.run)
            val denied = ToolOutcome.NotRun(NotRunReason.DENIED)
            assertEquals(
                listOf(
                    ToolOutcome.NotRun(NotRunReason.DENIED, request.id) to Refusals.DENIED_BY_OPERATOR,
                    denied to Refusals.DENIED_IN_TREE,
                ),
                results(request.turn.conversation).map { it.shown() },
            )
            assertEquals(
                listOf(ToolOutcome.Succeeded to "Not written.", denied to Refusals.DENIED_IN_TREE),
                rootResults().map { it.shown() },
            )
            assertEquals(emptyList(), write.calls)
        }
    }

    // Events.

    @Test
    fun `the root turn's channel is shown the run's tool calls, tagged with the run and its profile`() {
        val delegateCall = ToolCallId("delegate")
        val readCall = ToolCallId("read")
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md."), delegateCall) }
            .reply(profile(REVIEWER)) { toolCall("test.read", """{"path": "notes.md"}""", readCall) }
            .reply(profile(REVIEWER)) { text("Fine.") }
            .reply(ROOT) { text("Fine.") }

        harness().execute {
            val capabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild { events(TurnEventKind.entries.toSet()) }
            channel().scriptCapabilities(capabilities)
            val turn = ask()
            turn.ticket.outcome()

            val run = lineage(runs(REVIEWER)[0]).run
            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(delegateCall, DELEGATE_TOOL, ToolActivity.DELEGATE, "reviewer"),
                    TurnEvent.ToolCallUpdated(delegateCall, ToolCallStatus.RUNNING),
                    TurnEvent.ToolCallStarted(readCall, "test.read", ToolActivity.READ, "notes.md", run, REVIEWER_ID),
                    TurnEvent.ToolCallUpdated(readCall, ToolCallStatus.RUNNING, null, run),
                    TurnEvent.ToolCallUpdated(readCall, ToolCallStatus.COMPLETED, null, run),
                    TurnEvent.ToolCallUpdated(delegateCall, ToolCallStatus.COMPLETED),
                ),
                channel().reply(turn.ticket.turn).activity.filter {
                    it is TurnEvent.ToolCallStarted || it is TurnEvent.ToolCallUpdated
                },
            )
            assertEquals(2, channel().reply(turn.ticket.turn).activity.count { it is TurnEvent.UsageUpdated })
        }
    }

    @Test
    fun `a channel that gives each run a reply shows the run's words, calls and report there, apart from the turn`() {
        val delegateCall = ToolCallId("delegate")
        val readCall = ToolCallId("read")
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("reviewer", "Review notes.md."), delegateCall) }
            .reply(profile(REVIEWER)) {
                text("Reading it.")
                toolCall("test.read", """{"path": "notes.md"}""", readCall)
            }
            .reply(profile(REVIEWER)) { text("Fine.") }
            .reply(ROOT) { text("It is fine.") }

        harness().execute {
            val capabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild {
                streaming(true)
                events(TurnEventKind.entries.toSet())
                runReplies(true)
            }
            channel().scriptCapabilities(capabilities)
            val turn = ask()
            turn.ticket.outcome()

            val run = lineage(runs(REVIEWER)[0]).run
            val reply = channel().replies.single { it.request.run != null }
            assertEquals(DelegatedRun(delegateCall, run, REVIEWER_ID, task = "Review notes.md."), reply.request.run)
            assertEquals(run, reply.turn.lineage?.run)
            assertEquals("Reading it.", reply.segments[0])
            assertEquals("Fine.", reply.completed?.text)
            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(readCall, "test.read", ToolActivity.READ, "notes.md", run, REVIEWER_ID),
                    TurnEvent.ToolCallUpdated(readCall, ToolCallStatus.RUNNING, null, run),
                    TurnEvent.ToolCallUpdated(readCall, ToolCallStatus.COMPLETED, null, run),
                ),
                reply.activity.filter { it is TurnEvent.ToolCallStarted || it is TurnEvent.ToolCallUpdated },
            )
            assertEquals(2, reply.activity.count { it is TurnEvent.UsageUpdated })
            val root = channel().reply(turn.ticket.turn)
            assertEquals(
                listOf(
                    TurnEvent.ToolCallStarted(delegateCall, DELEGATE_TOOL, ToolActivity.DELEGATE, "reviewer"),
                    TurnEvent.ToolCallUpdated(delegateCall, ToolCallStatus.RUNNING),
                    TurnEvent.ToolCallUpdated(delegateCall, ToolCallStatus.COMPLETED),
                ),
                root.activity.filter { it is TurnEvent.ToolCallStarted || it is TurnEvent.ToolCallUpdated },
            )
            assertEquals("It is fine.", root.completed?.text)
            assertEquals(emptyList(), channel().violations)
        }
    }

    @Test
    fun `a run stopped in a channel that gives it a reply ends that reply as cancelled`() {
        read.hold()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("nester", "Level 1.")) }
            .reply(task("Level 1.")) { toolCall("test.read", """{"path": "a"}""") }
            .reply(ROOT) { text("The run was stopped.") }

        harness().execute {
            channel().scriptCapabilities(RecordingChannel.DEFAULT_CAPABILITIES.rebuild { runReplies(true) })
            val turn = ask()
            read.awaitCalls(1)
            val control = get<TurnControl>()
            val run = control.turns().single { it.turn.kind == TurnKind.DELEGATED }.turn

            assertTrue(control.cancelRun(checkNotNull(run.lineage).run, tree = true, channel().user("operator")))

            assertIs<TurnOutcome.Completed>(turn.ticket.outcome())
            assertEquals(ReplyEnd.CANCELLED, channel().reply(run.id, run.lineage?.run).awaitEnd().abandoned)
            assertEquals("The run was stopped.", channel().reply(turn.ticket.turn).completed?.text)
        }
    }

    @Test
    fun `four background reviews run concurrently, the fifth is refused, and each report arrives later`() {
        readGate = CompletableDeferred()
        model.reply(ROOT) {
            repeat(5) { number ->
                toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Review $number","background":true}""")
            }
        }.reply(ROOT) { text("Started.") }
        val opening = RequestMatch("a fresh reviewer") {
            it.ofProfile(REVIEWER) && it.history.none { entry -> entry is AssistantEntry }
        }
        repeat(4) { model.reply(opening) { toolCall("test.read", "{}") } }
        repeat(4) { model.reply(profile(REVIEWER)) { text("Review done.") } }
        repeat(4) { model.reply(ROOT) { text("Report received.") } }
        harness().execute {
            val ticket = ask().ticket
            assertIs<TurnOutcome.Completed>(ticket.outcome())
            while (read.calls.size < 4) delay(5)
            val results = rootResults()
            assertEquals(4, results.count { it.outcome is ToolOutcome.Detached })
            assertEquals(1, results.count { it.outcome == ToolOutcome.NotRun(NotRunReason.BACKGROUND_FULL) })
            val jobs = get<TurnControl>().jobs(channel().chat())
            assertEquals(4, jobs.size)
            assertEquals(4, jobs.map { it.id }.toSet().size)
            readGate?.complete(Unit)
            while (get<TurnControl>().jobs(channel().chat()).isNotEmpty()) delay(5)
            while (jobResultCount() < 4) delay(5)
            while (lifecycleEvents.count { it.startsWith("job.ended:") } < 4) delay(5)
            for (job in jobs) {
                val points = listOf("job.started", "job.ended", "agent.spawn", "agent.run.ended")
                for (point in points) {
                    assertEquals(1, lifecycleEvents.count { it == "$point:${job.id}" })
                }
                assertEquals(0, lifecycleEvents.count { it == "tool.backgrounded:${job.id}" })
            }
            val childConversations = runs(REVIEWER).map { it.ids.conversation }.toSet()
            assertEquals(4, childConversations.size)
            for (child in childConversations) {
                assertEquals(ConversationState.SEALED, store.conversations.conversation(child)?.state)
            }
        }
    }

    @Test
    fun `stopping the jobs after the parent completes ends its four background reviews without delivering reports`() {
        readGate = CompletableDeferred()
        model.reply(ROOT) {
            repeat(4) {
                toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Review","background":true}""")
            }
        }.reply(ROOT) { text("Started.") }
        repeat(4) { model.reply(profile(REVIEWER)) { toolCall("test.read", "{}") } }
        harness().execute {
            ask().ticket.outcome()
            while (read.calls.size < 4) delay(5)
            val control = get<TurnControl>()
            val operator = channel().user("operator")
            assertFalse(control.cancel(channel().chat(), operator))
            assertEquals(4, control.jobs(channel().chat()).size)
            control.jobs(channel().chat()).forEach { assertTrue(control.stopJob(channel().chat(), it.id, operator)) }
            while (control.jobs(channel().chat()).isNotEmpty()) delay(5)
            while (lifecycleEvents.count { it.startsWith("job.ended:") } < 4) delay(5)
            for (child in runs(REVIEWER).map { it.ids.conversation }.toSet()) {
                assertEquals(ConversationState.SEALED, store.conversations.conversation(child)?.state)
            }
            val entries = store.transcripts.entries(store.conversations.current(key).id)
            assertEquals(
                0,
                entries.filterIsInstance<UserEntry>().count {
                    it.origin == UserOrigin.SystemWritten("alexandrite.job")
                },
            )
        }
    }

    @Test
    fun `cancelling a background run tree stops its independently running background child`() {
        cancelNestedBackgroundTree(maxBackgroundRuns = 2, expectedReads = 2)
    }

    @Test
    fun `cancelling a background run tree removes a child waiting for a permit before it can execute`() {
        cancelNestedBackgroundTree(maxBackgroundRuns = 1, expectedReads = 1)
    }

    @Test
    fun `a tree stopped while its child waits for the only background slot never starts the child, and frees it`() {
        readGate = CompletableDeferred()
        val afterRan = CompletableDeferred<Unit>()
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, """{"profile":"nester","task":"Parent","background":true}""")
        }.reply(ROOT) { text("Started.") }
            .reply(ROOT) { toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"After","background":true}""") }
            .reply(ROOT) { text("Started again.") }
            .reply(ROOT) { text("Report received.") }
        model.reply(task("Parent")) {
            toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Child","background":true}""")
        }.reply(task("Parent")) { toolCall("test.read", """{"path":"parent"}""") }
        model.reply(task("After")) {
            pause { afterRan.complete(Unit) }
            text("Reviewed.")
        }

        harness(maxBackgroundRuns = 1).execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            val control = get<TurnControl>()
            val parentRun = checkNotNull(read.awaitCalls(1).single().turn.lineage).run
            val child = control.jobs(channel().chat()).single { it.id != parentRun }.id

            assertTrue(control.cancelRun(parentRun, tree = true, channel().user("operator")))
            jobEnded(parentRun).await()
            jobEnded(child).await()
            assertIs<TurnOutcome.Completed>(ask("Again.").ticket.outcome())
            afterRan.await()

            assertEquals(listOf("After"), runs(REVIEWER).map { it.task() })
            assertFalse("${TurnPoints.AGENT_SPAWN.id}:$child" in lifecycleEvents)
            assertTrue(channel().replies.none { it.request.run?.run == child })
        }
    }

    @Test
    fun `a run waits for the background run it started and answers with its report, which never reaches the root`() {
        // The review ends once the run has asked again, so that the run waits for its report.
        val waiting = CompletableDeferred<Unit>()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("nester", "Parent")) }
            .reply(ROOT) { text("Done.") }
        model.reply(task("Parent")) {
            toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Grandchild","background":true}""")
        }.reply(task("Parent")) {
            pause { waiting.complete(Unit) }
            text("Waiting for the review.")
        }.reply(task("Parent")) { text("The review says it is done.") }
        model.reply(profile(REVIEWER)) {
            pause { waiting.await() }
            text("Review done.")
        }

        harness().execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())

            val delegated = rootResults().single { it.toolName == DELEGATE_TOOL }
            assertEquals(ToolOutcome.Succeeded to "The review says it is done.", delegated.shown())
            val parent = runs(NESTER).last()
            assertTrue(
                parent.history.filterIsInstance<UserEntry>()
                    .filter { it.origin == UserOrigin.SystemWritten("alexandrite.job") }
                    .flatMap { it.parts.filterIsInstance<ContextPart>() }
                    .any { "Review done." in it.text },
            )
            assertEquals(0, jobResultCount())
            assertTrue(model.requests.none { store.conversations.turn(it.ids.turn)?.kind == TurnKind.JOB })
        }
    }

    @Test
    fun `with one background permit, a background run waits for its own background run without starving it`() {
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, """{"profile":"nester","task":"Parent","background":true}""")
        }.reply(ROOT) { text("Started.") }
            .reply(ROOT) { text("Report received.") }
        model.reply(task("Parent")) {
            toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Grandchild","background":true}""")
        }.reply(task("Parent")) { text("Waiting for the review.") }
            .reply(task("Parent")) { text("Parent done.") }
        model.reply(profile(REVIEWER)) { text("Review done.") }

        harness(maxBackgroundRuns = 1).execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            withTimeout(5_000) { while (jobResultCount() < 1) delay(5) }

            val report = store.transcripts.entries(store.conversations.current(key).id)
                .filterIsInstance<UserEntry>()
                .single { it.origin == UserOrigin.SystemWritten("alexandrite.job") }
            assertTrue(report.parts.filterIsInstance<ContextPart>().any { "Parent done." in it.text })
            assertEquals(1, runs(REVIEWER).size)
        }
    }

    @Test
    fun `a run that ends at its round limit stops the background runs it started`() {
        readGate = CompletableDeferred()
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("hurried", "Hurry")) }
            .reply(ROOT) { text("Done.") }
        model.reply(task("Hurry")) {
            toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Grandchild","background":true}""")
        }.reply(task("Hurry")) {
            pause { read.awaitCalls(1) }
            text("Out of rounds.")
        }
        model.reply(profile(REVIEWER)) { toolCall("test.read", "{}") }

        harness().execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            withTimeout(5_000) {
                while (read.cancelled.isEmpty() || get<TurnControl>().jobs(channel().chat()).isNotEmpty()) delay(5)
            }

            assertEquals(1, read.cancelled.size)
            assertEquals(0, jobResultCount())
            val delegated = rootResults().single { it.toolName == DELEGATE_TOOL }
            assertEquals(ToolOutcome.Succeeded to "Out of rounds.", delegated.shown())
        }
    }

    private fun cancelNestedBackgroundTree(maxBackgroundRuns: Int, expectedReads: Int) {
        readGate = CompletableDeferred()
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, """{"profile":"nester","task":"Parent","background":true}""")
        }.reply(ROOT) { text("Started.") }
        model.reply(profile(NESTER)) {
            toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Child","background":true}""")
        }.reply(profile(NESTER)) { toolCall("test.read", """{"path":"parent"}""") }
        model.reply(profile(REVIEWER)) { toolCall("test.read", """{"path":"child"}""") }

        harness(maxBackgroundRuns = maxBackgroundRuns).execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            val control = get<TurnControl>()
            withTimeout(5_000) {
                read.awaitCalls(expectedReads)
                while (control.jobs(channel().chat()).size < 2) delay(5)
            }
            val parent = read.calls.single { it.arguments["path"] == JsonPrimitive("parent") }.turn
            val parentRun = checkNotNull(parent.lineage).run
            assertEquals(expectedReads - 1, runs(REVIEWER).size)
            assertEquals(null, store.conversations.turn(parent.id)?.end)

            assertTrue(control.cancelRun(parentRun, tree = true, channel().user("operator")))

            withTimeout(5_000) {
                while (control.jobs(channel().chat()).isNotEmpty()) delay(5)
                while (read.cancelled.size < expectedReads) delay(5)
                while (lifecycleEvents.count { it.startsWith("job.ended:") } < 2) delay(5)
            }
            assertEquals(expectedReads, read.calls.size)
            assertEquals(expectedReads, read.cancelled.size)
            assertEquals(expectedReads - 1, runs(REVIEWER).size)
            assertEquals(TurnEndKind.CANCELLED, store.conversations.turn(parent.id)?.end)
            for (call in read.calls) {
                assertEquals(TurnEndKind.CANCELLED, store.conversations.turn(call.turn.id)?.end)
                assertEquals(ConversationState.SEALED, store.conversations.conversation(call.turn.conversation)?.state)
            }
            assertEquals(0, jobResultCount())
        }
    }

    @Test
    fun `a background writer asks the root chat and inherits its approval chain`() {
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, """{"profile":"writer","task":"Write notes","background":true}""")
        }.reply(ROOT) { text("Started.") }
        model.reply(profile(WRITER)) { toolCall("fs.write", """{"path":"notes"}""") }
            .reply(profile(WRITER)) { text("Written.") }
            .reply(ROOT) { text("Report received.") }
        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator))
            ask().ticket.outcome()
            while (cleared.isEmpty()) delay(5)
            assertEquals(listOf(Clearance(ClearanceKind.HUMAN, operator)), cleared.toList())
            assertEquals(listOf(AgentId("coder"), AgentId("writer")), channel().approvals.single().chain)
        }
    }

    @Test
    fun `a restart reports a background delegation once without rerunning its child`() {
        readGate = CompletableDeferred()
        model.reply(ROOT) {
            toolCall(DELEGATE_TOOL, """{"profile":"reviewer","task":"Review","background":true}""")
        }.reply(ROOT) { text("Started.") }
        model.reply(profile(REVIEWER)) { toolCall("test.read", "{}") }
        harness().execute {
            ask().ticket.outcome()
            while (read.calls.isEmpty()) delay(5)
        }
        val before = model.requests.size
        model.reply(ROOT) { text("The review was interrupted.") }
        harness().execute {
            assertEquals(before, model.requests.size)
            ask("Status?").ticket.outcome()
            assertEquals(1, read.calls.size)
            val request = model.requests.last()
            val notices = request.history.filterIsInstance<UserEntry>().flatMap {
                it.parts.filterIsInstance<ContextPart>()
            }
            assertTrue(
                notices.any { part ->
                    val interrupted = part.text.contains("interrupted by a restart")
                    interrupted && part.text.contains("agent.delegate reviewer")
                },
            )
        }
    }

    @Test
    fun `a run's call that starts in the background is refused before anyone is asked to approve it`() {
        model.reply(ROOT) { toolCall(DELEGATE_TOOL, delegate("runner", "Watch the build.")) }
            .reply(profile(RUNNER)) { toolCall("command.exec", """{"argv":["make"],"background":true}""") }
            .reply(profile(RUNNER)) { text("Could not watch it.") }
            .reply(ROOT) { text("Could not watch it.") }

        harness().execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            val run = runs(RUNNER).first()
            assertEquals(
                ToolOutcome.NotRun(NotRunReason.NOT_PERMITTED) to
                    "A delegated run cannot start a call in the background, as its result could not come back; " +
                    "run it in the foreground.",
                results(run.ids.conversation).single().shown(),
            )
            assertEquals(emptyList(), channel().approvals)
            assertEquals(emptyList(), exec.calls)
        }
    }

    @Test
    fun `a restart seals the conversation of a background run that never got a permit`() {
        model.reply(ROOT) { text("Hello.") }
        var child: ConversationId? = null
        harness().execute {
            val turn = ask().ticket
            turn.outcome()
            val root = store.conversations.current(key)
            val run = RunId("r-queued")
            val lineage = TurnLineage(run, turn.turn, root.id, ToolCallId("call-1"), turn.turn, root.id, 1)
            val created = store.conversations.createDelegated(key, lineage).id
            child = created
            get<JobRecords>().put(
                run,
                JobRecord(
                    root.id,
                    key,
                    channel().chat(),
                    turn.turn,
                    DELEGATE_TOOL,
                    "reviewer",
                    "2026-01-01T00:00:00Z",
                    child,
                ),
            )
        }
        harness().execute {
            assertEquals(ConversationState.SEALED, store.conversations.conversation(checkNotNull(child))?.state)
            assertEquals(emptyMap(), get<JobRecords>().all())
        }
    }

    private suspend fun jobResultCount(): Int {
        val conversation = store.conversations.current(key).id
        return store.transcripts.entries(conversation).filterIsInstance<UserEntry>().count {
            it.origin == UserOrigin.SystemWritten("alexandrite.job")
        }
    }

    private companion object {
        const val REVIEWER = "Review strictly."
        const val WRITER = "Write the change."
        const val PLANNER = "Only plan."
        const val DOER = "Act at once."
        const val READER = "Read the web."
        const val RUNNER = "Run commands."
        const val NESTER = "Hand work down."
        const val HURRIED = "Be quick."
        const val GENERAL_INSTRUCTIONS = "Carry out the task you are given with the tools you have. Report back " +
            "what you did, what you found and anything left undone, concisely; the delegating agent sees only " +
            "your final reply."
        val REVIEWER_ID = AgentId("reviewer")

        /** The requests of a top-level turn, which neither a run nor the judge makes. */
        val ROOT = RequestMatch("the root turn") { request ->
            request.instructions.none { it.id == DELEGATION_SECTION } && !request.isJudge()
        }

        val JUDGE = RequestMatch("the judge") { it.isJudge() }

        /** The requests of a run of the profile whose instructions are [instructions]. */
        fun profile(instructions: String) = RequestMatch("a run of \"$instructions\"") { it.ofProfile(instructions) }

        /** The requests of a run given [task]. */
        fun task(task: String) = RequestMatch("a run given \"$task\"") { it.task() == task }

        fun delegate(profile: String, task: String, context: String? = null): String = JsonObject(
            listOfNotNull(
                "profile" to JsonPrimitive(profile),
                "task" to JsonPrimitive(task),
                context?.let { "context" to JsonPrimitive(it) },
            ).toMap(),
        ).toString()

        fun ModelRequest.ofProfile(instructions: String): Boolean = this.instructions.any { it.text == instructions }

        fun ModelRequest.isJudge(): Boolean = instructions.any { it.id == "permission-judge" }

        /** The task that the run of this request was given, null for a request of no run. */
        fun ModelRequest.task(): String? = history.filterIsInstance<UserEntry>()
            .firstOrNull { it.origin == UserOrigin.SystemWritten(DELEGATION_SOURCE) }
            ?.parts?.filterIsInstance<TextPart>()?.lastOrNull()?.text
    }
}
