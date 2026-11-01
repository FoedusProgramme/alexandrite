package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.BOB
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.unitedsoftware.alexandrite.channel.telegram.api.HttpBotApi
import org.unitedsoftware.alexandrite.channel.telegram.api.SendMessage
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeUser
import org.unitedsoftware.alexandrite.engine.jdkhttp.JdkHttpEngine
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeStartException
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatInfo
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.runtime.StopKind
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TelegramIntakeTest {
    private val telegram = TelegramHarness()
    private val fake = telegram.fake

    @AfterTest
    fun close() {
        telegram.close()
    }

    // Submission.

    @Test
    fun `a message of a paired direct chat is submitted with its chat, sender and text`() {
        telegram.run {
            fake.send(telegram.ada, telegram.adaChat, "What does Main.kt do?")

            val submission = assertIs<Submission.Message>(telegram.submitted(1).single())
            val message = submission.message
            assertEquals(Capacity.COUNTED, submission.capacity)
            assertEquals(ChannelMessageRef(telegram.address(ADA), "1"), message.ref)
            assertEquals(
                ChatUser(UserAddress(WORK, "$ADA"), "Ada Lovelace", "ada_test", isBot = false, isOperator = true),
                message.sender,
            )
            assertEquals(ChatInfo(ChatKind.DIRECT, null, "ada_test"), message.chatInfo)
            assertEquals("What does Main.kt do?", message.text)
        }
    }

    @Test
    fun `an admin's command is exempt from the queue bound, a member's counts`() {
        telegram.run {
            fake.send(telegram.ada, telegram.adaChat, "/model deepseek/deepseek-chat")
            fake.send(telegram.bob, telegram.bobChat, "/settings")

            val (admin, member) = telegram.submitted(2).map { assertIs<Submission.Command>(it) }
            assertEquals("model" to "deepseek/deepseek-chat", admin.invocation.name to admin.invocation.arguments)
            assertEquals(Capacity.EXEMPT, admin.capacity)
            assertEquals("/model deepseek/deepseek-chat", admin.message?.text)
            assertEquals(admin.message?.ref, admin.invocation.trigger)
            assertEquals("settings", member.invocation.name)
            assertEquals(Capacity.COUNTED, member.capacity)
        }
    }

    @Test
    fun `a group message reaches the agent only when it names the bot`() {
        telegram.run {
            val bot = HttpBotApi(configOf(fake), JdkHttpEngine()).use {
                it.sendMessage(SendMessage(GROUP, "The build is green."))
            }
            fake.send(telegram.bob, telegram.group, "Lunch at noon?")
            fake.send(telegram.bob, telegram.group, "/start@other_bot")
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot what is new?")
            fake.send(telegram.bob, telegram.group, "And the tests?", replyTo = bot.messageId)
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot")
            telegram.consumed(5)

            val (mention, reply, bare) =
                telegram.submitter.submissions.map { assertIs<Submission.Message>(it).message }
            assertEquals("what is new?", mention.text)
            assertEquals(ChatInfo(ChatKind.GROUP, "Backend", null), mention.chatInfo)
            assertEquals("And the tests?", reply.text)
            assertEquals(true, reply.quote?.senderIsBot)
            assertEquals("@alexandrite_test_bot", bare.text)
            assertEquals(3, telegram.submitter.submissions.size)
        }
    }

    @Test
    fun `a bot that takes every group message answers one that names it not, and offers no code for one`() {
        val every = """, "groupMessages": "all""""
        val builder = telegram.builder("work" to telegram.instance(allowed = "[$ADA]", more = every))
        val other = fake.group(-1001234500009, "Elsewhere")
        telegram.run(builder) {
            fake.send(telegram.bob, other, "Anyone here?")
            fake.send(telegram.bob, telegram.group, "Lunch at noon?")
            fake.send(telegram.bob, telegram.group, "/start@other_bot")
            fake.send(telegram.ada, telegram.adaChat, "Paired.")
            telegram.submitted(1)
            telegram.consumed(4)
        }

        val texts = telegram.submitter.submissions.map { assertIs<Submission.Message>(it).message.text }
        assertEquals(listOf("Paired."), texts)
        assertEquals(emptyList(), fake.botMessages(other.id) + fake.botMessages(GROUP))
    }

    @Test
    fun `a paired group's every message reaches the agent when the bot takes them all`() {
        val every = """, "groupMessages": "all""""
        telegram.run(telegram.builder("work" to telegram.instance(more = every))) {
            fake.send(telegram.bob, telegram.group, "Lunch at noon?")
            fake.send(telegram.bob, telegram.group, "/start@other_bot")
            fake.send(telegram.bob, telegram.group, "@alexandrite_test_bot and you?")
            telegram.consumed(3)
        }

        val texts = telegram.submitter.submissions.map { assertIs<Submission.Message>(it).message.text }
        assertEquals(listOf("Lunch at noon?", "and you?"), texts)
    }

    // The command rules.

    @Test
    fun `a command its issuer may not run is refused in the chat and not submitted`() {
        telegram.run {
            fake.send(telegram.bob, telegram.bobChat, "/model deepseek/deepseek-chat")
            fake.send(telegram.bob, telegram.group, "/cancel")
            fake.send(telegram.bob, telegram.bobChat, "/cancel")

            val cancel = assertIs<Submission.Command>(telegram.submitted(1).single())
            assertEquals(telegram.address(BOB), cancel.chat)
            assertEquals(
                "Only an admin of this bot may use /model.",
                fake.await { botMessages(BOB).singleOrNull() }.text,
            )
            val here = fake.await { botMessages(GROUP).singleOrNull() }
            assertEquals("Only an admin of this bot may use /cancel in this chat.", here.text)
            assertEquals(1L, here.replyTo)
        }
    }

    @Test
    fun `a claimed command no rule names is for admins alone, and one no handler claims passes`() {
        telegram.run(telegram.builder().commandHandler(ClaimedCommands("mode"))) {
            fake.send(telegram.bob, telegram.bobChat, "/mode bypass")
            fake.send(telegram.bob, telegram.group, "/start@alexandrite_test_bot")

            val start = assertIs<Submission.Command>(telegram.submitted(1).single())
            assertEquals("start" to telegram.address(GROUP), start.invocation.name to start.chat)
            val refusal = fake.await { botMessages(BOB).singleOrNull() }
            assertEquals("Only an admin of this bot may use /mode.", refusal.text)
        }

        assertEquals(1, telegram.submitter.submissions.size)
    }

    @Test
    fun `a refused command is told once a minute to each issuer in each chat`() {
        val carol = FakeUser(1003, "Carol")

        telegram.run {
            repeat(3) { fake.send(telegram.bob, telegram.group, "/new") }
            fake.send(carol, telegram.group, "/cancel")
            fake.send(telegram.bob, telegram.bobChat, "/model deepseek/deepseek-chat")
            fake.send(telegram.bob, telegram.group, "/home")
            telegram.consumed(6)
        }

        assertEquals(
            listOf(
                "Only an admin of this bot may use /new in this chat." to 1L,
                "Only an admin of this bot may use /cancel in this chat." to 4L,
            ),
            fake.botMessages(GROUP).map { it.text to it.replyTo }.sortedBy { it.second },
        )
        assertEquals(listOf("Only an admin of this bot may use /model."), fake.botMessages(BOB).map { it.text })
        assertEquals(emptyList(), telegram.submitter.submissions)
    }

    // Admissions.

    @Test
    fun `a full queue is told once a minute, in the chat's language`() {
        val chinese = LanguageTag("zh-CN")
        telegram.control.scriptAgent(AgentId.MAIN, language = chinese)
        val texts = TextCatalog.builder()
            .text(PLUGIN, chinese, "refused.queue_full", "队列已满（最多 {capacity} 条）。")
            .build()
        repeat(2) { telegram.submitter.refuseNext(RefusalReason.QUEUE_FULL, 20) }

        telegram.run(telegram.builder().texts(texts)) {
            fake.send(telegram.bob, telegram.bobChat, "one")
            fake.send(telegram.bob, telegram.bobChat, "two")
            telegram.consumed(2)
        }

        assertEquals(listOf("队列已满（最多 20 条）。"), fake.botMessages(BOB).map { it.text })
        assertEquals(2, telegram.submitter.submissions.size)
    }

    @Test
    fun `a chat no agent serves is told once an hour and warned of once`() {
        repeat(2) { telegram.submitter.refuseNext(RefusalReason.NO_AGENT) }

        val lines = runBlocking {
            logged {
                telegram.run {
                    fake.send(telegram.bob, telegram.bobChat, "one")
                    fake.send(telegram.bob, telegram.bobChat, "two")
                    telegram.consumed(2)
                }
            }
        }

        assertEquals(
            listOf("No agent answers this chat. The bot's operator needs to set one up for it."),
            fake.botMessages(BOB).map { it.text },
        )
        assertEquals(1, lines.count { it.startsWith("WARN") && "No agent serves chat telegram:work:$BOB" in it })
    }

    @Test
    fun `content the bot does not read is told once an hour`() {
        telegram.run {
            repeat(2) { fake.queue(location()) }
            telegram.consumed(2)
        }

        assertEquals(
            listOf("This bot reads text, captions and files, but not this kind of message, so it did not take it."),
            fake.botMessages(BOB).map { it.text },
        )
        assertEquals(emptyList(), telegram.submitter.submissions)
    }

    @Test
    fun `a refusal because the agent closes leaves the update and the ones after it unconfirmed`() {
        telegram.run {
            fake.send(telegram.bob, telegram.bobChat, "zero")
            telegram.submitted(1)
            telegram.submitter.refuseNext(RefusalReason.SHUTTING_DOWN)
            fake.send(telegram.bob, telegram.bobChat, "one")
            fake.send(telegram.bob, telegram.bobChat, "two")
            telegram.submitted(2)
        }

        val texts = telegram.submitter.submissions.map { assertIs<Submission.Message>(it).message.text }
        assertEquals(listOf("zero", "one"), texts)
        assertEquals(2, fake.confirmedOffset)
        assertEquals(2, fake.pendingUpdates)
    }

    @Test
    fun `the offset of the last dispatched update is confirmed at the stop`() {
        telegram.run {
            fake.await { calls("getUpdates").firstOrNull() }
            fake.delay("getUpdates", 30.seconds)
            fake.send(telegram.bob, telegram.bobChat, "one")
            telegram.submitted(1)
            fake.await { calls("getUpdates").getOrNull(1) }
            assertEquals(0, fake.confirmedOffset)
        }

        assertEquals(2, fake.confirmedOffset)
        assertEquals(listOf(null, 2L), fake.offsets)
        assertEquals(0, fake.pendingUpdates)
    }

    @Test
    fun `an update that does not read is skipped, and the ones after it reach the agent`() {
        telegram.run {
            fake.send(telegram.bob, telegram.bobChat, "one")
            fake.queue(
                buildJsonObject {
                    put("from", telegram.bob.json())
                    put("chat", telegram.bobChat.json())
                    put("date", "yesterday")
                    put("text", "broken")
                },
            )
            fake.send(telegram.bob, telegram.bobChat, "two")
            telegram.submitted(2)
        }

        val texts = telegram.submitter.submissions.map { assertIs<Submission.Message>(it).message.text }
        assertEquals(listOf("one", "two"), texts)
    }

    // Start and failures.

    @Test
    fun `a token Telegram does not accept fails the start as invalid config without the token`() {
        val failure = startFailure(telegram.builder("work" to telegram.instance(token = OTHER_TOKEN)))

        assertContains(failure, "Invalid config at 'channels.telegram.instances.work.botToken'")
        assertContains(failure, "Telegram does not accept this bot token")
        assertFalse(OTHER_TOKEN.substringAfter(':') in failure, failure)
    }

    @Test
    fun `a webhook fails the start as invalid config`() {
        fake.webhookUrl = "https://hooks.example.com/telegram"

        val failure = startFailure(telegram.builder())

        assertContains(failure, "Invalid config at 'channels.telegram.instances.work'")
        assertContains(failure, "delete the webhook")
        assertFalse("hooks.example.com" in failure, failure)
    }

    @Test
    fun `one bot in two instances fails the start as invalid config naming both`() {
        val failure = startFailure(telegram.builder("work" to telegram.instance(), "home" to telegram.instance()))

        assertContains(failure, "Invalid config at 'channels.telegram.instances.home.botToken'")
        assertContains(failure, "telegram:work")
        assertFalse(fake.token.substringAfter(':') in failure, failure)
    }

    @Test
    fun `a token revoked while running stops the runtime as a failure`() {
        val termination = telegram.run(telegram.builder().inspectTermination()) {
            fake.await { calls("getUpdates").firstOrNull() }
            fake.fail("getUpdates", 401, "Unauthorized")
            awaitCancellation()
        }

        assertEquals(StopKind.FAILURE, termination.request.kind)
        assertEquals("Telegram no longer accepts the bot token of telegram:work", termination.request.reason)
        assertEquals(PLUGIN, termination.request.plugin)
    }

    private fun startFailure(builder: PluginHarness.Builder): String {
        val error = assertFailsWith<RuntimeStartException> { telegram.run(builder) {} }
        val problem = error.problems.first()
        assertEquals(RuntimeProblemKind.INVALID_CONFIG, problem.kind)
        assertTrue(fake.token.substringAfter(':') !in error.message.orEmpty())
        return problem.message
    }

    private fun location() = buildJsonObject {
        put("from", telegram.bob.json())
        put("chat", telegram.bobChat.json())
        put(
            "location",
            buildJsonObject {
                put("latitude", 51.5)
                put("longitude", -0.12)
            },
        )
    }
}
