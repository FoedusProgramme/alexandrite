package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.channel.common.pairing.PairedChat
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDecision
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingDesk
import org.unitedsoftware.alexandrite.channel.common.pairing.PairingRequest
import org.unitedsoftware.alexandrite.channel.common.pairing.Unpairing
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.BOB
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeChat
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeUser
import org.unitedsoftware.alexandrite.engine.jdkhttp.AlexandriteEngineJdkHttpIndex
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TelegramPairingTest {
    @TempDir
    lateinit var directory: Path

    private val telegram = TelegramHarness()
    private val fake = telegram.fake
    private val store = MemoryStore()
    private val file: Path get() = directory.resolve("alexandrite.json")

    @AfterTest
    fun close() {
        telegram.close()
    }

    /** The harness of the instance `work` over [store], whose config file declares the chats [allowed] paired. */
    private fun builder(
        allowed: String = "[$ADA]",
        harness: TelegramHarness = telegram,
        admins: String = "[$ADA]",
    ): PluginHarness.Builder = harness.builder(
        "work" to harness.instance(allowed = allowed, admins = admins),
    ).keptConfigFile(file).store(store)

    private fun PluginHarness.Running.desk(): PairingDesk = getAll<PairingDesk>().single()

    private fun code(text: String): String = checkNotNull(CODE.find(text)) { "No code in: $text" }.value

    /** Has [user] send [text] to the bot in [chat], and approves the code the chat gets. */
    private suspend fun PluginHarness.Running.pair(
        user: FakeUser,
        chat: FakeChat,
        text: String = "Hello?",
        harness: TelegramHarness = telegram,
    ): PairingRequest {
        harness.fake.send(user, chat, text)
        val offer = harness.fake.await { botMessages(chat.id).singleOrNull() }
        val request = desk().pending().single { it.chat == harness.address(chat.id) }
        assertEquals(code(offer.text), request.code)
        assertIs<PairingDecision.Approved>(desk().approve(request.code, null))
        return request
    }

    /** The approvals kept as `<chat> by <who>`. */
    private suspend fun approvals(): List<String> =
        store.records.list(PLUGIN, "pairings").map { "${it.key} by ${it.createdBy}" }

    @Test
    fun `an admin's direct chat passes without a code, as if the config allowed it`() {
        telegram.run(builder(allowed = "[]")) {
            fake.send(telegram.ada, telegram.adaChat, "Hello?")

            val message = assertIs<Submission.Message>(telegram.submitted(1).single()).message
            assertEquals("Hello?", message.text)
            assertEquals(emptyList(), desk().pending())
            assertEquals(listOf(PairedChat(telegram.address(ADA), true, null)), desk().paired())
        }
    }

    @Test
    fun `a direct chat that is not paired gets its code once, and is paired at once when the code is approved`() {
        val lines = runBlocking {
            logged {
                telegram.run(builder(allowed = "[]")) {
                    val config = Files.readString(file)
                    fake.send(telegram.bob, telegram.bobChat, "Hello?")
                    val offer = fake.await { botMessages(BOB).singleOrNull() }
                    fake.send(telegram.bob, telegram.bobChat, "Anyone?")
                    telegram.consumed(2)

                    assertEquals(listOf(offer), fake.botMessages(BOB))
                    assertEquals(emptyList(), telegram.submitter.submissions)
                    assertEquals(null, offer.replyTo)
                    val request = desk().pending().single()
                    assertEquals(code(offer.text), request.code)
                    assertEquals(telegram.address(BOB), request.chat)
                    assertEquals(ChatKind.DIRECT, request.kind)
                    assertEquals("Bob", request.requester?.displayName)

                    assertIs<PairingDecision.Approved>(desk().approve(request.code, null))

                    fake.send(telegram.bob, telegram.bobChat, "Now?")
                    val message = assertIs<Submission.Message>(telegram.submitted(1).single()).message
                    assertEquals("Now?", message.text)
                    val paired = desk().paired().single { !it.declared }
                    val approval = checkNotNull(paired.approval)
                    assertEquals(
                        listOf(telegram.address(BOB), false, ChatKind.DIRECT, null, request.requester, null),
                        listOf(
                            paired.chat,
                            paired.declared,
                            approval.kind,
                            approval.title,
                            approval.requester,
                            approval.approvedBy,
                        ),
                    )
                    assertEquals(listOf("telegram:work:$BOB by null"), approvals())
                    assertEquals(config, Files.readString(file))
                }
            }
        }

        assertContains(
            fake.botMessages(BOB).first().text,
            "This chat is not paired with this bot yet. To pair it, give the bot's operator the code ",
        )
        val drops = lines.filter { "is not paired" in it }
        assertEquals(1, drops.size, lines.joinToString("\n"))
        assertContains(drops.single(), "Dropped a message of chat telegram:work:$BOB (direct) from user $BOB @bob_test")
        assertContains(drops.single(), "alexandrite pair <code>")
    }

    @Test
    fun `an approval survives a restart as a record, and the config file is untouched`() {
        lateinit var config: String

        telegram.run(builder()) {
            config = Files.readString(file)
            pair(telegram.bob, telegram.bobChat)
        }
        telegram.run(builder()) {
            fake.send(telegram.bob, telegram.bobChat, "Back again")
            val message = assertIs<Submission.Message>(telegram.submitted(1).single()).message

            assertEquals("Back again", message.text)
            assertEquals(
                listOf(telegram.address(ADA) to true, telegram.address(BOB) to false),
                desk().paired().map { it.chat to it.declared },
            )
        }

        assertEquals(1, fake.botMessages(BOB).size)
        assertEquals(config, Files.readString(file))
    }

    @Test
    fun `no paired chat is warned of at start, a chat paired by an approval or an admin's counts as paired`() {
        val none = "WARN telegram:work: no chat is paired yet: the first message of a chat gets a pairing code"

        val first = runBlocking {
            logged { telegram.run(builder(allowed = "[]", admins = "[]")) { pair(telegram.bob, telegram.bobChat) } }
        }
        val second = runBlocking { logged { telegram.run(builder(allowed = "[]", admins = "[]")) {} } }
        val admin = runBlocking { logged { telegram.run(builder(allowed = "[]")) {} } }

        assertContains(first, none)
        assertFalse(none in second, second.joinToString("\n"))
        assertFalse(none in admin, admin.joinToString("\n"))
    }

    @Test
    fun `a group that is not paired gets a code only when it addresses the bot, and a denied code pairs nothing`() {
        telegram.run(builder()) {
            fake.send(telegram.bob, telegram.group, "Lunch at noon?")
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot are you there?")
            val offer = fake.await { botMessages(GROUP).singleOrNull() }
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot hello?")
            telegram.consumed(3)

            assertEquals(2L, offer.replyTo)
            assertEquals(listOf(offer), fake.botMessages(GROUP))
            val request = desk().pending().single()
            assertEquals(ChatKind.GROUP to "Backend", request.kind to request.title)
            assertIs<PairingDecision.Denied>(desk().deny(code(offer.text), null))
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot please?")
            telegram.consumed(4)

            assertEquals(listOf(offer), fake.botMessages(GROUP))
            assertEquals(emptyList(), desk().pending())
            assertEquals(emptyList(), telegram.submitter.submissions)
            assertEquals(listOf(PairedChat(telegram.address(ADA), true, null)), desk().paired())
            assertEquals(emptyList(), approvals())
        }
    }

    @Test
    fun `an unpaired chat no longer reaches the agent, and a chat the config declares is unpaired only there`() {
        telegram.run(builder()) {
            pair(telegram.bob, telegram.bobChat)
            fake.send(telegram.bob, telegram.bobChat, "one")
            telegram.submitted(1)

            assertEquals(Unpairing.Unpaired, desk().unpair(telegram.address(BOB)))
            fake.send(telegram.bob, telegram.bobChat, "two")
            telegram.consumed(3)

            assertEquals(1, telegram.submitter.submissions.size)
            assertEquals(Unpairing.NotPaired, desk().unpair(telegram.address(BOB)))
            assertEquals(Unpairing.Declared(approvalRemoved = false), desk().unpair(telegram.address(ADA)))
            fake.send(telegram.ada, telegram.adaChat, "still here")
            telegram.submitted(2)
            assertEquals(emptyList(), approvals())
        }
    }

    @Test
    fun `an unpaired chat gets nothing the agent sends, and nothing reaches it unasked`() {
        val forbidden = Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, "the chat is not paired")
        val notice = OutboundMessage.builder("Still there?", MessageKind.NOTICE).build()

        telegram.run(builder()) {
            val channel = checkNotNull(get<ChannelDirectory>().channel(WORK))
            val chat = telegram.address(BOB)
            pair(telegram.bob, telegram.bobChat)
            assertTrue(channel.capabilities(chat).proactive)

            assertEquals(Unpairing.Unpaired, desk().unpair(chat))

            assertFalse(channel.capabilities(chat).proactive)
            assertEquals(forbidden, channel.send(chat, notice))
            assertEquals(forbidden, channel.openReply(ReplyRequest(testTurn(chat = chat), null)).complete(notice))
            assertIs<Delivery.Delivered>(channel.send(telegram.address(ADA), notice))
        }

        assertEquals(1, fake.botMessages(BOB).size)
        assertEquals(emptyList(), fake.calls("sendChatAction").filter { it.params["chat_id"] == JsonPrimitive(BOB) })
    }

    @Test
    fun `a home chat or linked chat that is not paired is warned of once at start`() {
        val elsewhere = ChatAddress(ChannelInstanceId(ChannelType("telegram"), "home"), "7")
        runBlocking {
            val home = ChatSettingsUpdate.builder().home().build()
            telegram.control.settings.update(telegram.address(GROUP, 5), home, null)
            for (member in listOf(telegram.address(BOB), telegram.address(GROUP), elsewhere)) {
                telegram.control.links.link(AgentId.MAIN, telegram.address(ADA), member, null)
            }
        }

        val lines = runBlocking { logged { telegram.run(builder()) {} } }

        val hint = "but not paired, so the bot sends it nothing: add %s to channels.telegram.instances.work." +
            "allowedChats, or approve the code it is offered when it addresses the bot with `alexandrite pair <code>`"
        assertEquals(
            listOf(
                "WARN Chat telegram:work:$GROUP is the home chat of agent main and linked at agent main " +
                    hint.format(GROUP),
                "WARN Chat telegram:work:$BOB is linked at agent main ${hint.format(BOB)}",
            ),
            lines.filter { it.startsWith("WARN") && "not paired" in it },
        )
    }

    @Test
    fun `a chat unpaired while its message waited is not asked to send it again`() {
        TelegramHarness(outcome = null).use { open ->
            open.run(builder(harness = open)) {
                pair(open.bob, open.bobChat, harness = open)
                open.fake.send(open.bob, open.bobChat, "one")
                open.submitted(1)
                assertEquals(Unpairing.Unpaired, desk().unpair(open.address(BOB)))
                open.submitter.tickets.single().complete(TurnOutcome.ShutDown(replayable = true))
            }

            assertEquals(1, open.fake.botMessages(BOB).size)
        }
    }

    @Test
    fun `a chat named through an environment variable is declared, and an approval leaves the file alone`() {
        val carol = FakeUser(1003, "Carol")
        val carolChat = fake.direct(carol)
        val submitter = RecordingTurnSubmitter(TurnOutcome.Completed(null))
        val environment = mapOf("TELEGRAM_TOKEN" to fake.token, "BOB_CHAT" to "$BOB")
        val written =
            """{"channels": {"telegram": {"instances": {"work": {"botToken": "${'$'}{env:TELEGRAM_TOKEN}", """ +
                """"apiBaseUrl": "${fake.baseUrl}", "adminUserIds": [$ADA], """ +
                """"allowedChats": ["${'$'}{env:BOB_CHAT}", $ADA], "polling": {"timeoutSeconds": 1}}}}}}"""
        Files.writeString(file, written)
        val config = RuntimeConfig.builder(directory.resolve("data")).build()
        val plugins =
            PluginSet.of(AlexandriteChannelTelegramIndex(), AlexandriteEngineJdkHttpIndex()) + SubmitterIndex(submitter)
        val spec = RuntimeSpec.builder(config, plugins).configFile(file, environment).build()

        runBlocking {
            withTimeout(60.seconds) {
                AlexandriteRuntime.run(spec) {
                    val desk = services.resolver().getAll(key<PairingDesk>()).single()
                    fake.send(telegram.bob, telegram.bobChat, "Paired through the environment")
                    withTimeout(10.seconds) { submitter.awaitSubmissions(1) }
                    fake.send(carol, carolChat, "Me too?")
                    val code = code(fake.await { botMessages(carolChat.id).singleOrNull() }.text)

                    assertIs<PairingDecision.Approved>(desk.approve(code, null))
                    assertEquals(Unpairing.Declared(approvalRemoved = false), desk.unpair(telegram.address(BOB)))
                    fake.send(carol, carolChat, "Now?")
                    fake.send(telegram.bob, telegram.bobChat, "Still here")
                    withTimeout(10.seconds) { submitter.awaitSubmissions(3) }
                }
            }
        }

        assertEquals(written, Files.readString(file))
        assertEquals(
            listOf("Paired through the environment", "Now?", "Still here"),
            submitter.submissions.map { (it as Submission.Message).message.text },
        )
    }

    /** A plugin that binds [submitter] as the runtime's TurnSubmitter. */
    private class SubmitterIndex(private val submitter: TurnSubmitter) : PluginIndex {
        override val info = PluginInfo(
            "submitter",
            "Submitter",
            "test",
            "",
            AlexandriteSdk.API_VERSION,
            emptyList(),
            SubmitterIndex::class.java.name,
        )
        override val configRoot = "plugins.submitter"

        override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()

        override fun bindings(): List<Binding<*>> =
            listOf(instanceBinding(key<TurnSubmitter>(), submitter, "submitter", "RecordingTurnSubmitter"))
    }

    private companion object {
        val CODE = Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")
    }
}
