package org.unitedsoftware.alexandrite.agent.delegation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.agent.profile.Profiles
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.agent.tool.AgentToolContext
import org.unitedsoftware.alexandrite.agent.tool.CompanionTool
import org.unitedsoftware.alexandrite.agent.tool.InTurnResult
import org.unitedsoftware.alexandrite.agent.tool.InTurnTool
import org.unitedsoftware.alexandrite.agent.tool.RenderedTool
import org.unitedsoftware.alexandrite.agent.tool.property
import org.unitedsoftware.alexandrite.agent.tool.stringOrNull
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.internal.tool.booleanArgument
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome

@Singleton
@Contribute(Tool::class)
internal class DelegateTool(
    private val delegator: Delegator,
    private val profiles: Profiles,
    settings: AgentSettings,
) : CompanionTool,
    RenderedTool,
    InTurnTool {
    private val description = description(settings.maxBackgroundRunsPerChat)

    override val definition: ToolDefinition = definition(description)

    override suspend fun definitionFor(turn: TurnInfo): ToolDefinition {
        val listed = try {
            profiles.all()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Turn {} offers {} without its profiles, which cannot be read: {}", turn.id, NAME, e.toString())
            return definition
        }
        val lines = if (listed.isEmpty()) {
            "No profile exists yet."
        } else {
            "Profiles:\n" + listed.joinToString("\n") { summary ->
                val profile = summary.profile
                "- ${profile.id} (${oneLine(profile.name)}): ${oneLine(profile.description)}"
            }
        }
        return definition("$description\n\n$lines")
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        ToolResult("$NAME runs only in a turn of the agent.", isError = true)

    override suspend fun call(arguments: JsonObject, context: AgentToolContext): InTurnResult {
        val background = try {
            arguments.booleanArgument("background") ?: false
        } catch (e: ToolRefusal) {
            return problem(e.message)
        }
        val resume = arguments["resume"]?.let {
            arguments.nonBlank("resume")?.let(::runId)
                ?: return problem("'resume' must be the id of an earlier run, such as r-abc123.")
        }
        val profile = arguments.nonBlank("profile")
            ?: if (resume == null) return problem("Give 'profile', the id of a profile.") else null
        val task = arguments.nonBlank("task") ?: return problem("Give 'task', the whole brief of the sub-agent.")
        if (resume != null && "context" in arguments) return problem("A resumed run goes on from its own history.")
        val fork = when (arguments["context"]?.let { arguments.nonBlank("context").orEmpty() } ?: FRESH) {
            FRESH -> false
            FORK -> true
            else -> return problem("'context' is \"$FRESH\" or \"$FORK\".")
        }
        return delegator.delegate(profile, task, fork, context, background, resume)
    }

    private fun definition(description: String): ToolDefinition = ToolDefinition(
        name = NAME,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                property(
                    "background",
                    "boolean",
                    "Run independently and report later. Defaults to false; synchronous calls run serially.",
                )
                property("profile", "string", "The id of the profile that runs the task, one of those listed.")
                property(
                    "resume",
                    "string",
                    "The id of an earlier run that ended, from its [alexandrite:run …] line, to go on with its " +
                        "own history and the new task instead of starting fresh; its profile is then implied.",
                )
                property(
                    "task",
                    "string",
                    "The whole brief: the sub-agent sees nothing else, so say what to do, what you know that it " +
                        "needs (paths, names, constraints) and what to report back.",
                )
                putJsonObject("context") {
                    put("type", "string")
                    put("enum", JsonArray(listOf(JsonPrimitive(FRESH), JsonPrimitive(FORK))))
                    put(
                        "description",
                        "\"$FRESH\" (the default): the sub-agent starts with the task alone. \"$FORK\": it also " +
                            "reads this conversation up to now.",
                    )
                }
            }
            put("required", JsonArray(listOf(JsonPrimitive("task"))))
            put("additionalProperties", false)
        },
        risk = ToolRisk.AGENT_STATE,
        activity = ToolActivity.DELEGATE,
        titleArguments = listOf("profile", "resume"),
    )

    private companion object {
        const val NAME = DELEGATE_TOOL
        const val FRESH = "fresh"
        const val FORK = "fork"

        fun description(runs: Int): String = "Delegates one task to a sub-agent: a profile with instructions, a " +
            "model and tools of its own, which runs it in a new conversation and returns only its final reply. Each " +
            "of its tool calls needs the same permissions as yours, and it has no tool that you may not call. Use it " +
            "for work that a fresh context does better, such as a review or a self-contained change, and give it " +
            "the whole brief. By default this call waits, and multiple synchronous calls run serially. Set " +
            "background to true to start independent runs that report later; at most $runs may run per chat. A " +
            "run's result ends with its id, which resume takes to give the same sub-agent a follow-up task with " +
            "what it already knows."
    }
}

/** The string argument [name] when it is not blank, null for anything else. */
private fun JsonObject.nonBlank(name: String): String? = stringOrNull(name)?.takeIf { it.isNotBlank() }

private fun problem(text: String): InTurnResult = InTurnResult(text, ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS))

private val logger = LoggerFactory.getLogger(DelegateTool::class.java)

/** The run id that [text] names, null when it is none. */
private fun runId(text: String): RunId? = text.takeIf { RUN_ID.matches(it) }?.let(::RunId)

private val RUN_ID = Regex("r-[0-9a-z]{1,32}")
