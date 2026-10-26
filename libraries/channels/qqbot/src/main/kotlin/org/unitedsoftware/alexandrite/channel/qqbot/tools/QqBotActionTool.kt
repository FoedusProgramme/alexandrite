package org.unitedsoftware.alexandrite.channel.qqbot.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.channel.qqbot.channel.QqBotMessageIndex
import org.unitedsoftware.alexandrite.channel.qqbot.channel.QqBotSentMessages
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import kotlin.time.Duration.Companion.minutes

/**
 * Reads what the open platform knows about the group of the turn or the bot itself.
 *
 * The tool takes the action and its arguments, because the platform's surface is a table of endpoints. Only the
 * actions of [QqBotActions.reading] can be named; a read that answers makes the turn carry content the bot cannot
 * vouch for.
 */
@Singleton
@Contribute(Tool::class)
internal class QqBotReadTool(private val apis: QqBotApis) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "qqbot.read",
        description = describe("Reads what the QQ open platform knows", QqBotActions.reading),
        parameters = schema(),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        call(apis, QqBotActions.reading, arguments, context, null, null)
}

/** Changes something of the group this bot acts in: its members, their mute, its blacklist, a message. */
@Singleton
@Contribute(Tool::class)
internal class QqBotActTool(
    private val apis: QqBotApis,
    private val sent: QqBotSentMessages,
    private val index: QqBotMessageIndex,
) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "qqbot.act",
        description = describe(
            "Changes something of a QQ group this bot acts in. A call that ends without an answer may still have " +
                "been carried out, so read what that group holds before calling again",
            QqBotActions.changing,
        ),
        parameters = schema(),
        risk = ToolRisk.EXTERNAL_ACTION,
        timeout = 2.minutes,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        call(apis, QqBotActions.changing, arguments, context, sent, index)
}

/** The arguments every call takes: the action and whatever that action takes. */
private fun schema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            put("description", "The action to call, named as one of the actions the description lists.")
        }
        putJsonObject("params") {
            put("type", "object")
            put("description", "That action's arguments, by the names the description gives them.")
            put("additionalProperties", true)
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}

/** What the model is told these tools are for: every action and its arguments. */
private fun describe(header: String, rows: List<QqBotActions.Row>): String = buildString {
    append(header)
    append(". It calls one action per call, named as 'action', with that action's arguments under 'params'. ")
    append("The group is the chat of the turn: it is never an argument. The actions, with a ? on an argument that ")
    append("may be left out:\n")
    rows.forEach { row ->
        append("- ").append(row.name).append('(')
        append(row.parameters.joinToString(", ") { it.name + (if (it.required) "" else "?") })
        append(") — ").append(row.said).append('\n')
        row.parameters.forEach { parameter ->
            append("    ").append(parameter.name).append(": ").append(parameter.description).append('\n')
        }
    }
}

/** Calls [name] of [rows] in the chat of [context], and says what the platform answered. */
private suspend fun call(
    apis: QqBotApis,
    rows: List<QqBotActions.Row>,
    arguments: JsonObject,
    context: ToolContext,
    sent: QqBotSentMessages?,
    index: QqBotMessageIndex?,
): ToolResult {
    val name = text(arguments, "action")
        ?: return ToolResult("A call names an action, as action.", isError = true)
    val row = rows.firstOrNull { it.name == name }
        ?: return ToolResult(
            "'$name' is no action this call may ask for. It may ask for: " + rows.joinToString(", ") { it.name } + ".",
            isError = true,
        )
    val params = arguments["params"] as? JsonObject ?: JsonObject(emptyMap())
    val missing = row.parameters.filter { it.required && !given(params, it) }
    if (missing.isNotEmpty()) {
        return ToolResult(
            "The action '$name' needs " + missing.joinToString { it.name } + ": " +
                missing.joinToString { "${it.name} (${it.description})" },
            isError = true,
        )
    }
    val chat = context.turn.chat
    val api = apis.serving(chat)
        ?: return ToolResult(
            "The channel instance of $chat is not open, so the platform cannot be asked.",
            isError = true,
        )
    return withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val quoted = index?.quotedTarget(chat, context.trigger?.id)
        val trigger = when {
            name != "recall" -> context.trigger?.id

            text(params, "message_id") != null -> context.trigger?.id

            quoted != null -> quoted

            else -> sent?.lastOf(chat) ?: return@withContext ToolResult(
                "The bot knows no message it sent or this turn quotes, so a recall without message_id names nothing.",
                isError = true,
            )
        }
        when (val answer = row.call(api, chat, params, trigger)) {
            is QqBotAnswer.Ok -> {
                if (!row.changes) context.untrusted("the platform answered what '$name' asked for")
                val body = answer.body?.takeIf { it !is JsonNull }?.toString()
                ToolResult(if (body == null) row.said else "${row.said}\n$body")
            }

            is QqBotAnswer.Failed -> ToolResult(
                "The platform answered no to '$name': ${answer.failure.message}" +
                    (answer.failure.code?.let { " (code $it)" } ?: ""),
                isError = true,
            )
        }
    }
}

/** Whether the call gave [parameter], as an array for an array argument. */
private fun given(params: JsonObject, parameter: QqBotActions.Parameter): Boolean {
    val value = params[parameter.name] ?: return false
    if (value is JsonNull) return false
    if (parameter.array) return value is JsonArray
    return value is JsonPrimitive && value.contentOrNull?.isNotEmpty() == true
}

/** The text a call gave for [name], null when it gave none. */
private fun text(params: JsonObject, name: String): String? =
    (params[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
