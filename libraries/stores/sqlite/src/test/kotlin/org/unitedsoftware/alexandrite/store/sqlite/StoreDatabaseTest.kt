package org.unitedsoftware.alexandrite.store.sqlite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.store.ConversationTaint
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptCodec
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreDatabaseTest {
    @TempDir
    lateinit var directory: Path

    private val file: Path get() = directory.resolve(StoreDatabase.FILE_NAME)

    private val runs = mutableListOf<String>()

    private val steps = listOf(
        Migration("a") {
            runs += "a"
            execute("CREATE TABLE a (x)")
        },
        Migration("b") {
            runs += "b"
            execute("CREATE TABLE b (x)")
        },
    )

    private fun database(): StoreDatabase = StoreDatabase(TestFiles(directory), MutableClock(), driver)

    // Migrations.

    @Test
    fun `a new store gets the newest schema`() {
        withStore(directory) {}

        val schema = connect(file).use { it.sql() }

        assertEquals(golden("schema.sql"), schema)
    }

    @Test
    fun `a store of version 1 gets the index of the turns that never ended`() {
        storeWithTurn(version = 1)

        val unended = withStore(directory) { conversations.unendedTurns().map { it.id } }

        assertEquals(listOf(TurnId("t1")), unended)
        connect(file).use {
            assertEquals(STORE_MIGRATIONS.size, it.userVersion())
            assertContains(it.indexes("turns"), "turns_unended")
        }
    }

    @Test
    fun `a store of version 2 records each turn's chat as the chat of its key`() {
        storeWithTurn(version = 2)

        val record = withStore(directory) { conversations.turn(TurnId("t1")) }

        assertEquals(main, record?.key)
        assertEquals(chat, record?.chat)
        connect(file).use { assertEquals(STORE_MIGRATIONS.size, it.userVersion()) }
    }

    @Test
    fun `a store of version 3 keeps its data and gets the plugins' records`() {
        storeWithTurn(version = 3)

        val (conversation, record) = withStore(directory) {
            conversations.conversation(ConversationId("c1"))?.key to
                records.put("notes", "grants", "k", "1", guest.address)
        }

        assertEquals(main, conversation)
        assertEquals(guest.address, record.createdBy)
        connect(file).use {
            assertEquals(STORE_MIGRATIONS.size, it.userVersion())
            assertContains(it.tables(), "records")
        }
    }

    @Test
    fun `a store of version 4 keeps its conversations, which start untainted and take a mark`() {
        storeWithTurn(version = 4)
        val at = Instant.parse("2026-01-02T03:04:05.678Z")

        val (before, marked, after) = withStore(directory) {
            val conversation = ConversationId("c1")
            Triple(
                conversations.taint(conversation),
                conversations.markTainted(conversation, "a forwarded message", at),
                conversations.taint(conversation),
            )
        }

        assertEquals(null, before)
        assertEquals(true, marked)
        assertEquals(ConversationTaint(at, "a forwarded message"), after)
        connect(file).use { assertEquals(STORE_MIGRATIONS.size, it.userVersion()) }
    }

    @Test
    fun `a store of version 5 gets its readable entries' text indexed`() {
        storeWithTurn(version = 5)
        val now = MutableClock().instant
        val bodies = listOf(
            TranscriptCodec.FORMAT_VERSION to TranscriptCodec.encode(message("Old rocket notes")),
            TranscriptCodec.FORMAT_VERSION to TranscriptCodec.encode(NoticeEntry(null, "rocket", NoticeKind.FAILED)),
            TranscriptCodec.FORMAT_VERSION + 1 to TranscriptCodec.encode(message("rocket of the future")),
            TranscriptCodec.FORMAT_VERSION to TranscriptCodec.encode(message("The rocket is back")),
        )
        openStore(driver, file, STORE_MIGRATIONS.take(5), now).use { connection ->
            connection.inTransaction(now) {
                for ((format, body) in bodies) {
                    execute(
                        "INSERT INTO entries (conversation, turn, type, created_at, format, body) " +
                            "VALUES ('c1', 't1', 'user', 0, ?, ?)",
                        format,
                        body,
                    )
                }
            }
        }

        val found = withStore(directory) {
            transcripts.search("rocket", setOf(main), 10).map { it.entry.record?.id?.value to it.snippet }
        }

        assertEquals(setOf(1L to "Old rocket notes", 4L to "The rocket is back"), found.toSet())
        connect(file).use {
            assertEquals(7, STORE_MIGRATIONS.size)
            assertEquals(STORE_MIGRATIONS.size, it.userVersion())
        }
    }

    @Test
    fun `a store of version 6 trades its FTS5 index for one on FTS4`() {
        storeWithFts5Index(readable = true)

        assertFts4Index()
    }

    @Test
    fun `a store of version 6 drops an FTS5 index that this SQLite cannot read`() {
        storeWithFts5Index(readable = false)

        assertFts4Index()
    }

    @Test
    fun `the turns that never ended are found through their index`() {
        val plan = withStore(directory) { rows("EXPLAIN QUERY PLAN $UNENDED_TURNS").map { it.last() as String } }

        assertTrue(plan.any { "USING INDEX turns_unended" in it }, "$plan")
        assertTrue(plan.none { "TEMP B-TREE" in it }, "$plan")
    }

    @Test
    fun `opening a store again runs no step`() {
        openStore(driver, file, steps, MutableClock().instant).close()
        val schema = connect(file).use { it.schema() }

        openStore(driver, file, steps, MutableClock().instant).close()

        assertEquals(listOf("a", "b"), runs)
        assertEquals(schema, connect(file).use { it.schema() })
        assertEquals(2, connect(file).use { it.userVersion() })
    }

    @Test
    fun `a store goes on from the version it has`() {
        openStore(driver, file, steps.take(1), MutableClock().instant).close()
        openStore(driver, file, steps, MutableClock().instant).close()

        assertEquals(listOf("a", "b"), runs)
        assertEquals(setOf("a", "b"), connect(file).use { it.tables() })
    }

    @Test
    fun `a step that fails leaves the store at the version before it`() {
        val failing = steps.take(1) + Migration("c") {
            execute("CREATE TABLE c (x)")
            execute("INSERT INTO missing VALUES (1)")
        }

        val error = assertFailsWith<IllegalStateException> { openStore(driver, file, failing, MutableClock().instant) }

        assertContains(error.message!!, "to schema version 2 (c)")
        connect(file).use {
            assertEquals(1, it.userVersion())
            assertEquals(setOf("a"), it.tables())
        }
    }

    @Test
    fun `a store of a newer version is refused and left unchanged`() {
        connect(file).use {
            it.runSql("CREATE TABLE future (x)")
            it.runSql("PRAGMA user_version = ${STORE_MIGRATIONS.size + 1}")
        }
        val before = Files.readAllBytes(file)

        val error = assertFailsWith<IllegalStateException> { runBlocking { database().onStart() } }

        assertContains(error.message!!, "has schema version ${STORE_MIGRATIONS.size + 1}, which is newer than version")
        assertContentEquals(before, Files.readAllBytes(file))
        assertFalse(Files.exists(directory.resolve("${StoreDatabase.FILE_NAME}-wal")))
    }

    @Test
    fun `a file that is no database is refused and left unchanged`() {
        Files.write(file, ByteArray(4096) { it.toByte() })

        val error = assertFailsWith<IllegalStateException> { runBlocking { database().onStart() } }

        assertContains(error.message!!, "Cannot open the store $file")
        assertContentEquals(ByteArray(4096) { it.toByte() }, Files.readAllBytes(file))
    }

    // The connection.

    @Test
    fun `the connection writes ahead, syncs normally and enforces foreign keys`() {
        val pragmas = withStore(directory) {
            listOf("journal_mode", "synchronous", "foreign_keys", "busy_timeout").map { rows("PRAGMA $it").single() }
        }

        assertEquals(listOf(listOf("wal"), listOf(1L), listOf(1L), listOf(5000L)), pragmas)
    }

    @Test
    fun `a transaction that throws leaves nothing behind`() {
        withStore(directory) {
            assertFailsWith<IllegalStateException> {
                database.transaction {
                    execute("INSERT INTO chats (address, created_at) VALUES ('x', 0)")
                    error("stop")
                }
            }

            assertEquals(emptyList(), rows("SELECT * FROM chats"))
        }
    }

    @Test
    fun `actions after a commit run only once it committed`() {
        val ran = mutableListOf<String>()

        withStore(directory) {
            database.transaction { afterCommit { ran += "committed" } }
            runCatching {
                database.transaction {
                    afterCommit { ran += "rolled back" }
                    error("stop")
                }
            }
        }

        assertEquals(listOf("committed"), ran)
    }

    @Test
    fun `destroying the store waits for the block in flight`() {
        val database = database()
        runBlocking { database.onStart() }
        val entered = CountDownLatch(1)
        var committed = false

        val worker = thread {
            runBlocking {
                database.transaction {
                    entered.countDown()
                    Thread.sleep(200)
                    execute("INSERT INTO chats (address, created_at) VALUES ('x', 0)")
                }
                committed = true
            }
        }
        entered.await()
        database.onDestroy()
        worker.join()

        assertTrue(committed)
        assertEquals(setOf("x"), withStore(directory) { rows("SELECT address FROM chats").map { it.single() }.toSet() })
    }

    @Test
    fun `a block that began returns to a caller cancelled meanwhile, and one that did not begin changes nothing`() {
        withStore(directory) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            var returned: String? = null
            coroutineScope {
                val caller = launch(Dispatchers.Default) {
                    returned = database.transaction {
                        entered.complete(Unit)
                        release.await()
                        execute("INSERT INTO chats (address, created_at) VALUES ('began', 0)")
                        "committed"
                    }
                }
                entered.await()
                caller.cancel()
                release.countDown()
                caller.join()
                launch(Dispatchers.Default) {
                    coroutineContext.job.cancel()
                    database.transaction { execute("INSERT INTO chats (address, created_at) VALUES ('never', 0)") }
                }.join()
            }

            assertEquals("committed", returned)
            assertEquals(listOf(listOf("began")), rows("SELECT address FROM chats"))
        }
    }

    /** A store of schema 6 that holds an entry in its FTS5 index, made with a module unknown to SQLite unless [readable]. */
    private fun storeWithFts5Index(readable: Boolean) {
        storeWithTurn(version = 6)
        val now = MutableClock().instant
        openStore(engineDriver, file, STORE_MIGRATIONS.take(6), now).use { connection ->
            connection.inTransaction(now) {
                execute(
                    "INSERT INTO entries (conversation, turn, type, created_at, format, body) " +
                        "VALUES ('c1', 't1', 'user', 0, ?, ?)",
                    TranscriptCodec.FORMAT_VERSION,
                    TranscriptCodec.encode(message("Old rocket notes")),
                )
                if (readable) {
                    execute("CREATE VIRTUAL TABLE entry_text USING fts5(text, tokenize = 'trigram case_sensitive 0')")
                    execute("INSERT INTO entry_text (rowid, text) VALUES (1, 'Old rocket notes')")
                } else {
                    for (shadow in listOf("data", "idx", "content", "docsize", "config")) {
                        execute("CREATE TABLE entry_text_$shadow (x)")
                    }
                    execute("PRAGMA writable_schema = ON")
                    execute(
                        "INSERT INTO sqlite_master (type, name, tbl_name, rootpage, sql) VALUES ('table', " +
                            "'entry_text', 'entry_text', 0, 'CREATE VIRTUAL TABLE entry_text USING nosuchmodule(text)')",
                    )
                    execute("PRAGMA writable_schema = OFF")
                }
            }
        }
    }

    private fun assertFts4Index() {
        val found = withStore(directory) { transcripts.search("rocket", setOf(main), 10).map { it.snippet } }

        assertEquals(listOf("Old rocket notes"), found)
        connect(file).use {
            assertEquals(STORE_MIGRATIONS.size, it.userVersion())
            assertEquals(emptyList(), it.tables().filter { table -> table.startsWith("entry_text") })
            assertEquals(listOf("ok"), it.texts("PRAGMA integrity_check"))
        }
    }

    /** A store of schema [version] that holds the unended turn `t1` of [main]. */
    private fun storeWithTurn(version: Int) {
        val now = MutableClock().instant
        openStore(driver, file, STORE_MIGRATIONS.take(version), now).use { connection ->
            connection.inTransaction(now) {
                val chatId = chatId(chat)
                execute(
                    "INSERT INTO conversations (id, kind, agent, chat_id, state, created_at, depth) " +
                        "VALUES ('c1', 'user_lane', 'main', ?, 'active', 0, 0)",
                    chatId,
                )
                execute(
                    "INSERT INTO turns (id, conversation, agent, chat_id, kind, depth, started_at) " +
                        "VALUES ('t1', 'c1', 'main', ?, 'message', 0, 0)",
                    chatId,
                )
            }
        }
    }

    private fun SqliteConnection.indexes(table: String): List<String> =
        texts("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'")

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name")) { "No golden file $name." }.readText()

    /** The store's version and objects as SQL. */
    private fun SqliteConnection.sql(): String {
        val objects = texts(
            "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                "ORDER BY tbl_name, type = 'index', name",
        )
        val statements = objects.map { sql ->
            sql.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { index, line ->
                if (index == 0 || line.startsWith(")")) line else "    $line"
            }.joinToString("\n")
        }
        return (listOf("PRAGMA user_version = ${userVersion()}") + statements).joinToString(";\n\n", postfix = ";\n")
    }

    private fun SqliteConnection.schema(): List<String> = texts("SELECT sql FROM sqlite_master ORDER BY name")
}
