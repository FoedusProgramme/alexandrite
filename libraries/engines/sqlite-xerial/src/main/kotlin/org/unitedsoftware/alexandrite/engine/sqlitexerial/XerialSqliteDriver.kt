package org.unitedsoftware.alexandrite.engine.sqlitexerial

import org.sqlite.JDBC
import org.sqlite.core.Codes
import org.sqlite.core.CoreStatement
import org.sqlite.core.SafeStmtPtr
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteException
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteStatement
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteType
import java.nio.file.Paths
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Properties

/** SQLite through xerial's JDBC driver, with its default options, in autocommit mode. */
@Singleton
@Binds(SqliteDriver::class)
internal class XerialSqliteDriver : SqliteDriver {
    override fun open(path: String): SqliteConnection =
        jdbc { JdbcConnection(JDBC.createConnection("${JDBC.PREFIX}${Paths.get(path).toUri()}", Properties())) }
}

private class JdbcConnection(private val connection: Connection) : SqliteConnection {
    override fun prepare(sql: String): SqliteStatement = jdbc { JdbcStatement(connection.prepareStatement(sql)) }

    override fun beginImmediate() = run("BEGIN IMMEDIATE")

    override fun commit() = run("COMMIT")

    override fun rollback() = run("ROLLBACK")

    override fun lastInsertRowId(): Long = number("SELECT last_insert_rowid()")

    override fun changes(): Long = number("SELECT changes()")

    override fun close() = jdbc { connection.close() }

    private fun run(sql: String) {
        jdbc { connection.createStatement().use { it.execute(sql) } }
    }

    private fun number(sql: String): Long = jdbc {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use {
                it.next()
                it.getLong(1)
            }
        }
    }
}

/** A prepared statement, which its first [step] executes. */
private class JdbcStatement(private val statement: PreparedStatement) : SqliteStatement {
    /** The SQLite statement under [statement]. */
    private val native: SafeStmtPtr = (statement as CoreStatement).pointer

    private var executed = false

    /** The rows of the executed statement, null when it yields none. */
    private var rows: ResultSet? = null

    override fun bindLong(index: Int, value: Long) = jdbc { statement.setLong(index, value) }

    override fun bindDouble(index: Int, value: Double) = jdbc { statement.setDouble(index, value) }

    override fun bindText(index: Int, value: String) = jdbc { statement.setString(index, value) }

    override fun bindBlob(index: Int, value: ByteArray) = jdbc { statement.setBytes(index, value) }

    override fun bindNull(index: Int) = jdbc { statement.setNull(index, Types.NULL) }

    override fun step(): Boolean = jdbc {
        if (!executed) {
            executed = true
            if (statement.execute()) rows = statement.resultSet
        }
        rows?.next() ?: false
    }

    override fun columnCount(): Int =
        jdbc { native.safeRunInt<SQLException> { db, pointer -> db.column_count(pointer) } }

    override fun columnName(index: Int): String {
        checkColumn(index)
        return jdbc { native.safeRun<String, SQLException> { db, pointer -> db.column_name(pointer, index) } }
    }

    override fun columnType(index: Int): SqliteType {
        row()
        checkColumn(index)
        return when (jdbc { native.safeRunInt<SQLException> { db, pointer -> db.column_type(pointer, index) } }) {
            Codes.SQLITE_INTEGER -> SqliteType.INTEGER
            Codes.SQLITE_FLOAT -> SqliteType.FLOAT
            Codes.SQLITE_BLOB -> SqliteType.BLOB
            Codes.SQLITE_NULL -> SqliteType.NULL
            else -> SqliteType.TEXT
        }
    }

    override fun getLong(index: Int): Long = jdbc { row().getLong(index + 1) }

    override fun getDouble(index: Int): Double = jdbc { row().getDouble(index + 1) }

    override fun getText(index: Int): String = jdbc { row().getString(index + 1).orEmpty() }

    override fun getBlob(index: Int): ByteArray = jdbc { row().getBytes(index + 1) ?: ByteArray(0) }

    override fun isNull(index: Int): Boolean = columnType(index) == SqliteType.NULL

    override fun reset() = jdbc {
        rows?.close()
        rows = null
        executed = false
    }

    override fun close() = jdbc { statement.close() }

    private fun row(): ResultSet = rows ?: throw SqliteException("The statement is on no row.")

    private fun checkColumn(index: Int) {
        if (index !in 0 until columnCount()) throw SqliteException("The statement has no column $index.")
    }
}

private inline fun <T> jdbc(block: () -> T): T = try {
    block()
} catch (e: SQLException) {
    throw SqliteException(e.message ?: "$e", e)
}
