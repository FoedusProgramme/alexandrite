package org.unitedsoftware.alexandrite.engine.sqliteandroid

import android.database.Cursor
import android.database.CursorWindow
import android.database.DatabaseErrorHandler
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteQuery
import android.os.Build
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteException
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteStatement
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteType
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The SQLite of Android, through `android.database.sqlite`, with one connection to each database it opens.
 *
 * On API levels 26 and 27, a row it reads holds at most the 2 MB of a cursor window.
 */
@Singleton
@Binds(SqliteDriver::class)
internal class AndroidSqliteDriver : SqliteDriver {
    override fun open(path: String): SqliteConnection {
        val writesAhead = writesAhead(path)
        val connection = sqlite { AndroidConnection(SQLiteDatabase.openDatabase(path, null, OPEN_FLAGS, KEEP_FILE)) }
        if (writesAhead) {
            try {
                connection.writeAhead()
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }
        return connection
    }
}

/** A database that Android opened outside its own WAL mode, on one connection. */
private class AndroidConnection(private val database: SQLiteDatabase) : SqliteConnection {
    override fun prepare(sql: String): SqliteStatement = sqlite { AndroidStatement(database, sql) }

    override fun beginImmediate() = sqlite {
        if (database.inTransaction()) throw SqliteException("cannot start a transaction within a transaction")
        database.beginTransactionNonExclusive()
    }

    override fun commit() = sqlite {
        if (!database.inTransaction()) throw SqliteException("cannot commit - no transaction is active")
        database.setTransactionSuccessful()
        database.endTransaction()
    }

    override fun rollback() = sqlite {
        if (!database.inTransaction()) throw SqliteException("cannot rollback - no transaction is active")
        database.endTransaction()
    }

    override fun lastInsertRowId(): Long = number("SELECT last_insert_rowid()")

    override fun changes(): Long = number("SELECT changes()")

    override fun close() = sqlite {
        if (database.inTransaction()) database.endTransaction()
        database.close()
    }

    fun writeAhead() {
        sqlite { DatabaseUtils.stringForQuery(database, "PRAGMA journal_mode = WAL", null) }
    }

    private fun number(sql: String): Long = sqlite { DatabaseUtils.longForQuery(database, sql, null) }
}

/** A statement, run by a cursor whose first move runs it, and which [reset] replaces. */
private class AndroidStatement(private val database: SQLiteDatabase, private val sql: String) : SqliteStatement {
    private val bindings = HashMap<Int, Any?>()
    private val factory = SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
        bindings.forEach { (index, value) -> query.bind(index, value) }
        this.query = query
        SQLiteCursor(driver, editTable, query)
    }
    private lateinit var query: SQLiteQuery
    private var cursor: Cursor = database.rawQueryWithFactory(factory, sql, null, null)
    private val columns: Array<String> = cursor.columnNames
    private var started = false

    override fun bindLong(index: Int, value: Long) = bind(index, value)

    override fun bindDouble(index: Int, value: Double) = bind(index, value)

    override fun bindText(index: Int, value: String) = bind(index, value)

    override fun bindBlob(index: Int, value: ByteArray) = bind(index, value)

    override fun bindNull(index: Int) = bind(index, null)

    override fun step(): Boolean = sqlite {
        started = true
        moveToNext()
    }

    override fun columnCount(): Int = columns.size

    override fun columnName(index: Int): String = sqlite { columns[index] }

    override fun columnType(index: Int): SqliteType = when (sqlite { cursor.getType(index) }) {
        Cursor.FIELD_TYPE_INTEGER -> SqliteType.INTEGER
        Cursor.FIELD_TYPE_FLOAT -> SqliteType.FLOAT
        Cursor.FIELD_TYPE_BLOB -> SqliteType.BLOB
        Cursor.FIELD_TYPE_NULL -> SqliteType.NULL
        else -> SqliteType.TEXT
    }

    override fun getLong(index: Int): Long = sqlite { cursor.getLong(index) }

    override fun getDouble(index: Int): Double = sqlite { cursor.getDouble(index) }

    override fun getText(index: Int): String = when (columnType(index)) {
        SqliteType.NULL -> ""
        SqliteType.BLOB -> String(getBlob(index), Charsets.UTF_8)
        else -> sqlite { cursor.getString(index) }
    }

    override fun getBlob(index: Int): ByteArray = when (columnType(index)) {
        SqliteType.NULL -> ByteArray(0)
        SqliteType.INTEGER, SqliteType.FLOAT -> getText(index).toByteArray(Charsets.UTF_8)
        else -> sqlite { cursor.getBlob(index) }
    }

    override fun isNull(index: Int): Boolean = sqlite { cursor.isNull(index) }

    override fun reset() = sqlite {
        cursor.close()
        cursor = database.rawQueryWithFactory(factory, sql, null, null)
        started = false
    }

    override fun close() = sqlite { cursor.close() }

    private fun bind(index: Int, value: Any?) = sqlite {
        if (started) throw SqliteException("bind on a statement that runs: reset it first")
        query.bind(index, value)
        bindings[index] = value
    }

    /** Moves the cursor on, giving it larger windows on API level 28 and up while its row does not fit. */
    private fun moveToNext(): Boolean {
        var size = DEFAULT_WINDOW_BYTES
        while (true) {
            try {
                return cursor.moveToNext()
            } catch (e: SQLiteException) {
                val larger = size * WINDOW_GROWTH
                if (!e.isRowTooBig() || Build.VERSION.SDK_INT < WINDOW_SIZE_API || larger > MAX_WINDOW_BYTES) throw e
                (cursor as SQLiteCursor).setWindow(sizedWindow.newInstance(WINDOW_NAME, larger))
                size = larger
            }
        }
    }
}

private fun SQLiteQuery.bind(index: Int, value: Any?) {
    when (value) {
        null -> bindNull(index)
        is Long -> bindLong(index, value)
        is Double -> bindDouble(index, value)
        is String -> bindString(index, value)
        is ByteArray -> bindBlob(index, value)
    }
}

private fun SQLiteException.isRowTooBig(): Boolean =
    javaClass.name == "android.database.sqlite.SQLiteBlobTooBigException"

/** Whether the database file at [path] is in WAL mode. */
private fun writesAhead(path: String): Boolean {
    val header = ByteArray(HEADER_BYTES)
    try {
        RandomAccessFile(path, "r").use { it.readFully(header) }
    } catch (e: IOException) {
        return false
    }
    return String(header, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC && header[WRITE_VERSION] == WAL_VERSION
}

/** [block], with the runtime exceptions that Android throws as [SqliteException]s. */
private inline fun <T> sqlite(block: () -> T): T = try {
    block()
} catch (e: SqliteException) {
    throw e
} catch (e: RuntimeException) {
    throw SqliteException(e.message ?: "$e", e)
}

/** Leaves a database file that SQLite finds corrupt as it is. */
private val KEEP_FILE = DatabaseErrorHandler {}

/** `SQLiteDatabase.DISABLE_COMPATIBILITY_WAL`, which keeps the journal mode of API level 28 and up out of WAL. */
private const val DISABLE_COMPATIBILITY_WAL = 0x40000000

private const val OPEN_FLAGS = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or
    SQLiteDatabase.NO_LOCALIZED_COLLATORS or DISABLE_COMPATIBILITY_WAL

private const val MAGIC = "SQLite format 3"

private const val HEADER_BYTES = 100

private const val WRITE_VERSION = 18

private const val WAL_VERSION: Byte = 2

private const val WINDOW_NAME = "alexandrite"

private const val DEFAULT_WINDOW_BYTES = 2L * 1024 * 1024

private const val WINDOW_GROWTH = 4

private const val MAX_WINDOW_BYTES = 512L * 1024 * 1024

private const val WINDOW_SIZE_API = 28

/** `CursorWindow(String, long)` of API level 28. */
private val sizedWindow by lazy {
    CursorWindow::class.java.getConstructor(String::class.java, Long::class.javaPrimitiveType)
}
