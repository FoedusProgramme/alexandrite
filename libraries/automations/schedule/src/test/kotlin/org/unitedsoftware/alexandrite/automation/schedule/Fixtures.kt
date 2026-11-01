package org.unitedsoftware.alexandrite.automation.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.automation.common.TimeSource
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.tool.Mandate
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.RecordingTurnInitiator
import org.unitedsoftware.alexandrite.testkit.RecordingWakeScheduler
import org.unitedsoftware.alexandrite.testkit.TEST_INSTANCE
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.TestTurnTicket
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

const val PLUGIN = "alexandrite-automation-schedule"

/** Sunday, 18:00 in Shanghai. */
val ORIGIN: Instant = Instant.parse("2026-10-18T10:00:00Z")
val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
val CHAT: ChatAddress = testChat()
val OTHER_CHAT: ChatAddress = testChat("other")
val KEY: AgentChatKey = AgentChatKey(AgentId.MAIN, CHAT)
val OPERATOR: ChatUser = testUser("ada", isOperator = true)
val GUEST: ChatUser = testUser("bob")

/** A message turn of [actor] at [chat], its times in Shanghai. */
fun turn(
    actor: ChatUser? = OPERATOR,
    chat: ChatAddress = CHAT,
    kind: TurnKind = TurnKind.MESSAGE,
    block: TurnInfo.Builder.() -> Unit = {},
): TurnInfo = testTurn(kind, chat) {
    actor(actor)
    zone(SHANGHAI)
    block()
}

/** A [TurnInitiator] that records the turns and the mandates it is asked to revoke. */
class Initiator(val recording: RecordingTurnInitiator = RecordingTurnInitiator(PLUGIN)) :
    TurnInitiator by recording {
    val revoked = CopyOnWriteArrayList<Mandate>()

    val turns: List<InitiatedTurn> get() = recording.initiated.map { it.turn }

    val tickets: List<TestTurnTicket> get() = recording.tickets

    override suspend fun revoke(mandate: Mandate) {
        revoked += mandate
    }
}

/**
 * The plugin's parts over [records], on virtual time that reads [origin] at the start of the test, with the chats of
 * `test:main` in Shanghai and in [language].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Schedule(
    private val test: TestScope,
    val records: TestRecords = TestRecords(),
    val config: ScheduleConfig = ScheduleConfig(),
    origin: Instant = ORIGIN,
    language: LanguageTag? = null,
    wrap: (Records) -> Records = { it },
) {
    val initiator = Initiator()
    val channel = RecordingChannel()
    val wakes = RecordingWakeScheduler()
    private val start = test.testScheduler.currentTime
    val time: TimeSource = TimeSource.delaying { origin.plusMillis(test.testScheduler.currentTime - start) }
    private val scope = object : PluginScope, CoroutineScope by test.backgroundScope {}
    private val directory = object : ChannelDirectory {
        override val instances: Set<ChannelInstanceId> = setOf(TEST_INSTANCE)

        override fun channel(instance: ChannelInstanceId): Channel? = channel.takeIf { instance == TEST_INSTANCE }
    }
    private val settings = RecordingAgent().apply {
        scriptAgent(AgentId.MAIN, language = language, zone = SHANGHAI)
    }.settings
    private val clock = Clock.fixed(origin, ZoneOffset.UTC)
    val texts = testTexts(PLUGIN)
    val tasks = TaskRecords(wrap(records.of(PLUGIN)), initiator)
    private val info = PluginInfo(PLUGIN, "Schedule", "test", "", AlexandriteSdk.API_VERSION, emptyList(), "Schedule")
    val scheduling = Scheduling(
        config,
        tasks,
        initiator,
        Notices(directory, settings, texts, clock, scope),
        scope,
        info,
        wakes,
        clock,
        time,
    )
    val control = ScheduleControl(config, tasks, scheduling)
    val create = CreateTool(config, lazyOf(control), clock)
    val list = ListTool(lazyOf(control), clock)
    val cancel = CancelTool(lazyOf(control))
    val command = ScheduleCommand(lazyOf(control), texts, clock)

    /** The texts of the notices sent so far. */
    val notices: List<String> get() = channel.sent.map { it.message.text }

    suspend fun open(): Schedule = apply {
        scheduling.onOpen()
        test.runCurrent()
    }

    /** Closes and stops it, as a shutdown does. */
    suspend fun stop() {
        scheduling.onClose()
        scheduling.onStop()
        test.runCurrent()
    }

    fun advance(by: Duration) {
        test.advanceTimeBy(by)
        test.runCurrent()
    }

    fun now(): Instant = time.now()

    /** Calls [tool] with [arguments] in [turn], under [mandate]. */
    suspend fun call(
        tool: Tool,
        arguments: Map<String, String>,
        turn: TurnInfo = turn(),
        mandate: Mandate? = null,
    ): ToolResult {
        val context = MandateContext(testToolContext(turn), mandate)
        val result = tool.execute(JsonObject(arguments.mapValues { JsonPrimitive(it.value) }), context)
        test.runCurrent()
        return result
    }

    /** Has [tool] make a task of [arguments] in [turn] under [mandate], and returns its id. */
    suspend fun make(arguments: Map<String, String>, turn: TurnInfo = turn(), mandate: Mandate? = MANDATE): String {
        val result = call(create, mapOf("prompt" to "Check the build.") + arguments, turn, mandate)
        check(!result.isError) { result.text }
        return checkNotNull(ID.find(result.text)).value
    }
}

private class MandateContext(inner: ToolContext, private val mandate: Mandate?) : ToolContext by inner {
    override suspend fun mandate(): Mandate? = mandate
}

val MANDATE: Mandate = Mandate("m-test")

val ID: Regex = Regex("t-[0-9a-z]{4}")

val ToolResult.text: String get() = content.joinToString("") { (it as TextPart).text }
