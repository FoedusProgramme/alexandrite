package org.unitedsoftware.alexandrite.engine.sqlitebundled

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteException
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteStatement
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteType

/** The SQLite that androidx bundles, with its native library for Android and for the desktop JVM. */
@Singleton
@Binds(SqliteDriver::class)
internal class BundledSqliteDriver : SqliteDriver {
    private val driver by lazy { BundledSQLiteDriver() }

    override fun open(path: String): SqliteConnection = sqlite { BundledConnection(driver.open(path)) }
}

private class BundledConnection(private val connection: SQLiteConnection) : SqliteConnection {
    override fun prepare(sql: String): SqliteStatement = sqlite { BundledStatement(connection.prepare(sql)) }

    override fun beginImmediate() = run("BEGIN IMMEDIATE")

    override fun commit() = run("COMMIT")

    override fun rollback() = run("ROLLBACK")

    override fun lastInsertRowId(): Long = number("SELECT last_insert_rowid()")

    override fun changes(): Long = number("SELECT changes()")

    override fun close() = sqlite { connection.close() }

    private fun run(sql: String) {
        sqlite { connection.prepare(sql).use { it.step() } }
    }

    private fun number(sql: String): Long = sqlite {
        connection.prepare(sql).use { statement ->
            statement.step()
            statement.getLong(0)
        }
    }
}

/** A statement, which stays at its end until a [reset] where SQLite would run it again. */
private class BundledStatement(private val statement: SQLiteStatement) : SqliteStatement {
    private var done = false

    override fun bindLong(index: Int, value: Long) = sqlite { statement.bindLong(index, value) }

    override fun bindDouble(index: Int, value: Double) = sqlite { statement.bindDouble(index, value) }

    override fun bindText(index: Int, value: String) = sqlite { statement.bindText(index, value) }

    override fun bindBlob(index: Int, value: ByteArray) = sqlite { statement.bindBlob(index, value) }

    override fun bindNull(index: Int) = sqlite { statement.bindNull(index) }

    override fun step(): Boolean = sqlite { !done && statement.step().also { done = !it } }

    override fun columnCount(): Int = sqlite { statement.getColumnCount() }

    override fun columnName(index: Int): String = sqlite { statement.getColumnName(index) }

    override fun columnType(index: Int): SqliteType = when (sqlite { statement.getColumnType(index) }) {
        SQLITE_DATA_INTEGER -> SqliteType.INTEGER
        SQLITE_DATA_FLOAT -> SqliteType.FLOAT
        SQLITE_DATA_BLOB -> SqliteType.BLOB
        SQLITE_DATA_NULL -> SqliteType.NULL
        else -> SqliteType.TEXT
    }

    override fun getLong(index: Int): Long = sqlite { statement.getLong(index) }

    override fun getDouble(index: Int): Double = sqlite { statement.getDouble(index) }

    override fun getText(index: Int): String = sqlite { if (statement.isNull(index)) "" else statement.getText(index) }

    override fun getBlob(index: Int): ByteArray =
        sqlite { if (statement.isNull(index)) ByteArray(0) else statement.getBlob(index) }

    override fun isNull(index: Int): Boolean = sqlite { statement.isNull(index) }

    override fun reset() = sqlite {
        statement.reset()
        done = false
    }

    override fun close() = sqlite { statement.close() }
}

/** [block], with the runtime exceptions that androidx throws as [SqliteException]s. */
private inline fun <T> sqlite(block: () -> T): T = try {
    block()
} catch (e: SqliteException) {
    throw e
} catch (e: RuntimeException) {
    throw SqliteException(e.message ?: "$e", e)
}
