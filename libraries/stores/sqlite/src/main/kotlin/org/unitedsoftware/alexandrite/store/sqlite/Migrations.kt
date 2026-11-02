package org.unitedsoftware.alexandrite.store.sqlite

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import java.nio.file.Path
import java.time.Instant

/** One step of the store's schema, run in a transaction of its own. */
internal class Migration(val description: String, val apply: Tx.() -> Unit)

/** The steps of the store's schema in order, the first n of them making version n. */
internal val STORE_MIGRATIONS: List<Migration> = listOf(
    Migration("chats, conversations, turns, entries, media and chat states") {
        SCHEMA_1.forEach { execute(it) }
    },
    Migration("an index of the turns that never ended") {
        execute("CREATE INDEX turns_unended ON turns (started_at) WHERE ended_at IS NULL")
    },
    Migration("the chat each turn came from") {
        execute("ALTER TABLE turns ADD COLUMN origin_chat_id INTEGER REFERENCES chats (id)")
        execute("UPDATE turns SET origin_chat_id = chat_id")
    },
    Migration("the plugins' records") {
        execute(
            """
            CREATE TABLE records (
                plugin TEXT NOT NULL,
                collection TEXT NOT NULL,
                record_key TEXT NOT NULL,
                value TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                created_by TEXT,
                updated_at INTEGER NOT NULL,
                updated_by TEXT,
                UNIQUE (plugin, collection, record_key)
            )
            """,
        )
    },
    Migration("the first untrusted content of each conversation") {
        execute("ALTER TABLE conversations ADD COLUMN tainted_at INTEGER")
        execute("ALTER TABLE conversations ADD COLUMN taint_reason TEXT")
    },
    Migration("a full-text index of the entries' text on FTS5, which version 7 replaces") {},
    Migration("a full-text index of the entries' text on FTS4") {
        dropFts5Index()
        execute("CREATE VIRTUAL TABLE entry_grams USING fts4(grams, tokenize = simple)")
        indexAllText()
    },
)

/** A connection through [driver] to the store at [file], whose schema [steps] brought to their newest version. */
internal fun openStore(driver: SqliteDriver, file: Path, steps: List<Migration>, now: Instant): SqliteConnection {
    val connection = try {
        driver.open(file.toString())
    } catch (e: Exception) {
        throw IllegalStateException("Cannot open the store $file: ${e.message}", e)
    }
    try {
        connection.runSql("PRAGMA busy_timeout = 5000")
        connection.runSql("PRAGMA foreign_keys = ON")
        val version = connection.userVersion(file)
        check(version <= steps.size) {
            "The store $file has schema version $version, which is newer than version ${steps.size} of this " +
                "Alexandrite. It was left unchanged."
        }
        connection.runSql("PRAGMA journal_mode = WAL")
        connection.runSql("PRAGMA synchronous = NORMAL")
        for (next in version + 1..steps.size) connection.migrate(file, next, steps[next - 1], now)
        if (version < steps.size) {
            logger.info("Store {} migrated from version {} to {}", file, version, steps.size)
        }
        return connection
    } catch (e: Throwable) {
        try {
            connection.close()
        } catch (close: Exception) {
            e.addSuppressed(close)
        }
        if (e is IllegalStateException) throw e
        throw IllegalStateException("Cannot open the store $file: ${e.message}", e)
    }
}

private fun SqliteConnection.userVersion(file: Path): Int = prepare("PRAGMA user_version").use { statement ->
    check(statement.step()) { "The store $file reports no schema version." }
    statement.getLong(0).toInt()
}

private fun SqliteConnection.migrate(file: Path, version: Int, step: Migration, now: Instant) {
    try {
        inTransaction(now) {
            step.apply(this)
            execute("PRAGMA user_version = $version")
        }
    } catch (e: Exception) {
        throw IllegalStateException(
            "Cannot bring the store $file to schema version $version (${step.description}): ${e.message}",
            e,
        )
    }
}

private val SCHEMA_1 = listOf(
    """
    CREATE TABLE chats (
        id INTEGER PRIMARY KEY,
        address TEXT NOT NULL UNIQUE,
        created_at INTEGER NOT NULL
    )
    """,
    """
    CREATE TABLE conversations (
        id TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        agent TEXT NOT NULL,
        chat_id INTEGER NOT NULL REFERENCES chats (id),
        state TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        sealed_at INTEGER,
        successor TEXT REFERENCES conversations (id),
        run_id TEXT,
        parent_turn TEXT REFERENCES turns (id),
        parent_conversation TEXT REFERENCES conversations (id),
        parent_call TEXT,
        root_turn TEXT REFERENCES turns (id),
        root_conversation TEXT REFERENCES conversations (id),
        depth INTEGER NOT NULL,
        forked_from TEXT REFERENCES conversations (id),
        forked_through_entry INTEGER
    )
    """,
    "CREATE INDEX conversations_chat ON conversations (chat_id, agent)",
    """
    CREATE TABLE current_conversations (
        agent TEXT NOT NULL,
        chat_id INTEGER NOT NULL REFERENCES chats (id),
        kind TEXT NOT NULL,
        conversation TEXT NOT NULL REFERENCES conversations (id),
        PRIMARY KEY (agent, chat_id, kind)
    ) WITHOUT ROWID
    """,
    """
    CREATE TABLE turns (
        id TEXT PRIMARY KEY,
        conversation TEXT NOT NULL REFERENCES conversations (id),
        agent TEXT NOT NULL,
        chat_id INTEGER NOT NULL REFERENCES chats (id),
        kind TEXT NOT NULL,
        actor TEXT,
        run_id TEXT,
        parent_turn TEXT REFERENCES turns (id),
        parent_conversation TEXT REFERENCES conversations (id),
        parent_call TEXT,
        root_turn TEXT REFERENCES turns (id),
        root_conversation TEXT REFERENCES conversations (id),
        depth INTEGER NOT NULL,
        started_at INTEGER NOT NULL,
        ended_at INTEGER,
        outcome TEXT
    )
    """,
    "CREATE INDEX turns_conversation ON turns (conversation)",
    """
    CREATE TABLE entries (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        conversation TEXT NOT NULL REFERENCES conversations (id),
        turn TEXT NOT NULL REFERENCES turns (id),
        type TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        format INTEGER NOT NULL,
        body TEXT NOT NULL,
        message_chat_id INTEGER REFERENCES chats (id),
        message_id TEXT
    )
    """,
    "CREATE INDEX entries_conversation ON entries (conversation, id)",
    "CREATE INDEX entries_turn ON entries (turn)",
    "CREATE INDEX entries_message ON entries (message_chat_id, message_id) WHERE message_id IS NOT NULL",
    """
    CREATE TABLE media (
        id TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        media_type TEXT NOT NULL,
        size INTEGER NOT NULL,
        sha256 TEXT NOT NULL,
        created_at INTEGER NOT NULL
    )
    """,
    "CREATE INDEX media_sha256 ON media (sha256)",
    """
    CREATE TABLE entry_media (
        entry_id INTEGER NOT NULL REFERENCES entries (id),
        media_id TEXT NOT NULL REFERENCES media (id),
        PRIMARY KEY (entry_id, media_id)
    ) WITHOUT ROWID
    """,
    "CREATE INDEX entry_media_media ON entry_media (media_id)",
    """
    CREATE TABLE chat_states (
        plugin TEXT NOT NULL,
        name TEXT NOT NULL,
        agent TEXT NOT NULL,
        chat_id INTEGER NOT NULL REFERENCES chats (id),
        value TEXT NOT NULL,
        PRIMARY KEY (plugin, name, agent, chat_id)
    ) WITHOUT ROWID
    """,
)

private val logger = LoggerFactory.getLogger(Migration::class.java)
