package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.channel.onebot.channel.OneBotApis
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.FloorCheck
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Reads what the platform knows about a chat, a member or a message this bot acts in.
 *
 * One tool serves every action it may be asked for, because an action is a row of
 * [OneBotActions] and not a class: the platform's surface is large, and a row says what an action takes and what it
 * is worth. What a call may ask for is the table, so an action the table leaves out cannot be named at all, which is
 * where the actions that hand out the account's credentials and the ones that restart the implementation are kept.
 */
@Singleton
@Contribute(Tool::class)
internal class OneBotReadTool(private val apis: OneBotApis, private val floor: HardFloor?) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.read",
        description = describe("Reads what the QQ platform knows", OneBotActions.rows.filterNot { it.changes }),
        parameters = schema(),
        risk = ToolRisk.READ_ONLY,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        call(apis, OneBotActions.rows.filterNot { it.changes }, floor, arguments, context)
}

/** Changes something of a chat or a member of the platform this bot acts in. */
@Singleton
@Contribute(Tool::class)
internal class OneBotActTool(private val apis: OneBotApis, private val floor: HardFloor?) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "onebot.act",
        description = describe(
            "Changes something of a QQ chat this bot acts in. A call that ends without an answer may still have " +
                "been carried out, so look at what that chat or group holds before calling again",
            OneBotActions.rows.filter { it.changes },
        ),
        parameters = schema(),
        risk = ToolRisk.EXTERNAL_ACTION,
        timeout = actTimeout,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        call(apis, OneBotActions.rows.filter { it.changes }, floor, arguments, context)
}

/** The arguments every call of these tools takes, which is the action and whatever that action takes. */
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

/** What the model is told these tools are for: every action they may be asked for, and what it takes. */
private fun describe(header: String, rows: List<OneBotActions.Row>): String = buildString {
    append(header)
    append(". It calls one action per call, named as 'action', with that action's arguments under 'params'. ")
    append("The actions it may be asked for, with a ? on the arguments that may be left out:\n")
    rows.forEach { row ->
        append("- ").append(row.name).append('(')
        append(row.parameters.joinToString(", ") { it.name + (if (it.required) "" else "?") })
        append(") \u2014 ").append(row.said).append('\n')
        row.parameters.forEach { parameter ->
            append("    ").append(parameter.name).append(": ").append(parameter.description).append('\n')
        }
    }
}

/**
 * Calls [name] of [rows] in the chat of [context], and says what the platform answered.
 *
 * An action the call may not ask for is refused, and only the arguments its row declares are
 * sent, so nothing a call writes under `params` can reach the platform unless the action takes it by that name.
 */
private suspend fun call(
    apis: OneBotApis,
    rows: List<OneBotActions.Row>,
    floor: HardFloor?,
    arguments: JsonObject,
    context: ToolContext,
): ToolResult {
    val name = arguments.text("action")
        ?: return ToolResult("A call names an action, as action.", isError = true)
    val row = rows.firstOrNull { it.name == name }
        ?: return ToolResult(
            "'$name' is no action this call may ask for. It may ask for: " +
                rows.joinToString(", ") { it.name } + ".",
            isError = true,
        )
    val given = arguments["params"] as? JsonObject ?: JsonObject(emptyMap())
    val params = when (val prepared = prepare(row, given, context.trigger?.id)) {
        is Prepared.Refused -> return ToolResult(prepared.said, isError = true)
        is Prepared.Ready -> prepared.params
    }
    // A file of the workspace is read here, because whether this call may read one is the floor's to say, which a
    // function of the row and the arguments has no way to ask.
    val carried = when (
        val files =
            withFiles(row, params, floor, context) { call -> apis.serving(context.turn.chat).raw(UPLOAD_STREAM, call) }
    ) {
        is Prepared.Refused -> return ToolResult(files.said, isError = true)
        is Prepared.Ready -> files.params
    }
    val api = apis.serving(context.turn.chat)
    val result = api.raw(name, carried)
    // What a read answers is what other people wrote, so a turn that carries it carries content this bot cannot vouch
    // for, and the conversation is marked the way any other content read from outside is marked.
    if (!row.changes && result is OneBotResult.Ok) {
        context.untrusted("the platform answered what '$name' asked for")
    }
    return ToolResult(result.outcome(row.said, answered = !row.changes))
}

/** What a call to an action amounts to: the arguments to send, or why they cannot be sent. */
internal sealed interface Prepared {
    /** The arguments to send, which are the ones [OneBotActions.Row.parameters] declares. */
    class Ready(val params: JsonObject) : Prepared

    /** Why nothing is sent. */
    class Refused(val said: String) : Prepared
}

/**
 * The arguments [row] sends when a call gave [given], with [messageId] standing in for the id of the message of the
 * turn where the row allows it to be left out.
 *
 * Every argument is written the way the standard asks for it, since a model writes them all as text: one that names a
 * message, a group or a length is sent as the number it names, and one that asks whether to do something is sent as
 * yes or no. An argument that must be given and was not, or one that cannot be read as what the row says it is, is
 * refused, naming it and what it is for.
 */
internal fun prepare(row: OneBotActions.Row, given: JsonObject, messageId: String?): Prepared {
    val params = buildJsonObject {
        row.parameters.forEach { parameter ->
            val value = given[parameter.name]
            if (value != null && value !is JsonNull) {
                when (parameter.kind) {
                    OneBotActions.Kind.TEXT -> put(parameter.name, value)

                    OneBotActions.Kind.NUMBER -> put(parameter.name, number(value))

                    OneBotActions.Kind.FILE -> put(parameter.name, value)

                    OneBotActions.Kind.FLAG -> {
                        val flag = boolean(value)
                            ?: return Prepared.Refused(
                                "The action '${row.name}' takes ${parameter.name} as yes or no, not '$value': " +
                                    parameter.description,
                            )
                        put(parameter.name, flag)
                    }
                }
                return@forEach
            }
            // An action about a message means the message of this turn when the call named none. A turn no message
            // started leaves nothing to mean, and a call that had to name one is refused.
            if (parameter.name == OneBotActions.MESSAGE_ID && parameter.optional) {
                val fallback = messageId ?: return Prepared.Refused(
                    "The action '${row.name}' needs ${parameter.name}: ${parameter.description}",
                )
                put(parameter.name, number(JsonPrimitive(fallback)))
                return@forEach
            }
            if (parameter.required) {
                return Prepared.Refused(
                    "The action '${row.name}' needs ${parameter.name}: ${parameter.description}",
                )
            }
        }
    }
    return Prepared.Ready(params)
}

/**
 * [value] as yes or no, null when it says neither.
 *
 * A model writes an argument as text even when it asks whether to do something, so both the words it may write and
 * the values a call may carry are read, and anything else is refused.
 */
private fun boolean(value: JsonElement): Boolean? {
    val primitive = value as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.trim()?.lowercase()) {
        "yes", "y", "1" -> true
        "no", "n", "0" -> false
        else -> null
    }
}

/** [value] as the number it names, or as the text it is when it names no number. */
private fun number(value: JsonElement): JsonElement {
    val text = (value as? JsonPrimitive)?.contentOrNull ?: return value
    return text.toLongOrNull()?.let { JsonPrimitive(it) } ?: value
}

/**
 * [params] with every argument that names a file of this workspace carrying the file itself instead.
 *
 * A path of this machine means nothing to an implementation on another one, so the file is read and handed over as
 * the bytes the platform takes, written the way it takes them. What may be read is the floor's to say: a path it
 * denies is refused here with the reason it gave, and one it allows is read at the path it names, which is the path a
 * link is followed to. A runtime with no floor is one where nothing decides what may be read, so a call that needs a
 * file is always refused.
 *
 * A file larger than a call should carry goes to the implementation in pieces instead, which names it in answer, and
 * what the action carries is that name: a file of any size can be sent, and neither side ever holds it whole. [send]
 * is one call of the action that takes a file in pieces.
 */
internal suspend fun withFiles(
    row: OneBotActions.Row,
    params: JsonObject,
    floor: HardFloor?,
    context: ToolContext,
    send: suspend (JsonObject) -> OneBotResult<JsonElement>,
): Prepared {
    val named = row.parameters.filter { it.kind == OneBotActions.Kind.FILE }
    if (named.isEmpty()) return Prepared.Ready(params)
    val reading = floor ?: return Prepared.Refused(
        "The action '${row.name}' cannot read a file of your workspace: nothing in this runtime decides what may " +
            "be read, so no file is sent.",
    )
    val carried = buildJsonObject {
        params.forEach { (key, value) -> put(key, value) }
        named.forEach { parameter ->
            val given = params[parameter.name]?.jsonPrimitive?.contentOrNull
                ?: return Prepared.Refused(
                    "The action '${row.name}' needs ${parameter.name}: ${parameter.description}",
                )
            // A file tool takes a path that is absolute, one relative to the workspace or one under the home
            // directory, and asks the floor about the path that comes of it. What a call may name is the same, so the
            // path is resolved the same way before the floor is asked, which is told of an absolute path alone.
            val expanded = when {
                given == "~" -> home
                given.startsWith("~/") || given.startsWith("~\\") -> "$home${given.substring(1)}"
                else -> given
            }
            val path = try {
                Path.of(expanded)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                return Prepared.Refused("'$given' is no path: ${parameter.description}")
            }
            val absolute = when {
                path.isAbsolute -> path.normalize()

                context.workspace == null -> return Prepared.Refused(
                    "'$given' is a relative path, and this call has no workspace to resolve it against: " +
                        "give an absolute path.",
                )

                else -> try {
                    context.openWorkspace().resolve(path).normalize()
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    return Prepared.Refused("'$given' could not be resolved against your workspace: ${e.message}")
                }
            }
            val allowed = when (val check = reading.checkRead(absolute, context.turn.agent)) {
                is FloorCheck.Denied -> return Prepared.Refused(
                    "The action '${row.name}' may not read '$given': ${check.reason}.",
                )

                is FloorCheck.Ask -> return Prepared.Refused(
                    "The action '${row.name}' may not read '$given' without approval: ${check.reason}.",
                )

                is FloorCheck.Allowed -> check.canonical

                else -> return Prepared.Refused(
                    "The action '${row.name}' may not read '$given': the floor gave no answer this version knows.",
                )
            }
            val size = try {
                withContext(Dispatchers.IO) { Files.size(allowed) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                return Prepared.Refused("The action '${row.name}' could not read '$given': ${e.message}")
            }
            if (size > FILE_LIMIT) {
                // Too large to carry in one request, so it goes in pieces and what is carried is the name the
                // implementation gives it. A file it did not take whole is never sent on as if it were.
                val called = params["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
                    ?: allowed.fileName?.toString()
                    ?: "file"
                val name = streamFile(allowed, called, send) ?: return Prepared.Refused(
                    "The action '${row.name}' could not send '$given': the implementation took no file in pieces, " +
                        "or named none to send on by.",
                )
                context.touched(allowed)
                put(parameter.name, name)
                return@forEach
            }
            val bytes = try {
                withContext(Dispatchers.IO) { Files.readAllBytes(allowed) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                return Prepared.Refused("The action '${row.name}' could not read '$given': ${e.message}")
            }
            context.touched(allowed)
            put(parameter.name, BYTES_PREFIX + Base64.getEncoder().encodeToString(bytes))
        }
    }
    return Prepared.Ready(carried)
}

/** What the platform is told a file's bytes are, which is how it is handed one it cannot reach itself. */
private const val BYTES_PREFIX: String = "base64://"

/**
 * How long a change of this channel may take, which a file of the workspace makes long.
 *
 * A file larger than one request carries is sent to the implementation in pieces, each of them a call of its own,
 * and is then sent on, so the timeout a turn takes by default is too short for one and ends the call with nothing
 * known of what became of it. This is a ceiling: a call that is done sooner ends sooner.
 */
private val actTimeout: Duration = 5.minutes

/** The home directory, which a path of a call may name with a leading `~` the way a file tool takes one. */
private val home: String = System.getProperty("user.home").orEmpty()
