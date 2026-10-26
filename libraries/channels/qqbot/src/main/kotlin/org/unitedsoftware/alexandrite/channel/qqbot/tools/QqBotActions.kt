package org.unitedsoftware.alexandrite.channel.qqbot.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.unitedsoftware.alexandrite.channel.qqbot.mapping.QqBotChats
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotAnswer
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.protocol.QqBotFailure
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind

/**
 * The actions of the open platform this channel offers the agent, each declared once.
 *
 * The official API is a set of REST endpoints and has no action names; the names here are this repository's own, and a
 * call still makes the endpoint the row names. A row says what an action is for and what it takes, so a tool can
 * describe the table to the model and refuse an action that is not in it.
 */
internal object QqBotActions {
    internal class Parameter(
        val name: String,
        val description: String,
        val required: Boolean = true,
        /** Whether the value is an array of strings, which is sent as it arrives. */
        val array: Boolean = false,
    )

    /**
     * One action: the endpoint it calls, the arguments it takes, and whether it changes something.
     *
     * The group id always comes from the chat of the call, never from the model, so a call cannot reach another chat.
     */
    internal class Row(
        val name: String,
        val said: String,
        val parameters: List<Parameter>,
        val changes: Boolean,
        val call: suspend (QqBotApi, ChatAddress, JsonObject, String?) -> QqBotAnswer,
    )

    /** The reads: what the platform knows about a group or the bot. */
    val reading: List<Row> = listOf(
        Row("bot_info", "The bot was read.", emptyList(), changes = false) { api, _, _, _ ->
            api.get("/users/@me")
        },
        Row("group_info", "The group was read.", emptyList(), changes = false) { api, chat, _, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group, so it has no group information.")
            api.get("/v2/groups/$group/info")
        },
        Row("bot_state", "The state of the bot in the group was read.", emptyList(), changes = false) {
                api,
                chat,
                _,
                _,
            ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            api.get("/v2/groups/$group/bot_state")
        },
        Row(
            "join_requests",
            "The requests to join the group were read.",
            listOf(
                Parameter("cursor", "The cursor of the page to read, empty for the first page.", required = false),
                Parameter("limit", "How many requests one page holds, at most 50.", required = false),
            ),
            changes = false,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            api.get("/v2/groups/$group/join_request_list", query(params, "cursor", "limit"))
        },
        Row("group_mute_state", "The mute state of the group was read.", emptyList(), changes = false) {
                api,
                chat,
                _,
                _,
            ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            api.get("/v2/groups/$group/restrict_chat_setting")
        },
        Row(
            "group_members",
            "The members of the group were read.",
            listOf(Parameter("cursor", "The cursor of the page to read, empty for the first page.", required = false)),
            changes = false,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            api.get("/v2/groups/$group/members", query(params, "cursor"))
        },
        Row(
            "group_member",
            "One member of the group was read.",
            listOf(Parameter("member_openid", "The openid of the member in this group.")),
            changes = false,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            val member = text(params, "member_openid") ?: return@Row refused("A call names member_openid.")
            api.get("/v2/groups/$group/members/$member")
        },
        Row(
            "blacklist",
            "The blacklist of the group was read.",
            listOf(
                Parameter("cursor", "The cursor of the page to read, empty for the first page.", required = false),
                Parameter("limit", "How many entries one page holds, at most 100.", required = false),
            ),
            changes = false,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            api.get("/v2/groups/$group/member_blacklist", query(params, "cursor", "limit"))
        },
    )

    /** The actions that change something. */
    val changing: List<Row> = listOf(
        Row(
            "recall",
            "The message was taken back.",
            listOf(
                Parameter(
                    "message_id",
                    "The id of the message to take back; the bot's last message in this chat when left out. A " +
                        "user's message must always be named explicitly.",
                    required = false,
                ),
            ),
            changes = true,
        ) { api, chat, params, trigger ->
            val id = text(params, "message_id") ?: trigger
                ?: return@Row refused("A call names message_id when the bot sent no message this process knows.")
            val openid = QqBotChats.openid(chat) ?: return@Row refused("This instance serves no $chat.")
            when (QqBotChats.kind(chat)) {
                ChatKind.GROUP -> api.delete("/v2/groups/$openid/messages/$id")
                ChatKind.DIRECT -> api.delete("/v2/users/$openid/messages/$id")
                else -> refused("This instance serves no $chat.")
            }
        },
        Row(
            "approve_join_request",
            "The request to join the group was answered.",
            listOf(
                Parameter("member_openid", "The openid of the member who asked to join."),
                Parameter("op", "approve to let the member in, decline to refuse."),
                Parameter("join_request_id", "The id of the request, from join_requests.", required = false),
                Parameter("reject_reason", "Why a request was declined.", required = false),
                Parameter("add_to_member_blacklist", "Whether a declined member is blacklisted too.", required = false),
            ),
            changes = true,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            val member = text(params, "member_openid") ?: return@Row refused("A call names member_openid.")
            if (text(params, "op") == null) return@Row refused("A call names op: approve or decline.")
            api.post(
                "/v2/groups/$group/approval_join_request/$member",
                bodyOf(params, "op", "join_request_id", "reject_reason", "add_to_member_blacklist"),
            )
        },
        Row(
            "set_member_mute",
            "The mute of members was changed.",
            listOf(
                Parameter(
                    "members",
                    "The changes, each {op: add|update|del, member_openid, mute_expire_at?}; at most 20.",
                    array = true,
                ),
            ),
            changes = true,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            val members = params["members"] as? JsonArray ?: return@Row refused("A call names members as an array.")
            api.post("/v2/groups/$group/restrict_chat_setting", buildJsonObject { put("members", members) })
        },
        Row(
            "remove_members",
            "The members were removed from the group.",
            listOf(
                Parameter("member_openids", "The openids of the members to remove, at most 20.", array = true),
                Parameter(
                    "add_to_member_blacklist",
                    "Whether the removed members are blacklisted too.",
                    required = false,
                ),
            ),
            changes = true,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            val ids = params["member_openids"] as? JsonArray ?: return@Row refused("A call names member_openids.")
            api.post(
                "/v2/groups/$group/batch_remove_members",
                bodyOf(params, "add_to_member_blacklist").let { extra ->
                    buildJsonObject {
                        put("member_openids", ids)
                        extra.forEach { (name, value) -> put(name, value) }
                    }
                },
            )
        },
        Row(
            "blacklist_action",
            "The blacklist of the group was changed.",
            listOf(
                Parameter("op", "add to put members on the blacklist, del to take them off."),
                Parameter("member_openids", "The openids to change, at most 20.", array = true),
            ),
            changes = true,
        ) { api, chat, params, _ ->
            val group = groupOf(chat) ?: return@Row refused("This chat is no group.")
            val ids = params["member_openids"] as? JsonArray ?: return@Row refused("A call names member_openids.")
            val op = text(params, "op") ?: return@Row refused("A call names op: add or del.")
            api.post(
                "/v2/groups/$group/member_blacklist",
                buildJsonObject {
                    put("op", op)
                    put("member_openids", ids)
                },
            )
        },
    )

    /** Every action, in the order the tools describe them. */
    val all: List<Row> get() = reading + changing

    /** [params] as the JSON body of the keys of [names] that the call gave and that are not null. */
    private fun bodyOf(params: JsonObject, vararg names: String): JsonObject = buildJsonObject {
        for (name in names) {
            val value = params[name]
            if (value != null && value !is JsonNull) put(name, value)
        }
    }

    /** The query of the keys of [names] that the call gave. */
    private fun query(params: JsonObject, vararg names: String): Map<String, String> = buildMap {
        for (name in names) text(params, name)?.let { put(name, it) }
    }

    /** The text a call gave for [name], null when it gave none. */
    private fun text(params: JsonObject, name: String): String? =
        (params[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** The group openid of [chat], null when it is no group. */
    private fun groupOf(chat: ChatAddress): String? =
        if (QqBotChats.kind(chat) == ChatKind.GROUP) QqBotChats.openid(chat) else null

    /** A call the platform is not asked about, with why. */
    private fun refused(said: String): QqBotAnswer = QqBotAnswer.Failed(QqBotFailure(null, said, null), null)
}
