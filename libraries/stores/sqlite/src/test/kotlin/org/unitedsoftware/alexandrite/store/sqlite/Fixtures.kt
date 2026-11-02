package org.unitedsoftware.alexandrite.store.sqlite

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.engine.sqlitebundled.AlexandriteEngineSqliteBundledIndex
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.MediaPart
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.boundBy
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

internal val chat: ChatAddress = testChat()
internal val main: AgentChatKey = AgentChatKey(AgentId.MAIN, chat)
internal val guest: ChatUser = testUser()
internal val png: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

internal class TestFiles(override val dataDir: Path) : PluginFiles {
    override val cacheDir: Path get() = dataDir.resolve("cache")
}

internal class MutableClock(var instant: Instant = TEST_TIME, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant += duration
    }
}

/** The index of the driver engine the suite runs over. */
internal val driverIndex: PluginIndex = when (
    val name = System.getProperty(
        "alexandrite.store.sqlite.driver",
        "xerial",
    )
) {
    "xerial" -> AlexandriteEngineSqliteXerialIndex()
    "bundled" -> AlexandriteEngineSqliteBundledIndex()
    else -> error("No SQLite driver $name.")
}

internal val engineDriver: SqliteDriver = boundBy(driverIndex)

/** [engineDriver], failing the SQL that SQLite 3.18 cannot run. */
internal val driver: SqliteDriver = FloorDriver(engineDriver)

internal class TestStore(directory: Path, val clock: MutableClock) {
    val database = StoreDatabase(TestFiles(directory), clock, driver)
    val conversations = SqliteConversationStore(database)
    val media = SqliteMediaStore(database, TestFiles(directory))
    val transcripts = SqliteTranscriptStore(database, media)
    val chatStates = SqliteChatStateStore(database)
    val records = SqliteRecordStore(database)
    private val turns = AtomicInteger()

    suspend fun start() {
        database.onStart()
        media.onStart()
    }

    suspend fun turn(conversation: ConversationInfo, kind: TurnKind = TurnKind.MESSAGE): TurnInfo {
        val id = TurnId("turn-${turns.incrementAndGet()}")
        val turn = TurnInfo.builder(id, conversation.key.chat, conversation.id, kind)
            .agent(conversation.key.agent)
            .actor(guest.takeIf { kind == TurnKind.MESSAGE })
            .build()
        conversations.startTurn(turn)
        return turn
    }

    /** Stores [entries] in a new turn of the current conversation of [key]. */
    suspend fun append(vararg entries: TranscriptEntry, key: AgentChatKey = main): List<TranscriptEntry> =
        transcripts.append(turn(conversations.current(key)).id, entries.toList())

    suspend fun rows(sql: String, vararg arguments: Any?): List<List<Any?>> = database.transaction {
        query(sql, *arguments) { (1..columnCount).map { getObject(it) } }
    }
}

internal fun <T> withStore(directory: Path, clock: MutableClock = MutableClock(), block: suspend TestStore.() -> T): T =
    runBlocking {
        val store = TestStore(directory, clock)
        try {
            store.start()
            store.block()
        } finally {
            store.database.onDestroy()
        }
    }

internal fun connect(file: Path): SqliteConnection = driver.open(file.toString())

/** The first column of each row of [sql], as text. */
internal fun SqliteConnection.texts(sql: String): List<String> =
    prepare(sql).use { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }

internal fun SqliteConnection.userVersion(): Int = texts("PRAGMA user_version").single().toInt()

internal fun SqliteConnection.tables(): Set<String> =
    texts("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").toSet()

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal fun message(text: String, id: String = "m-$text", chat: ChatAddress = main.chat): UserEntry = UserEntry(
    null,
    listOf(ContextPart("message-context", "Sender: Guest"), TextPart(text)),
    UserOrigin.FromChat(guest, ChannelMessageRef(chat, id), TEST_TIME, null, null),
)

internal fun mediaPart(id: StoredMedia, kind: MediaKind = MediaKind.IMAGE): MediaPart =
    MediaPart(kind, "image/png", id, "photo.png", 640, 480)
