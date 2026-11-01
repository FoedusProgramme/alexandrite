package org.unitedsoftware.alexandrite.agent.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.agent.tool.CompanionTool
import org.unitedsoftware.alexandrite.agent.tool.property
import org.unitedsoftware.alexandrite.agent.tool.stringArrayProperty
import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.text.UnifiedDiff
import org.unitedsoftware.alexandrite.internal.tool.ToolRefusal
import org.unitedsoftware.alexandrite.internal.tool.requiredString
import org.unitedsoftware.alexandrite.internal.tool.stringArgument
import org.unitedsoftware.alexandrite.sdk.agent.AgentProfile
import org.unitedsoftware.alexandrite.sdk.agent.ProfileSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.ProfileSummary
import org.unitedsoftware.alexandrite.sdk.turn.SettingChange
import java.nio.file.Path
import java.nio.file.Paths

@Singleton
@Contribute(Tool::class)
internal class ProfileListTool(private val profiles: Profiles) : CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "profile.list",
        description = "Lists the profiles a delegating agent may run as a sub-agent: each one's id, name, " +
            "description, model, tools and permission mode, and whether it is declared by the config, contributed " +
            "by a plugin, or created by the agent. It changes nothing.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("additionalProperties", false)
        },
        risk = ToolRisk.READ_ONLY,
        activity = ToolActivity.LIST,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val all = profiles.all()
        if (all.isEmpty()) return ToolResult("No profile exists yet. profile.create makes one.")
        val lines = all.map { "- ${summaryLine(it)}" }
        return ToolResult((listOf("Profiles:") + lines).joinToString("\n"))
    }

    private fun summaryLine(summary: ProfileSummary): String {
        val profile = summary.profile
        val model = profile.model?.toString() ?: "(the delegating agent's)"
        val mode = profile.mode?.toString() ?: "(tightens whatever mode it runs in)"
        val toolsDeny = profile.toolsDeny.ifEmpty { NONE }.joinToString()
        return "${profile.id} (${profile.name}): ${profile.description} [model=$model, " +
            "toolsAllow=${profile.toolsAllow.joinToString()}, toolsDeny=$toolsDeny, " +
            "mode=$mode, source=${sourceText(summary.source)}]"
    }

    private fun sourceText(source: ProfileSource): String = when (source) {
        is ProfileSource.Config -> "config"
        is ProfileSource.Plugin -> "plugin"
        is ProfileSource.Created -> "created by ${source.by?.displayName ?: "the host"}"
        else -> "unknown"
    }

    private companion object {
        val NONE = listOf("(none)")
    }
}

@Singleton
@Contribute(Tool::class)
internal class ProfileCreateTool(private val profiles: Profiles) :
    ReviewedTool,
    CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "profile.create",
        description = "Creates a profile that a delegating agent may run as a sub-agent. 'id' is the profile's id " +
            "(lowercase words joined by hyphens); 'description' is what a delegating agent reads to choose it; " +
            "'instructions' is its persona text, at most 20000 characters. 'model' (<endpoint>/<model>) and " +
            "'reasoning' default to the delegating agent's; 'toolsAllow' and 'toolsDeny' are tool globs ('*' for " +
            "any), 'toolsAllow' defaulting to every tool; 'mode' only ever tightens the chain it runs in; " +
            "'maxRounds' and 'maxTurnTokens' are optional limits. An id a config entry or a plugin already uses " +
            "cannot be created this way. $REVIEWED_WHEN",
        parameters = createSchema(),
        risk = ToolRisk.PERSISTENT_STATE,
        activity = ToolActivity.EDIT,
        titleArguments = listOf("id"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        val profile = profiles.plannedCreate(draft(arguments))
        CallReview.builder().preview(ReviewPreview.Text(profileText(profile))).build()
    } catch (e: ToolRefusal) {
        CallReview.builder().problem(e.message).build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = try {
        val profile = profiles.plannedCreate(draft(arguments))
        profiles.persistCreate(profile, context.turn.actor)
        ToolResult("Created profile ${profile.id} (${profile.name}).")
    } catch (e: ToolRefusal) {
        ToolResult(e.message, isError = true)
    }

    private fun draft(arguments: JsonObject): ProfileDraft = ProfileDraft(
        id = arguments.requiredString("id"),
        name = arguments.requiredString("name"),
        description = arguments.requiredString("description"),
        instructions = arguments.requiredString("instructions"),
        model = arguments.stringArgument("model"),
        reasoning = arguments.stringArgument("reasoning"),
        toolsAllow = arguments.strings("toolsAllow") ?: listOf("*"),
        toolsDeny = arguments.strings("toolsDeny") ?: emptyList(),
        mode = arguments.stringArgument("mode"),
        maxRounds = arguments.int("maxRounds"),
        maxTurnTokens = arguments.long("maxTurnTokens"),
    )
}

@Singleton
@Contribute(Tool::class)
internal class ProfileEditTool(private val profiles: Profiles) :
    ReviewedTool,
    CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "profile.edit",
        description = "Edits a profile the agent created itself (profile.list shows the source of each one), " +
            "'id' naming it. Every other field is optional: leaving one out keeps the profile's own value; giving " +
            "'model', 'reasoning', 'mode', 'maxRounds' or 'maxTurnTokens' as null clears it. $REVIEWED_WHEN",
        parameters = editSchema(),
        risk = ToolRisk.PERSISTENT_STATE,
        activity = ToolActivity.EDIT,
        titleArguments = listOf("id"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        val id = id(arguments)
        val (current, updated) = profiles.plannedEdit(id, edit(arguments))
        if (current == updated) throw ToolRefusal("The edit changes nothing in profile '$id'.")
        val preview = ReviewPreview.Diff(syntheticPath(id), diff(current, updated).text)
        CallReview.builder().preview(preview).snapshot(hash(current)).build()
    } catch (e: ToolRefusal) {
        CallReview.builder().problem(e.message).build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = try {
        val id = id(arguments)
        val (current, updated) = profiles.plannedEdit(id, edit(arguments))
        val reviewed = context.clearance?.snapshot
        if (reviewed != null && reviewed != hash(current)) throw ToolRefusal(changedSinceReviewed(id))
        profiles.persistEdit(updated, context.turn.actor)
        ToolResult("Edited profile $id.")
    } catch (e: ToolRefusal) {
        ToolResult(e.message, isError = true)
    }

    private fun id(arguments: JsonObject): AgentId = AgentId(arguments.requiredString("id"))

    private fun edit(arguments: JsonObject): ProfileEdit = ProfileEdit(
        name = arguments.stringArgument("name"),
        description = arguments.stringArgument("description"),
        instructions = arguments.stringArgument("instructions"),
        model = arguments.change("model"),
        reasoning = arguments.change("reasoning"),
        toolsAllow = arguments.strings("toolsAllow"),
        toolsDeny = arguments.strings("toolsDeny"),
        mode = arguments.change("mode"),
        maxRounds = arguments.intChange("maxRounds"),
        maxTurnTokens = arguments.longChange("maxTurnTokens"),
    )

    private fun diff(current: AgentProfile, updated: AgentProfile): UnifiedDiff {
        val old = profileText(current)
        val new = profileText(updated)
        return UnifiedDiff.of(old, new, "profile ${current.id} (before)", "profile ${current.id} (after)")
    }
}

@Singleton
@Contribute(Tool::class)
internal class ProfileRemoveTool(private val profiles: Profiles) :
    ReviewedTool,
    CompanionTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "profile.remove",
        description = "Removes a profile the agent created itself (profile.list shows the source of each one), " +
            "'id' naming it. $REVIEWED_WHEN",
        parameters = idOnlySchema("The id of the profile to remove."),
        risk = ToolRisk.PERSISTENT_STATE,
        activity = ToolActivity.DELETE,
        titleArguments = listOf("id"),
    )

    override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = try {
        val id = AgentId(arguments.requiredString("id"))
        val profile = profiles.plannedRemove(id)
        val preview = ReviewPreview.Text("Removing profile $id:\n\n${profileText(profile)}")
        CallReview.builder().preview(preview).snapshot(hash(profile)).build()
    } catch (e: ToolRefusal) {
        CallReview.builder().problem(e.message).build()
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = try {
        val id = AgentId(arguments.requiredString("id"))
        val profile = profiles.plannedRemove(id)
        val reviewed = context.clearance?.snapshot
        if (reviewed != null && reviewed != hash(profile)) throw ToolRefusal(changedSinceReviewed(id))
        profiles.persistRemove(id)
        ToolResult("Removed profile $id.")
    } catch (e: ToolRefusal) {
        ToolResult(e.message, isError = true)
    }
}

/** [profile] as an approver, or `profile.create`'s preview, reads it. */
internal fun profileText(profile: AgentProfile): String {
    val lines = listOf(
        "id: ${profile.id}",
        "name: ${profile.name}",
        "description: ${profile.description}",
        "model: ${profile.model ?: "(the delegating agent's)"}",
        "reasoning: ${profile.reasoning ?: "(the delegating agent's)"}",
        "toolsAllow: ${profile.toolsAllow.joinToString()}",
        "toolsDeny: ${profile.toolsDeny.joinToString().ifEmpty { "(none)" }}",
        "mode: ${profile.mode ?: "(tightens whatever mode it runs in)"}",
        "maxRounds: ${profile.maxRounds ?: "(none)"}",
        "maxTurnTokens: ${profile.maxTurnTokens ?: "(none)"}",
    )
    return (lines + listOf("", "instructions:", profile.instructions)).joinToString("\n")
}

/** A path that exists only to title [id] in a diff preview. */
private fun syntheticPath(id: AgentId): Path = Paths.get("profiles", "$id")

/** What an approved edit or removal of [profile] compares again before it runs. */
private fun hash(profile: AgentProfile): String = "sha256:" + sha256Hex(profileText(profile))

private fun changedSinceReviewed(id: AgentId): String =
    "Profile '$id' changed since this call was reviewed: repeat the call if it still applies."

private fun createSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        property("id", "string", "The profile's id: lowercase words of letters and digits joined by hyphens.")
        property("name", "string", "How the profile calls itself.")
        property("description", "string", "What a delegating agent reads to choose this profile.")
        property("instructions", "string", "Its persona text, at most 20000 characters.")
        property("model", "string", "<endpoint>/<model>; the delegating agent's own when left out.")
        property("reasoning", "string", "A reasoning effort; the delegating agent's own when left out.")
        stringArrayProperty("toolsAllow", "Tool globs it may call, '*' for any; every tool when left out.")
        stringArrayProperty("toolsDeny", "Tool globs it may never call, wins over toolsAllow.")
        property("mode", "string", "A permission mode; only ever tightens the chain it runs in.")
        property("maxRounds", "integer", "The most tool rounds of one of its turns.")
        property("maxTurnTokens", "integer", "The most input and output tokens of one of its turns.")
    }
    put("required", JsonArray(listOf("id", "name", "description", "instructions").map(::JsonPrimitive)))
    put("additionalProperties", false)
}

private fun editSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        property("id", "string", "The id of the profile to edit.")
        property("name", "string", "How the profile calls itself; kept when left out.")
        property("description", "string", "What a delegating agent reads to choose it; kept when left out.")
        property("instructions", "string", "Its persona text; kept when left out.")
        property("model", "string", "<endpoint>/<model>; kept when left out, cleared by null.")
        property("reasoning", "string", "A reasoning effort; kept when left out, cleared by null.")
        stringArrayProperty("toolsAllow", "Tool globs it may call; kept when left out.")
        stringArrayProperty("toolsDeny", "Tool globs it may never call; kept when left out.")
        property("mode", "string", "A permission mode; kept when left out, cleared by null.")
        property(
            "maxRounds",
            "integer",
            "The most tool rounds of one of its turns; kept when left out, cleared by null.",
        )
        property(
            "maxTurnTokens",
            "integer",
            "The most input and output tokens of one of its turns; kept when left out, cleared by null.",
        )
    }
    put("required", JsonArray(listOf(JsonPrimitive("id"))))
    put("additionalProperties", false)
}

private fun idOnlySchema(description: String): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { property("id", "string", description) }
    put("required", JsonArray(listOf(JsonPrimitive("id"))))
    put("additionalProperties", false)
}

private fun JsonObject.strings(name: String): List<String>? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    val array = value as? JsonArray ?: throw ToolRefusal("'$name' must be an array.")
    return array.map { entry ->
        (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ToolRefusal("'$name' must be an array of strings.")
    }
}

private fun JsonObject.int(name: String): Int? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    return (value as? JsonPrimitive)?.intOrNull ?: throw ToolRefusal("'$name' must be a whole number.")
}

private fun JsonObject.long(name: String): Long? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    return (value as? JsonPrimitive)?.longOrNull ?: throw ToolRefusal("'$name' must be a whole number.")
}

/** [name] as a [SettingChange]: absent keeps it, null clears it, a string sets it. */
private fun JsonObject.change(name: String): SettingChange<String> = when {
    name !in this -> SettingChange.Keep
    this[name] == JsonNull -> SettingChange.Reset
    else -> SettingChange.SetTo(stringArgument(name) ?: throw ToolRefusal("'$name' must be a string."))
}

private fun JsonObject.intChange(name: String): SettingChange<Int> = when {
    name !in this -> SettingChange.Keep
    this[name] == JsonNull -> SettingChange.Reset
    else -> SettingChange.SetTo(int(name) ?: throw ToolRefusal("'$name' must be a whole number."))
}

private fun JsonObject.longChange(name: String): SettingChange<Long> = when {
    name !in this -> SettingChange.Keep
    this[name] == JsonNull -> SettingChange.Reset
    else -> SettingChange.SetTo(long(name) ?: throw ToolRefusal("'$name' must be a whole number."))
}

/** When a person sees a change of a profile before it applies. */
private const val REVIEWED_WHEN =
    "A person reviews the change first only when the turn is not an operator's own or the conversation took in " +
        "untrusted content; otherwise it applies at once."
