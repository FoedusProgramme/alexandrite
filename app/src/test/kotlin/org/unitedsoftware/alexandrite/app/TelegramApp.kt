package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
import org.unitedsoftware.alexandrite.agent.AlexandriteAgentIndex
import org.unitedsoftware.alexandrite.channel.telegram.AlexandriteChannelTelegramIndex
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotApi
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeChat
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeUser
import org.unitedsoftware.alexandrite.commands.AlexandriteCommandsIndex
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.store.sqlite.AlexandriteStoreSqliteIndex
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.tools.AlexandriteToolsIndex
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The app's agent, tools, commands and Telegram plugins over a [FakeBotApi] whose bot has Ada as its admin, Bob as a
 * member and the group Backend, with [model] answering for the agent `coder`.
 */
class TelegramApp(
    val model: ScriptedModel =
        ScriptedModel(models = listOf(ScriptedModel.modelInfo(), ScriptedModel.modelInfo(FAST_MODEL))),
) : AutoCloseable {
    val fake = FakeBotApi()
    val store = MemoryStore()
    val ada = FakeUser(ADA, "Ada", "Lovelace", "ada_test")
    val bob = FakeUser(BOB, "Bob", username = "bob_test")
    val adaChat = fake.direct(ada)
    val bobChat = fake.direct(bob)
    val group = fake.group(GROUP, "Backend")

    fun dataDir(directory: Path): Path = directory.resolve("data")

    /**
     * The app with its data and its config file in [directory], whose paired chats are [paired] and trusted users
     * [trusted], on the SQLite store when [sqlite] and on [store] otherwise.
     */
    fun builder(
        directory: Path,
        paired: List<Long> = listOf(ADA, BOB, GROUP),
        sqlite: Boolean = false,
        trusted: List<Long> = emptyList(),
    ): PluginHarness.Builder {
        val telegram = AlexandriteChannelTelegramIndex()
        val builder = PluginHarness.builder(AlexandriteAgentIndex())
            .plugin(AlexandriteToolsIndex())
            .plugin(AlexandriteCommandsIndex())
            .plugin(telegram)
            .config(AGENT_CONFIG)
            .config(telegram, telegramConfig(paired, trusted))
            .model(model)
            .dataRoot(dataDir(directory))
            .keptConfigFile(directory.toRealPath().resolve("config/alexandrite.json"))
        return if (sqlite) {
            builder.plugin(
                AlexandriteStoreSqliteIndex(),
            ).plugin(AlexandriteEngineSqliteXerialIndex())
        } else {
            builder.store(store)
        }
    }

    fun run(builder: PluginHarness.Builder, block: suspend PluginHarness.Running.() -> Unit) {
        runBlocking { withTimeout(50.seconds) { builder.build().run(block) } }
    }

    /** Has [from] write [text] in [chat], and returns the message's id. */
    fun send(from: FakeUser, chat: FakeChat, text: String): Long = fake.send(from, chat, text).messageId()

    /** The bot's messages in [chat], once there are [count]. */
    fun messages(chat: FakeChat, count: Int): List<FakeBotMessage> =
        fake.await(WAIT) { botMessages(chat.id).takeIf { it.size >= count } }

    /** The bot's messages in [chat], its activity messages left out, once there are [count]. */
    fun bodyMessages(chat: FakeChat, count: Int): List<FakeBotMessage> =
        fake.await(WAIT) { botMessages(chat.id).body().takeIf { it.size >= count } }

    /** The bot's message in [chat] that reads [text], once one does. */
    fun message(chat: FakeChat, text: String): FakeBotMessage =
        fake.await(WAIT) { botMessages(chat.id).firstOrNull { it.text == text } }

    /** Waits until the bot dispatched the first [count] updates and polled again. */
    fun consumed(count: Long) {
        fake.await(WAIT) { confirmedOffset.takeIf { it > count } }
    }

    fun address(chat: FakeChat): ChatAddress = ChatAddress(WORK, "${chat.id}")

    fun key(chat: FakeChat): AgentChatKey = AgentChatKey(CODER, address(chat))

    override fun close() {
        fake.close()
    }

    private fun telegramConfig(paired: List<Long>, trusted: List<Long>): String =
        """{"instances": {"work": {"botToken": "${fake.token}", "apiBaseUrl": "${fake.baseUrl}", """ +
            """"adminUserIds": [$ADA], "trustedUserIds": $trusted, "allowedChats": $paired, """ +
            """"polling": {"timeoutSeconds": 1}, """ +
            """"streaming": {"editIntervalMillis": 0, "groupEditIntervalMillis": 0}}}}"""

    companion object {
        const val ADA = 1001L
        const val BOB = 1002L
        const val GROUP = -1001234500000L
        const val FAST_MODEL = "fast-model"
        const val BOT_MENTION = "@alexandrite_test_bot"

        /** A pairing or link code. */
        val CODE = Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")
        val WORK = ChannelInstanceId(ChannelType("telegram"), "work")
        val CODER = AgentId("coder")
        val WAIT = 10.seconds

        /** How the offer to trust what tainted a conversation starts. */
        const val OFFERED = "Untrusted content came in"

        private val AGENT_CONFIG = """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {
                  "name": "Coder",
                  "model": "scripted/test-model",
                  "reasoning": "low",
                  "channels": {"telegram:work": {}}
                }
              }
            }
        """.trimIndent()
    }
}

/** Waits until the agent lists no turn. */
suspend fun PluginHarness.Running.idle() {
    withTimeout(10.seconds) {
        while (get<TurnControl>().turns(null).isNotEmpty()) delay(10.milliseconds)
    }
}

/** Whether this message is an activity message, folding a stretch of tool calls rather than carrying body text. */
fun FakeBotMessage.isActivity(): Boolean = !text.startsWith("Approval needed") &&
    (
        entities.any { (it as? JsonObject)?.get("type") == JsonPrimitive("expandable_blockquote") } ||
            text.startsWith("Delegating to ") || text.startsWith("Delegated to ")
        )

/** [messages], its activity messages left out. */
fun List<FakeBotMessage>.body(): List<FakeBotMessage> = filterNot { it.isActivity() }

/** The entry's text parts, joined. */
val UserEntry.words: String get() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }

/** The words of the chat message that opened the request's turn. */
val ModelRequest.asked: String
    get() = history.filterIsInstance<UserEntry>().last { it.origin is UserOrigin.FromChat }.words

fun JsonObject.messageId(): Long = (getValue("message_id") as JsonPrimitive).long
