package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformRequest
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QqBotMenuSyncTest {
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `the slash commands become a single-chat menu and command panels`() = runBlocking {
        val platform = Platform()
        try {
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    PlatformAnswer(200, """{"panel_id":"p1"}""")
                }
            }
            platform.server.on("/v2/menu", """{"version":1}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            val menu = platform.server.calls("/v2/menu").single()
            assertEquals("PUT", menu.method)
            assertTrue("\"name\":\"model\"" in menu.body, menu.body)
            assertTrue("\"send_message\":\"/model\"" in menu.body, menu.body)
            assertTrue("\"desc\":\"Pick the model\"" in platform.server.calls("/v2/panels").last().body)

            val panels = platform.server.calls("/v2/panels")
            assertEquals(4, panels.size)
            assertEquals(listOf("GET", "POST", "GET", "POST"), panels.map { it.method })
            assertTrue("\"scope\":\"c2c\"" in panels[1].body, panels[1].body)
            assertTrue("\"scope\":\"group\"" in panels[3].body, panels[3].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `the sync language picks the description the handler gives it`() = runBlocking {
        val platform = Platform("zh-CN")
        try {
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    PlatformAnswer(200, """{"panel_id":"p1"}""")
                }
            }
            platform.server.on("/v2/menu", """{"version":1}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            assertTrue("\"desc\":\"选模型\"" in platform.server.calls("/v2/panels").last().body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `an already published panel is updated instead of created again`() = runBlocking {
        val platform = Platform()
        try {
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(
                        200,
                        """{"records":[{"panel_id":"p1","panel":{"remark":"alexandrite commands"}}],"is_end":true}""",
                    )
                } else {
                    PlatformAnswer(200, """{"id":"p1"}""")
                }
            }
            platform.server.on("/v2/menu", """{"version":2}""")
            platform.server.on("/v2/panels/p1", """{"version":2}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            val calls = platform.server.calls("/v2/panels/p1")
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.method == "PUT" }, calls.joinToString { it.method })
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a menu the platform refuses as too long is published with the count it names`() = runBlocking {
        val platform = Platform()
        try {
            var menus = 0
            platform.server.on("/v2/menu") { _ ->
                menus++
                if (menus == 1) {
                    PlatformAnswer(400, """{"code":40030013,"message":"超出数量限制","limit":5}""")
                } else {
                    PlatformAnswer(200, """{"version":1}""")
                }
            }
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    PlatformAnswer(200, """{"panel_id":"p1"}""")
                }
            }
            QqBotMenuSync(platform.api, platform.config, listOf(ManyCommands)).onOpen()

            val calls = platform.server.calls("/v2/menu")
            assertEquals(2, calls.size)
            assertEquals(5, "\"type\":\"send_message\"".toRegex().findAll(calls[1].body).count(), calls[1].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a command panel the platform refuses as too long is published with the count it names`() = runBlocking {
        val platform = Platform()
        try {
            val posts = mutableListOf<String>()
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    posts += request.body
                    if (posts.size % 2 == 1) {
                        PlatformAnswer(400, """{"code":40030013,"message":"超出数量限制","limit":1}""")
                    } else {
                        PlatformAnswer(200, """{"panel_id":"p1"}""")
                    }
                }
            }
            platform.server.on("/v2/menu", """{"version":1}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            assertEquals(4, posts.size, posts.joinToString("\n"))
            assertTrue("\"/model\"" in posts[0], posts[0])
            assertTrue("\"/whoami\"" in posts[0], posts[0])
            assertFalse("\"/whoami\"" in posts[1], posts[1])
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a menu the platform refuses without a limit is published with fewer items`() = runBlocking {
        val platform = Platform()
        try {
            var menus = 0
            platform.server.on("/v2/menu") { _ ->
                menus++
                if (menus == 1) {
                    PlatformAnswer(400, """{"code":40030013,"message":"超出数量限制"}""")
                } else {
                    PlatformAnswer(200, """{"version":1}""")
                }
            }
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    PlatformAnswer(200, """{"panel_id":"p1"}""")
                }
            }
            QqBotMenuSync(platform.api, platform.config, listOf(ManyCommands)).onOpen()

            val calls = platform.server.calls("/v2/menu")
            assertEquals(2, calls.size, calls.joinToString("\n") { it.body })
            assertEquals(5, "\"type\":\"send_message\"".toRegex().findAll(calls[1].body).count(), calls[1].body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a command panel the platform refuses without a limit is published with fewer items`() = runBlocking {
        val platform = Platform()
        try {
            val posts = mutableListOf<String>()
            platform.server.on("/v2/panels") { request ->
                if (request.method == "GET") {
                    PlatformAnswer(200, """{"records":[],"next_cursor":"","is_end":true}""")
                } else {
                    posts += request.body
                    if (posts.size % 2 == 1) {
                        PlatformAnswer(400, """{"code":40030013,"message":"超出数量限制"}""")
                    } else {
                        PlatformAnswer(200, """{"panel_id":"p1"}""")
                    }
                }
            }
            platform.server.on("/v2/menu", """{"version":1}""")
            QqBotMenuSync(platform.api, platform.config, listOf(Commands)).onOpen()

            assertEquals(4, posts.size, posts.joinToString("\n"))
            assertEquals(1, "\"type\":\"command\"".toRegex().findAll(posts[1]).count(), posts[1])
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a description is cut to the units the platform counts`() {
        val chinese = CommandSpec.builder("model", "x").build()
        val english = CommandSpec.builder("whoami", "x").build()

        fun description(handler: CommandHandler, spec: CommandSpec): String {
            val panel = QqBotMenuSync.panelBody("c2c", listOf(handler to spec), null)["panel"] as JsonObject
            val item = (panel["items"] as JsonArray)[0] as JsonObject
            return (item["desc"] as JsonPrimitive).content
        }

        val chineseHandler = handler(
            listOf(chinese),
            "说明".repeat(20),
        )
        assertEquals(15, description(chineseHandler, chinese).length, description(chineseHandler, chinese))

        val englishHandler = handler(listOf(english), "x".repeat(40))
        assertEquals(30, description(englishHandler, english).length, description(englishHandler, english))
    }

    private fun handler(commands: List<CommandSpec>, text: String): CommandHandler = object : CommandHandler {
        override val commands: List<CommandSpec> = commands

        override fun description(
            command: String,
            language: org.unitedsoftware.alexandrite.sdk.chat.LanguageTag?,
        ): String = text

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) =
            throw UnsupportedOperationException("not used")
    }

    /** Twelve commands, so that the menu takes fewer than there are. */
    private object ManyCommands : CommandHandler {
        override val commands: List<CommandSpec> = (1..12).map { CommandSpec.builder("c$it", "Command $it").build() }

        override fun description(
            command: String,
            language: org.unitedsoftware.alexandrite.sdk.chat.LanguageTag?,
        ): String = commands.first { it.name == command }.description

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) =
            throw UnsupportedOperationException("not used")
    }

    private object Commands : CommandHandler {
        override val commands: List<CommandSpec> = listOf(
            CommandSpec.builder("model", "Pick the model").build(),
            CommandSpec.builder("whoami", "Show the chat and sender").build(),
        )

        override fun description(
            command: String,
            language: org.unitedsoftware.alexandrite.sdk.chat.LanguageTag?,
        ): String = if (language?.value?.startsWith("zh") == true) {
            when (command) {
                "model" -> "选模型"
                else -> "查看 $command"
            }
        } else {
            commands.first { it.name == command }.description
        }

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) =
            throw UnsupportedOperationException("not used")
    }

    private inner class Platform(private val syncLanguage: String? = null) : AutoCloseable {
        val server = FakeApiServer()
        private val http: QqBotHttp
        val api: QqBotApi
        val config = testConfig(server.base, syncLanguage = syncLanguage)

        init {
            server.on("/app/getAppAccessToken", """{"access_token":"access","expires_in":"7200"}""")
            http = QqBotHttp(config)
            api = QqBotApi(http, QqBotTokens(config, http, clock))
        }

        override fun close() {
            http.close()
            server.close()
        }
    }
}
