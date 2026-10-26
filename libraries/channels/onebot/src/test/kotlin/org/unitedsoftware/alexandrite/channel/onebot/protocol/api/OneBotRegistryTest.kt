package org.unitedsoftware.alexandrite.channel.onebot.protocol.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneBotRegistryTest {
    /** The actions `api/public.md` lists, in its order. */
    private val documented = listOf(
        "send_private_msg",
        "send_group_msg",
        "send_msg",
        "delete_msg",
        "get_msg",
        "get_forward_msg",
        "send_like",
        "set_group_kick",
        "set_group_ban",
        "set_group_anonymous_ban",
        "set_group_whole_ban",
        "set_group_admin",
        "set_group_anonymous",
        "set_group_card",
        "set_group_name",
        "set_group_leave",
        "set_group_special_title",
        "set_friend_add_request",
        "set_group_add_request",
        "get_login_info",
        "get_stranger_info",
        "get_friend_list",
        "get_group_info",
        "get_group_list",
        "get_group_member_info",
        "get_group_member_list",
        "get_group_honor_info",
        "get_cookies",
        "get_csrf_token",
        "get_credentials",
        "get_record",
        "get_image",
        "can_send_image",
        "can_send_record",
        "get_status",
        "get_version_info",
        "set_restart",
        "clean_cache",
    )

    @Test
    fun `the registry lists every public action of the standard`() {
        assertEquals(38, documented.size)
        assertEquals(documented, OneBotRegistry.actions.map { it.name })
        assertTrue(OneBotRegistry.actions.none { it.hidden })
        assertTrue(OneBotRegistry.actions.none { it.name.startsWith(".") })
    }

    @Test
    fun `the registry lists the hidden action apart from the public ones`() {
        assertTrue(OneBotRegistry.QUICK_OPERATION.hidden)
        assertEquals(OneBotCategory.HIDDEN, OneBotRegistry.QUICK_OPERATION.category)
        assertEquals(".handle_quick_operation", OneBotRegistry.QUICK_OPERATION.name)
        assertEquals(listOf(OneBotRegistry.QUICK_OPERATION), OneBotRegistry.hiddenActions)
        assertEquals(OneBotRegistry.QUICK_OPERATION, OneBotRegistry.action(".handle_quick_operation"))
    }

    @Test
    fun `every public action resolves under its own name`() {
        for (name in documented) {
            val call = OneBotRegistry.resolve(name)
            assertTrue(call is OneBotCall.Known, "$name is not known")
            assertEquals(name, call.action)
            assertEquals(OneBotCallSuffix.NONE, call.suffix)
            assertTrue(call.isSupported)
        }
    }

    @Test
    fun `every public action resolves under the async and the rate limited suffix`() {
        for (name in documented) {
            for (suffix in listOf(OneBotCallSuffix.ASYNC, OneBotCallSuffix.RATE_LIMITED)) {
                val call = OneBotRegistry.resolve(name + suffix.suffix)
                assertTrue(call is OneBotCall.Known, "${name + suffix.suffix} is not known")
                assertEquals(name, call.definition.name)
                assertEquals(suffix, call.suffix)
                assertTrue(call.isSupported, "${name + suffix.suffix} is not accepted")
            }
        }
    }

    @Test
    fun `the hidden action takes no derived suffix`() {
        val call = OneBotRegistry.resolve(".handle_quick_operation_async")
        assertTrue(call is OneBotCall.Known)
        assertEquals(".handle_quick_operation", call.definition.name)
        assertEquals(OneBotCallSuffix.ASYNC, call.suffix)
        assertFalse(call.isSupported)
    }

    @Test
    fun `an action of an implementation is kept as it was named`() {
        val call = OneBotRegistry.resolve("napcat_get_group_at_all_remain")
        assertTrue(call is OneBotCall.Unknown)
        assertEquals("napcat_get_group_at_all_remain", call.action)
        assertEquals("napcat_get_group_at_all_remain", call.name)
        assertEquals(OneBotCallSuffix.NONE, call.suffix)
        assertFalse(OneBotRegistry.knows(call.action))

        val async = OneBotRegistry.resolve("napcat_get_group_at_all_remain_async")
        assertTrue(async is OneBotCall.Unknown)
        assertEquals("napcat_get_group_at_all_remain_async", async.action)
        assertEquals("napcat_get_group_at_all_remain", async.name)
        assertEquals(OneBotCallSuffix.ASYNC, async.suffix)
    }

    @Test
    fun `an action name resolves to the action the registry holds`() {
        assertEquals(OneBotRegistry.QUICK_OPERATION, OneBotRegistry.action(".handle_quick_operation"))
        assertEquals("send_msg", OneBotRegistry.action("send_msg")?.name)
        assertNull(OneBotRegistry.action("send_msg_async"))
        assertNull(OneBotRegistry.action("no_such_action"))
    }
}
