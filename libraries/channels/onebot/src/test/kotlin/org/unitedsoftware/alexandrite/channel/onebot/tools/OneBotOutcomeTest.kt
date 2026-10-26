package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.onebot.protocol.result.OneBotResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the model is told a call answered.
 *
 * A read is answered with what the platform gave, because that answer is what the call was for: a read answered only
 * with the sentence that the action was carried out leaves the model with nothing to read, which is what the call of
 * a message that read as "The message was read." amounted to.
 */
class OneBotOutcomeTest {
    private val message = buildJsonObject {
        put("message_id", 1811467524L)
        put("message", "不会")
    }

    @Test
    fun `a read is answered with what the platform gave`() {
        val said = OneBotResult.Ok(message).outcome("The message was read.", answered = true)
        assertTrue(said.contains("1811467524"), said)
        assertTrue(said.contains("不会"), said)
    }

    @Test
    fun `a change is answered with what it did rather than with the platform's answer`() {
        val said = OneBotResult.Ok(message).outcome("The message was taken back.", answered = false)
        assertEquals("The message was taken back.", said)
    }

    @Test
    fun `an answer longer than a turn should carry stops, and says that it did`() {
        val long = OneBotResult.Ok(JsonPrimitive("x".repeat(ANSWER_LIMIT * 2)))
            .outcome("The message was read.", answered = true)
        assertTrue(long.contains("stops here"), long.takeLast(60))
        assertTrue(long.length < ANSWER_LIMIT * 2, "an answer of ${long.length} characters was not cut")
    }

    @Test
    fun `a read the platform answered with nothing says so`() {
        val said = OneBotResult.Ok(JsonNull).outcome("The message was read.", answered = true)
        assertEquals("The implementation answered with nothing.", said)
    }

    @Test
    fun `an answer that holds no field is still shown as it is`() {
        val said = OneBotResult.Ok(kotlinx.serialization.json.JsonObject(emptyMap()))
            .outcome("The message was read.", answered = true)
        assertEquals("{}", said)
    }

    @Test
    fun `an answer that did not come says why, whichever kind of action asked`() {
        listOf(true, false).forEach { answered ->
            val said = OneBotResult.Failed(retcode = 100, message = "no such group").outcome("said", answered)
            assertTrue(said.contains("no such group"), said)
            assertTrue(said.contains("100"), said)
        }
    }
}
