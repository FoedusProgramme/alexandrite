package org.unitedsoftware.alexandrite.channel.qqbot.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.channel.QqBotMessageIndex
import org.unitedsoftware.alexandrite.channel.qqbot.channel.QqBotSentMessages
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.testkit.TestToolContext
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QqBotToolsTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")
    private val group = ChatAddress(instance, "group:g1")

    @Test
    fun `a read names its action and marks what it read as untrusted`() = runBlocking {
        val platform = Platform()
        platform.server.on("/v2/groups/g1/info", """{"group_name":"The group"}""")
        try {
            val context = context()
            val result = platform.read.execute(action("group_info"), context)

            assertTrue(!result.isError, result.content.toString())
            assertTrue("The group" in result.content.toString(), result.content.toString())
            assertTrue(context.untrustedReasons.isNotEmpty(), "a read was not marked untrusted")
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a group is read by the openid of the chat rather than by an argument`() = runBlocking {
        val platform = Platform()
        platform.server.on("/v2/groups/g1/members", """{"members":[],"next_cursor":""}""")
        try {
            val result = platform.read.execute(
                action("group_members", """{"cursor":"c1"}"""),
                context(),
            )

            assertTrue(!result.isError, result.content.toString())
            val calls = platform.server.calls("/v2/groups/g1/members")
            assertEquals(1, calls.size)
            assertTrue("cursor=c1" in calls.single().query, calls.single().query)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a permission the platform withholds is reported as an error`() = runBlocking {
        val platform = Platform()
        platform.server.on("/v2/groups/g1/info", """{"code":11253,"message":"the bot is not whitelisted"}""")
        try {
            val result = platform.read.execute(action("group_info"), context())

            assertTrue(result.isError, result.content.toString())
            assertTrue("11253" in result.content.toString(), result.content.toString())
        } finally {
            platform.close()
        }
    }

    @Test
    fun `an action the table does not hold is refused`() = runBlocking {
        val platform = Platform()
        try {
            val result = platform.read.execute(action("delete_everything"), context())

            assertTrue(result.isError)
            assertTrue("no action" in result.content.toString(), result.content.toString())
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a message of the turn is taken back when the call names none`() = runBlocking {
        val platform = Platform()
        platform.server.on("/v2/groups/g1/messages/m1") {
            org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer(200, "{}")
        }
        try {
            val result = platform.act.execute(
                action("recall", """{"message_id":"m1"}"""),
                context(),
            )

            assertTrue(!result.isError, result.content.toString())
            val call = platform.server.calls("/v2/groups/g1/messages/m1").single()
            assertEquals("DELETE", call.method)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a recall without an id takes back the message the trigger quotes`() = runBlocking {
        val platform = Platform()
        platform.server.on("/v2/groups/g1/messages/quoted1") {
            org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer(200, "{}")
        }
        platform.index.recordQuote(group, "trigger1", "quoted1")
        try {
            val withTrigger = object : ToolContext by context() {
                override val trigger = ChannelMessageRef(group, "trigger1")
            }

            val result = platform.act.execute(action("recall"), withTrigger)

            assertTrue(!result.isError, result.content.toString())
            assertEquals("DELETE", platform.server.calls("/v2/groups/g1/messages/quoted1").single().method)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a recall without an id takes back the bot's last message`() = runBlocking {
        val platform = Platform()
        platform.server.on("/v2/groups/g1/messages/bot1") {
            org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer(200, "{}")
        }
        platform.sent.record(group, "bot1")
        try {
            val result = platform.act.execute(action("recall"), context())

            assertTrue(!result.isError, result.content.toString())
            assertEquals("DELETE", platform.server.calls("/v2/groups/g1/messages/bot1").single().method)
        } finally {
            platform.close()
        }
    }

    private fun context(): TestToolContext = testToolContext(turn = testTurn(chat = group))

    private fun action(name: String, params: String = "{}"): JsonObject =
        org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotWire.json.parseToJsonElement(
            """{"action":"$name","params":$params}""",
        ) as JsonObject

    /** An API of the fake platform, and the tools that talk through it. */
    private inner class Platform : AutoCloseable {
        val server = FakeApiServer()
        private val http: QqBotHttp
        val apis = QqBotApis()
        val sent = QqBotSentMessages()
        val index = QqBotMessageIndex()
        val read: QqBotReadTool
        val act: QqBotActTool

        init {
            server.on("/app/getAppAccessToken", """{"access_token":"access","expires_in":"7200"}""")
            val config = testConfig(server.base)
            http = QqBotHttp(config)
            val tokens = QqBotTokens(config, http, MutableClock(Instant.parse("2026-07-21T00:00:00Z")))
            apis.register(instance, QqBotApi(http, tokens))
            read = QqBotReadTool(apis)
            act = QqBotActTool(apis, sent, index)
        }

        override fun close() {
            http.close()
            server.close()
        }
    }
}
