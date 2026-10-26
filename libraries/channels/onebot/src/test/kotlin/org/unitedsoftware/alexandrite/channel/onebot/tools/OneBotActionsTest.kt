package org.unitedsoftware.alexandrite.channel.onebot.tools

import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the table of actions lets the agent be asked for, and what it keeps out of reach.
 *
 * The table is the only thing standing between a call and the platform, so what it holds is worth asserting rather
 * than trusting: an action the table leaves out cannot be named at all, and an action it holds is one the platform
 * declares, unless the row says the standard does not have it.
 */
class OneBotActionsTest {
    @Test
    fun `every action the table holds is one the standard declares`() {
        OneBotActions.rows.filterNot { it.extension }.forEach { row ->
            val declared = assertNotNull(OneBotRegistry.action(row.name), "${row.name} is no declared action")
            assertFalse(declared.hidden, "${row.name} is an action the standard hides")
        }
    }

    @Test
    fun `an extension is an action the standard does not declare`() {
        val emoji = assertNotNull(OneBotActions.of("set_msg_emoji_like"))
        assertTrue(emoji.extension, "the emoji response is not an action of the standard")
        assertNull(OneBotRegistry.action("set_msg_emoji_like"), "the standard declares no such action")
    }

    @Test
    fun `no action is declared twice`() {
        val names = OneBotActions.rows.map { it.name }
        assertEquals(names.size, names.toSet().size, names.toString())
    }

    @Test
    fun `the table hands out nothing of the account`() {
        listOf("get_cookies", "get_csrf_token", "get_credentials").forEach { name ->
            assertNull(OneBotActions.of(name), "$name must not be reachable")
        }
    }

    @Test
    fun `the table does not carry out anything by name`() {
        listOf("raw", ".handle_quick_operation").forEach { name ->
            assertNull(OneBotActions.of(name), "$name must not be reachable")
        }
    }

    @Test
    fun `the table restarts nothing and clears nothing`() {
        listOf("set_restart", "clean_cache").forEach { name ->
            assertNull(OneBotActions.of(name), "$name must not be reachable")
        }
    }

    @Test
    fun `an action that reads is not one that changes`() {
        assertFalse(assertNotNull(OneBotActions.of("get_group_member_info")).changes)
        assertFalse(assertNotNull(OneBotActions.of("get_msg")).changes)
        assertTrue(assertNotNull(OneBotActions.of("delete_msg")).changes)
        assertTrue(assertNotNull(OneBotActions.of("set_group_kick")).changes)
    }

    @Test
    fun `an action about a message lets the id of the message be left out`() {
        listOf("delete_msg", "set_msg_emoji_like", "get_msg").forEach { name ->
            val row = assertNotNull(OneBotActions.of(name))
            assertTrue(row.takesAMessage, "$name is about a message")
            val parameter = assertNotNull(row.parameters.firstOrNull { it.name == OneBotActions.MESSAGE_ID })
            assertTrue(parameter.optional, "$name must take the id of the message of the turn")
        }
    }

    @Test
    fun `an action that is not about a message has no such argument`() {
        val row = assertNotNull(OneBotActions.of("set_group_ban"))
        assertFalse(row.takesAMessage)
        assertTrue(row.parameters.none { it.name == OneBotActions.MESSAGE_ID })
    }

    @Test
    fun `every argument of every action is named and explained`() {
        OneBotActions.rows.forEach { row ->
            assertTrue(row.name.isNotBlank(), "an action needs a name")
            assertTrue(row.said.isNotBlank(), "${row.name} says nothing when it is carried out")
            assertTrue(row.parameters.isNotEmpty(), "${row.name} takes nothing")
            row.parameters.forEach { parameter ->
                assertTrue(parameter.name.isNotBlank(), "${row.name} has an argument with no name")
                assertTrue(parameter.description.isNotBlank(), "$parameter of ${row.name} is not explained")
            }
        }
    }

    @Test
    fun `what is not declared cannot be asked for`() {
        assertNull(OneBotActions.of("send_group_msg"))
        assertNull(OneBotActions.of("delete_msg_async"))
        assertNull(OneBotActions.of(""))
    }

    @Test
    fun `uploading a file takes a path of the workspace as the file`() {
        listOf("upload_group_file", "upload_private_file").forEach { name ->
            val row = assertNotNull(OneBotActions.of(name))
            assertTrue(row.extension, "$name is an action the standard does not declare")
            val file = assertNotNull(row.parameters.firstOrNull { it.name == "file" })
            assertEquals(
                OneBotActions.Kind.FILE,
                file.kind,
                "$name takes a file of the workspace rather than a name of one",
            )
        }
    }

    @Test
    fun `reading a file of the platform takes its name rather than a path of the workspace`() {
        val row = assertNotNull(OneBotActions.of("get_file"))
        assertTrue(row.extension)
        val file = assertNotNull(row.parameters.firstOrNull { it.name == "file" })
        assertEquals(OneBotActions.Kind.TEXT, file.kind)
        assertTrue(row.parameters.none { it.kind == OneBotActions.Kind.FILE })
    }

    @Test
    fun `the actions for files name the arguments the implementation asks for`() {
        assertEquals(
            listOf("group_id", "file", "name", "folder"),
            assertNotNull(OneBotActions.of("upload_group_file")).parameters.map { it.name },
        )
        assertEquals(
            listOf("user_id", "file", "name"),
            assertNotNull(OneBotActions.of("upload_private_file")).parameters.map { it.name },
        )
        assertEquals(
            listOf("group_id", "file_id", "busid"),
            assertNotNull(OneBotActions.of("get_group_file_url")).parameters.map { it.name },
        )
    }
}
