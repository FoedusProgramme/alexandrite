package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a call to an action sends, and what it is refused for.
 *
 * This is where a call reaches the platform or does not, so what it forwards is worth asserting: an action sends the
 * arguments it declares and nothing else, so nothing a call writes can reach the platform by a name the action does
 * not take, and an argument that must be given is refused rather than sent as nothing.
 */
class OneBotPrepareTest {
    private val delete = row("delete_msg")
    private val ban = row("set_group_ban")
    private val mute = mapOf("group_id" to "987654", "user_id" to "2222", "duration" to "600")

    @Test
    fun `the arguments a call gives are the ones that are sent`() {
        val prepared = assertIs<Prepared.Ready>(prepare(ban, params(mute), null))
        assertEquals(JsonPrimitive(987654L), prepared.params["group_id"])
        assertEquals(JsonPrimitive(2222L), prepared.params["user_id"])
        assertEquals(JsonPrimitive(600L), prepared.params["duration"])
    }

    @Test
    fun `an action about a message takes the message of the turn when none is given`() {
        val prepared = assertIs<Prepared.Ready>(prepare(delete, JsonObject(emptyMap()), "1534828408"))
        assertEquals(JsonPrimitive(1534828408L), prepared.params["message_id"])
    }

    @Test
    fun `an argument given by a call is used rather than the message of the turn`() {
        val prepared = assertIs<Prepared.Ready>(prepare(delete, params(mapOf("message_id" to "77")), "1534828408"))
        assertEquals(JsonPrimitive(77L), prepared.params["message_id"])
    }

    @Test
    fun `an action about a message and no message of the turn refuses rather than guesses`() {
        val prepared = assertIs<Prepared.Refused>(prepare(delete, JsonObject(emptyMap()), null))
        assertTrue(prepared.said.contains("message_id"), prepared.said)
    }

    @Test
    fun `an argument that must be given and was not is refused, naming it and the action`() {
        val prepared = assertIs<Prepared.Refused>(
            prepare(ban, params(mapOf("group_id" to "987654")), null),
        )
        assertTrue(prepared.said.contains("user_id"), prepared.said)
        assertTrue(prepared.said.contains("set_group_ban"), prepared.said)
    }

    @Test
    fun `an argument a call writes under a name the action does not take reaches nothing`() {
        val prepared = assertIs<Prepared.Ready>(
            prepare(ban, params(mute + mapOf("action" to "set_restart", "group_id_extra" to "1")), null),
        )
        assertEquals(setOf("group_id", "user_id", "duration"), prepared.params.keys)
        assertNull(prepared.params["action"])
    }

    @Test
    fun `the message of the turn is not sent to an action that does not take it`() {
        val prepared = assertIs<Prepared.Ready>(prepare(ban, params(mute), "1534828408"))
        assertEquals(setOf("group_id", "user_id", "duration"), prepared.params.keys)
        assertNull(prepared.params[OneBotActions.MESSAGE_ID])
    }

    @Test
    fun `an argument that may be left out and was not given is not sent at all`() {
        val prepared = assertIs<Prepared.Ready>(
            prepare(row("set_group_kick"), params(mapOf("group_id" to "987654", "user_id" to "2222")), null),
        )
        assertEquals(setOf("group_id", "user_id"), prepared.params.keys)
    }

    @Test
    fun `an argument given as nothing counts as one that was not given`() {
        val prepared = assertIs<Prepared.Refused>(
            prepare(ban, params(mute).withNothing("user_id"), null),
        )
        assertTrue(prepared.said.contains("user_id"), prepared.said)
    }

    @Test
    fun `an argument that asks whether to do something is sent as yes or no, however a call wrote it`() {
        listOf("true" to true, "yes" to true, "false" to false, "no" to false).forEach { (written, expected) ->
            val prepared = assertIs<Prepared.Ready>(
                prepare(row("set_friend_add_request"), params(mapOf("flag" to "f", "approve" to written)), null),
            )
            assertEquals(JsonPrimitive(expected), prepared.params["approve"], written)
        }
    }

    @Test
    fun `an argument that asks whether to do something is not sent when it was not given`() {
        val prepared = assertIs<Prepared.Ready>(
            prepare(row("set_group_kick"), params(mapOf("group_id" to "987654", "user_id" to "2222")), null),
        )
        assertNull(prepared.params["reject_add_request"])
    }

    @Test
    fun `an argument that asks whether to do something and cannot be read as one is refused`() {
        val prepared = assertIs<Prepared.Refused>(
            prepare(row("set_friend_add_request"), params(mapOf("flag" to "f", "approve" to "maybe")), null),
        )
        assertTrue(prepared.said.contains("approve"), prepared.said)
        assertTrue(prepared.said.contains("yes or no"), prepared.said)
    }

    /** The row of [name], which the test expects the table to hold. */
    private fun row(name: String): OneBotActions.Row =
        checkNotNull(OneBotActions.of(name)) { "the table holds no action $name" }

    /** The arguments a call wrote, as text, which is how a model writes them. */
    private fun params(given: Map<String, String>): JsonObject = buildJsonObject {
        given.forEach { (name, value) -> put(name, JsonPrimitive(value)) }
    }

    /** The same arguments, with [name] given as nothing rather than left out. */
    private fun JsonObject.withNothing(name: String): JsonObject = buildJsonObject {
        this@withNothing.forEach { (key, value) -> put(key, value) }
        put(name, JsonNull)
    }
}
