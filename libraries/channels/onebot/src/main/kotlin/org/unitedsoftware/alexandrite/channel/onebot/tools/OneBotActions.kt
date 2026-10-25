package org.unitedsoftware.alexandrite.channel.onebot.tools

import org.unitedsoftware.alexandrite.channel.onebot.protocol.api.OneBotRegistry
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

/**
 * The actions of the platform this bot may be asked for, each declared once.
 *
 * One row says what an action is for, which arguments it takes, what they mean and how each is written. A tool reads
 * the row, so an action is added by adding a row, and the ones that must never be called are left out of the table.
 *
 * An action the standard declares is checked against the registry, so a row naming one that does not exist, or one
 * the standard hides, fails while the table is read, before any call reaches the platform. An action the standard
 * does not declare at all is a row that says so, which is how an implementation's own actions are reached.
 *
 * The names of the arguments are the ones the platform takes, which are the keys its own requests are written with.
 */
internal object OneBotActions {
    /** How the standard writes an argument, which a model writes as text either way. */
    internal enum class Kind {
        /** A name, a title, a flag it was given, a reason: the text as it was written. */
        TEXT,

        /** A message, a group, a member, a length: the number the text names. */
        NUMBER,

        /** Whether to do something: yes or no. */
        FLAG,

        /**
         * A file of the agent's own workspace, named by its path.
         *
         * The platform is usually on another machine, where a path of this one means nothing, so the tool reads the
         * file and hands it over as the bytes the platform takes. What may be read is not decided here: the floor
         * that decides what the tools for files may read decides it, where a runtime has one.
         */
        FILE,
    }

    /**
     * What an action is, and what calling it takes.
     *
     * [parameters] names the arguments the platform takes, in the order they are shown. An argument that must be given
     * says so, and the one an action about a message takes may be left out, in which case the message of this turn is
     * meant: a prompt does not name the id of the message it answers, so a call about it must not have to.
     */
    internal class Row(
        val name: String,
        val said: String,
        val parameters: List<Parameter>,
        /** Whether the standard declares this action at all. An implementation may have actions it does not. */
        val extension: Boolean = false,
    ) {
        /** Whether this action changes something. */
        val changes: Boolean get() = this in changing

        /** Whether the id of the message of this turn stands in for an argument that was left out. */
        val takesAMessage: Boolean get() = parameters.any { it.name == MESSAGE_ID && it.optional }
    }

    /** One argument of an action. */
    internal class Parameter(
        val name: String,
        val description: String,
        val kind: Kind = Kind.TEXT,
        val required: Boolean = true,
    ) {
        /** Whether this argument may be left out. */
        val optional: Boolean get() = !required
    }

    /** The argument that names the message an action is about. */
    const val MESSAGE_ID: String = "message_id"

    private val reading: List<Row> = listOf(
        Row(
            name = "get_msg",
            said = "The message was read.",
            parameters = listOf(message()),
        ),
        Row(
            name = "get_forward_msg",
            said = "The forwarded messages were read.",
            parameters = listOf(Parameter("id", "The id of the forwarded message to read.")),
        ),
        Row(
            name = "get_stranger_info",
            said = "The account was read.",
            parameters = listOf(
                Parameter("user_id", "The account to read.", Kind.NUMBER),
                Parameter("no_cache", "Whether to read it afresh rather than from the cache.", Kind.FLAG, false),
            ),
        ),
        Row(
            name = "get_group_info",
            said = "The group was read.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("no_cache", "Whether to read it afresh rather than from the cache.", Kind.FLAG, false),
            ),
        ),
        Row(
            name = "get_group_member_info",
            said = "The member was read.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("user_id", "The member to read.", Kind.NUMBER),
                Parameter("no_cache", "Whether to read them afresh rather than from the cache.", Kind.FLAG, false),
            ),
        ),
        Row(
            name = "get_group_member_list",
            said = "The members were read.",
            parameters = listOf(Parameter("group_id", "The group.", Kind.NUMBER)),
        ),
        Row(
            name = "get_group_honor_info",
            said = "The honors were read.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("type", "Which honors to read, such as all, talkative or performer."),
            ),
        ),
        Row(
            name = "get_file",
            said = "The file was read.",
            parameters = listOf(
                Parameter("file", "How the implementation knows the file."),
                Parameter(
                    "type",
                    "Which kind of reference to answer with, where the implementation asks.",
                    required = false,
                ),
            ),
            extension = true,
        ),
        Row(
            name = "get_group_file_url",
            said = "Where the group file can be read was read.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("file_id", "The id the group file is known by."),
                Parameter("busid", "Which part of the group's files the file is in.", Kind.NUMBER),
            ),
            extension = true,
        ),
    )

    private val changing: List<Row> = listOf(
        Row(
            name = "delete_group_file",
            said = "The group file was deleted.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("file_id", "The id the group file is known by, which listing its files gives."),
                Parameter(
                    "busid",
                    "Which part of the group's files the file is in, which listing its files gives.",
                    Kind.NUMBER,
                ),
            ),
            extension = true,
        ),
        Row(
            name = "delete_msg",
            said = "The message was taken back.",
            parameters = listOf(message("The message to take back.")),
        ),
        Row(
            name = "set_msg_emoji_like",
            said = "The emoji response was added.",
            parameters = listOf(
                message("The message to respond to."),
                Parameter("emoji_id", "The number of the emoji, such as 128077 for a thumbs up.", Kind.NUMBER),
            ),
            extension = true,
        ),
        Row(
            name = "send_like",
            said = "The account was given a like.",
            parameters = listOf(
                Parameter("user_id", "The account to like.", Kind.NUMBER),
                Parameter("times", "How many likes to give.", Kind.NUMBER, false),
            ),
        ),
        Row(
            name = "set_friend_add_request",
            said = "The friend request was answered.",
            parameters = listOf(
                Parameter("flag", "The flag the request reported."),
                Parameter("approve", "Whether to accept the request.", Kind.FLAG),
                Parameter("remark", "The remark to keep with a friend the call accepts.", required = false),
            ),
        ),
        Row(
            name = "set_group_add_request",
            said = "The request was answered.",
            parameters = listOf(
                Parameter("flag", "The flag the request reported."),
                Parameter("sub_type", "add when somebody asks to join, invite when the bot is invited."),
                Parameter("approve", "Whether to accept the request.", Kind.FLAG),
                Parameter("reason", "The reason to give for a request the call refuses.", required = false),
            ),
        ),
        Row(
            name = "set_group_kick",
            said = "The member was removed from the group.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("user_id", "The member to remove.", Kind.NUMBER),
                Parameter("reject_add_request", "Whether to refuse their requests to join again.", Kind.FLAG, false),
            ),
        ),
        Row(
            name = "set_group_ban",
            said = "The mute was set.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("user_id", "The member to mute.", Kind.NUMBER),
                Parameter("duration", "How many seconds the mute lasts, 0 to lift it.", Kind.NUMBER),
            ),
        ),
        Row(
            name = "set_group_whole_ban",
            said = "The mute of the whole group was set.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("enable", "Whether every member is muted.", Kind.FLAG),
            ),
        ),
        Row(
            name = "set_group_admin",
            said = "Whether the member is an administrator was set.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("user_id", "The member.", Kind.NUMBER),
                Parameter("enable", "Whether they administer the group.", Kind.FLAG),
            ),
        ),
        Row(
            name = "set_group_card",
            said = "The name the member goes by in the group was set.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("user_id", "The member.", Kind.NUMBER),
                Parameter("card", "The name they go by in the group, empty to clear it."),
            ),
        ),
        Row(
            name = "set_group_name",
            said = "The name of the group was set.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("group_name", "The name to give the group."),
            ),
        ),
        Row(
            name = "set_group_leave",
            said = "The bot left the group.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("is_dismiss", "Whether to dismiss the group rather than only leave it.", Kind.FLAG, false),
            ),
        ),
        Row(
            name = "set_group_special_title",
            said = "The title was set.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("user_id", "The member.", Kind.NUMBER),
                Parameter("special_title", "The title to give them, empty to clear it."),
                Parameter("duration", "How many seconds the title lasts.", Kind.NUMBER, false),
            ),
        ),
        Row(
            name = "upload_group_file",
            said = "The file was uploaded to the group.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("file", "The path of the file to upload, in your workspace.", Kind.FILE),
                Parameter("name", "What to call the file in the group."),
                Parameter("folder", "Which folder of the group's files to put it in.", required = false),
            ),
            extension = true,
        ),
        Row(
            name = "upload_private_file",
            said = "The file was uploaded to the chat.",
            parameters = listOf(
                Parameter("user_id", "The account to send the file to.", Kind.NUMBER),
                Parameter("file", "The path of the file to upload, in your workspace.", Kind.FILE),
                Parameter("name", "What to call the file."),
            ),
            extension = true,
        ),
    )

    /** An action that is about the message of this turn when a call names none. */
    private fun message(what: String = "The message to read."): Parameter =
        Parameter(MESSAGE_ID, "$what Default: the message this turn is about.", Kind.NUMBER, required = false)

    /** What a group's files are, which an implementation that offers the extension reports. */
    private val fileReading: List<Row> = listOf(
        Row(
            name = "get_group_root_files",
            said = "The files of the group were read.",
            parameters = listOf(Parameter("group_id", "The group.", Kind.NUMBER)),
            extension = true,
        ),
        Row(
            name = "get_group_files_by_folder",
            said = "The files of that folder were read.",
            parameters = listOf(
                Parameter("group_id", "The group.", Kind.NUMBER),
                Parameter("folder_id", "The folder to read, as the group names it."),
            ),
            extension = true,
        ),
        Row(
            name = "get_group_file_system_info",
            said = "What the group's files hold was read.",
            parameters = listOf(Parameter("group_id", "The group.", Kind.NUMBER)),
            extension = true,
        ),
    )

    /** Everything this bot may be asked for, the reads first. */
    val rows: List<Row> = reading + fileReading + changing

    private val byName: Map<String, Row> = rows.associateBy(Row::name)

    init {
        rows.forEach { row ->
            if (!row.extension) {
                val declared = OneBotRegistry.action(row.name)
                require(declared != null) { "The platform declares no action '${row.name}'." }
                require(!declared.hidden) { "The action '${row.name}' is one the standard hides." }
            }
        }
        require(byName.size == rows.size) { "An action is declared twice." }
    }

    /** The row of [name], null when this bot may not be asked for it. */
    fun of(name: String): Row? = byName[name]
}
