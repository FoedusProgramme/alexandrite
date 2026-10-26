package org.unitedsoftware.alexandrite.channel.onebot.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a group's files are, and taking one of them away.
 *
 * A file that was sent to a group can only be checked or taken back by asking what the group holds, which gives the id
 * and the part a file is known by, and then naming that file. Both halves are here for that reason: the actions that
 * list are of no use without the one that deletes, and the one that deletes cannot be given what it takes without the
 * actions that list.
 */
class OneBotGroupFilesTest {
    @Test
    fun `what a group's files are is read from the group`() {
        listOf("get_group_root_files", "get_group_files_by_folder", "get_group_file_system_info").forEach { name ->
            val row = assertNotNull(OneBotActions.of(name), "the table holds no $name")
            assertTrue(row.extension, "$name is an action the standard does not declare")
            assertTrue(!row.changes, "$name only reads")
            assertEquals("group_id", row.parameters.first().name, "$name names the group")
            assertEquals(OneBotActions.Kind.NUMBER, row.parameters.first().kind)
        }
    }

    @Test
    fun `the folder a group's files are read from is named by the group`() {
        val row = assertNotNull(OneBotActions.of("get_group_files_by_folder"))
        assertEquals(listOf("group_id", "folder_id"), row.parameters.map { it.name })
        assertTrue(row.parameters.none { it.kind == OneBotActions.Kind.FILE }, "a folder of the group is no file here")
    }

    @Test
    fun `a file of a group is taken away by what listing the group's files gives`() {
        val row = assertNotNull(OneBotActions.of("delete_group_file"))
        assertTrue(row.extension, "the standard declares no such action")
        assertTrue(row.changes, "taking a file away changes something")
        assertEquals(listOf("group_id", "file_id", "busid"), row.parameters.map { it.name })
        assertEquals(OneBotActions.Kind.NUMBER, row.parameters.first { it.name == "group_id" }.kind)
        assertEquals(OneBotActions.Kind.NUMBER, row.parameters.first { it.name == "busid" }.kind)
        assertEquals(OneBotActions.Kind.TEXT, row.parameters.first { it.name == "file_id" }.kind)
    }

    @Test
    fun `taking a file away is asked of the tool that changes, and what a group holds of the one that reads`() {
        assertTrue(assertNotNull(OneBotActions.of("delete_group_file")).changes)
        OneBotActions.rows.filter { it.name.startsWith("get_group_") && it.name != "get_group_file_url" }.forEach {
            assertTrue(!it.changes, "${it.name} belongs to the tool that reads")
        }
    }
}
