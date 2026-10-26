package org.unitedsoftware.alexandrite.channel.onebot.channel

import kotlinx.serialization.json.Json
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.unitedsoftware.alexandrite.channel.onebot.protocol.event.OneBotEventCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commands a report carries, and the messages the bot is addressed by. */
class OneBotCommandsTest {
    private val botId = "10001000"

    @Test
    fun `a command names itself and its arguments after the prefix`() {
        val command = assertIs<OneBotCommands.Parsed>(OneBotCommands.of(group("/model deepseek/one")))
        assertEquals("model", command.name)
        assertEquals("deepseek/one", command.arguments)
    }

    @Test
    fun `a command without arguments carries an empty ones`() {
        val command = assertIs<OneBotCommands.Parsed>(OneBotCommands.of(group("/new")))
        assertEquals("new", command.name)
        assertEquals("", command.arguments)
    }

    @Test
    fun `a mention before a command leaves the command`() {
        val command = assertIs<OneBotCommands.Parsed>(
            OneBotCommands.of(group("/model", at = botId)),
        )
        assertEquals("model", command.name)
    }

    @Test
    fun `a mention after a command leaves the command`() {
        val command = assertIs<OneBotCommands.Parsed>(
            OneBotCommands.of(groupWith("/model", "at")),
        )
        assertEquals("model", command.name)
        assertEquals("", command.arguments)
    }

    @Test
    fun `a bare prefix names no command`() {
        assertNull(OneBotCommands.of(group("/")))
    }

    @Test
    fun `a token that is no command name names no command`() {
        assertNull(OneBotCommands.of(group("/随便说点什么")), "a name is written as the other platform writes one")
        assertNull(OneBotCommands.of(group("/model-two")))
        assertNull(OneBotCommands.of(group("/模型")))
    }

    @Test
    fun `a command may name the target it belongs to`() {
        val command = assertIs<OneBotCommands.Parsed>(OneBotCommands.of(group("/model@10001000 deepseek/one")))
        assertEquals("model", command.name)
        assertEquals("deepseek/one", command.arguments)
    }

    @Test
    fun `text without a prefix names no command`() {
        assertNull(OneBotCommands.of(group("model")))
    }

    @Test
    fun `a prefix that does not start the message names no command`() {
        assertNull(OneBotCommands.of(group("say /model")))
    }

    @Test
    fun `a direct message is the bot's`() {
        assertTrue(addressed(private("hello"), direct = true))
    }

    @Test
    fun `a group message that names nobody is not the bot's`() {
        assertFalse(addressed(group("hello"), direct = false))
    }

    @Test
    fun `a group message that names the bot is the bot's`() {
        assertTrue(addressed(group("hello", at = botId), direct = false))
    }

    @Test
    fun `a group message that names everyone is the bot's`() {
        assertTrue(addressed(group("hello", at = "all"), direct = false))
    }

    @Test
    fun `a group message that names another member is not the bot's`() {
        assertFalse(addressed(group("hello", at = "99999999"), direct = false))
    }

    @Test
    fun `a group message answering the bot is the bot's`() {
        assertTrue(addressed(group("hello", reply = "42"), direct = false) { it == "42" })
    }

    @Test
    fun `a group message answering another message is not the bot's`() {
        assertFalse(addressed(group("hello", reply = "42"), direct = false) { it == "77" })
    }

    /** Whether a report of [message] is the bot's, answering what [sent] holds. */
    private fun addressed(
        event: OneBotEvent.Message,
        direct: Boolean,
        sent: (String) -> Boolean = { false },
    ): Boolean = OneBotCommands.addressed(
        event = event,
        isDirect = direct,
        namesTheBot = { selfId ->
            event.message.segments.any {
                it is org.unitedsoftware.alexandrite.channel.onebot.protocol.message.OneBotSegment.At &&
                    (it.isAll || it.userId == selfId)
            }
        },
        answersTheBot = sent,
    )

    /** A group report whose message is [text] and one segment of [at], or of [reply], before it. */
    private fun group(text: String, at: String? = null, reply: String? = null): OneBotEvent.Message =
        decode(groupJson(text, at, reply))

    private fun groupWith(text: String, at: String) = decode(groupJson(text, at, null))

    private fun groupJson(text: String, at: String?, reply: String?): String = buildString {
        append("""{"time":1515204254,"self_id":$botId,"post_type":"message","message_type":"group",""")
        append(""""sub_type":"normal","message_id":12,"group_id":987654,"user_id":12345678,"message":[""")
        at?.let { append("""{"type":"at","data":{"qq":${if (it == "all") "\"all\"" else it}}},""") }
        reply?.let { append("""{"type":"reply","data":{"id":$it}},""") }
        append("""{"type":"text","data":{"text":"$text"}}],""")
        append(""""raw_message":"$text",""")
        append(""""sender":{"user_id":12345678,"nickname":"someone","role":"member"}}""")
    }

    private fun private(text: String): OneBotEvent.Message = decode(
        buildString {
            append("""{"time":1515204254,"self_id":$botId,"post_type":"message","message_type":"private",""")
            append(""""sub_type":"friend","message_id":12,"user_id":12345678,""")
            append(""""message":[{"type":"text","data":{"text":"$text"}}],"raw_message":"$text",""")
            append(""""sender":{"user_id":12345678,"nickname":"someone"}}""")
        },
    )

    private fun decode(json: String): OneBotEvent.Message =
        assertIs(OneBotEventCodec.decode(Json.parseToJsonElement(json)))
}
