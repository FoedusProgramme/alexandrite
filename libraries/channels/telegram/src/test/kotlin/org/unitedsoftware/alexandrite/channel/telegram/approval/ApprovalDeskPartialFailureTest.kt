package org.unitedsoftware.alexandrite.channel.telegram.approval

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.telegram.TelegramBots
import org.unitedsoftware.alexandrite.channel.telegram.TelegramInstanceConfig
import org.unitedsoftware.alexandrite.channel.telegram.WORK
import org.unitedsoftware.alexandrite.channel.telegram.api.HttpBotApi
import org.unitedsoftware.alexandrite.channel.telegram.configOf
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotApi
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeUser
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.channel.telegram.intake.BotSession
import org.unitedsoftware.alexandrite.channel.telegram.intake.ChatTitles
import org.unitedsoftware.alexandrite.channel.telegram.intake.RecordingControl
import org.unitedsoftware.alexandrite.channel.telegram.intake.TestInstance
import org.unitedsoftware.alexandrite.channel.telegram.pairing.TelegramPairingDesk
import org.unitedsoftware.alexandrite.channel.telegram.reply.Outbox
import org.unitedsoftware.alexandrite.channel.telegram.reply.SendLimiter
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.testkit.testHttpEngine
import org.unitedsoftware.alexandrite.testkit.testRecords
import org.unitedsoftware.alexandrite.testkit.testTexts
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/** How [ApprovalDesk] behaves when one part of a multi-message request fails to send, over a real [FakeBotApi]. */
class ApprovalDeskPartialFailureTest {
    private val fake = FakeBotApi()
    private val api = HttpBotApi(configOf(fake), testHttpEngine())
    private val ada = FakeUser(1001, "Ada")
    private val instanceScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun close() {
        instanceScope.cancel()
        api.close()
        fake.close()
    }

    @Test
    fun `a later part that fails to send deletes the parts already sent, notes them, and answers unavailable`() =
        runBlocking {
            val chat = fake.direct(ada)
            val instance = TestInstance(instanceScope)
            val config = TelegramInstanceConfig(
                Secret(fake.token),
                adminUserIds = listOf(ada.id),
                allowedChats = listOf(ada.id),
                apiBaseUrl = fake.baseUrl,
                approvalMinutes = 1,
            )
            val texts = testTexts("alexandrite-channel-telegram")
            val limiter = SendLimiter(chatInterval = Duration.ZERO)
            val session = BotSession(instance, config, api, TelegramBots(), RecordingControl())
            val outbox = Outbox(instance, api, limiter, texts, null, session)
            val gate =
                AccessGate(instance, config, TelegramPairingDesk(Clock.systemUTC(), testRecords()), outbox, null, null)
            gate.onStart()
            val messages = ApprovalMessages()
            val desk = ApprovalDesk(instance, config, gate, outbox, limiter, api, texts, null, ChatTitles(), messages)
            val diff = ReviewPreview.Diff(
                Path.of("/srv/notes.md"),
                (1..200).joinToString("\n") { "+line $it of the new file" },
            )
            val turn = testTurn(chat = ChatAddress(WORK, "${ada.id}")) {
                actor(ChatUser(UserAddress(WORK, "${ada.id}"), "Ada", null, isBot = false, isOperator = true))
            }
            val request = ApprovalRequest.builder(
                "k3x9",
                turn,
                "fs.write",
                ToolRisk.WORKSPACE_WRITE,
                """{"path":"notes.md"}""",
                "the chat is in ask mode",
            )
                .preview(diff)
                .build()
            fake.fail("sendMessage", 500, "boom", nth = 2)

            val answer = desk.ask(request)

            assertEquals(ApprovalAnswer.Unavailable("no administrator can be reached"), answer)
            assertEquals(1, fake.calls("deleteMessage").size)
            assertNotNull(messages.quoted(chat.id, 1))
            assertNull(fake.botMessage(chat.id, 1))
        }
}
