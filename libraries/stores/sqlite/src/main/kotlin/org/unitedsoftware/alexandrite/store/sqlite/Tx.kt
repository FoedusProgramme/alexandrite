package org.unitedsoftware.alexandrite.store.sqlite

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteStatement
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteType
import java.time.Instant

/** A transaction of the store, valid only inside its block. */
internal class Tx(
    private val connection: SqliteConnection,
    /** When the transaction began, to the millisecond. */
    val now: Instant,
) {
    private val committedActions = mutableListOf<() -> Unit>()

    /** Runs [sql] with [arguments] and returns how many rows it changed. */
    fun execute(sql: String, vararg arguments: Any?): Int {
        prepare(sql, arguments).use { it.runToEnd() }
        return connection.changes().toInt()
    }

    /** Runs the `INSERT` [sql] with [arguments] and returns the rowid of the row it added. */
    fun insert(sql: String, vararg arguments: Any?): Long {
        prepare(sql, arguments).use { it.runToEnd() }
        return connection.lastInsertRowId()
    }

    fun <T> query(sql: String, vararg arguments: Any?, row: Row.() -> T): List<T> =
        prepare(sql, arguments).use { statement ->
            val current = Row(statement)
            buildList { while (statement.step()) add(current.row()) }
        }

    /** The first row [sql] yields, null when it yields none. */
    fun <T> queryOne(sql: String, vararg arguments: Any?, row: Row.() -> T): T? =
        prepare(sql, arguments).use { statement -> if (statement.step()) Row(statement).row() else null }

    /** Runs [action] once the transaction has committed. */
    fun afterCommit(action: () -> Unit) {
        committedActions += action
    }

    private fun prepare(sql: String, arguments: Array<out Any?>): SqliteStatement {
        val statement = connection.prepare(sql)
        try {
            arguments.forEachIndexed { index, argument -> statement.bind(index + 1, argument) }
        } catch (e: Throwable) {
            statement.close()
            throw e
        }
        return statement
    }

    fun runAfterCommit() {
        for (action in committedActions) {
            try {
                action()
            } catch (e: Exception) {
                logger.warn("An action after a commit of the store failed", e)
            }
        }
    }
}

/** The row a query is on, whose columns are read by name or by position from 1. */
internal class Row(private val statement: SqliteStatement) {
    /** The index of each column name, the first of the columns that share one. */
    private val indexes: Map<String, Int> by lazy {
        val names = HashMap<String, Int>()
        for (index in 0 until statement.columnCount()) names.putIfAbsent(statement.columnName(index), index)
        names
    }

    val columnCount: Int get() = statement.columnCount()

    fun getString(column: String): String = getString(position(column))

    fun getString(position: Int): String =
        getStringOrNull(position) ?: throw IllegalStateException("Column $position of the row is NULL.")

    fun getStringOrNull(column: String): String? = getStringOrNull(position(column))

    fun getStringOrNull(position: Int): String? {
        val text = statement.getText(position - 1)
        return if (text.isEmpty() && statement.isNull(position - 1)) null else text
    }

    /** The column's number, 0 when it is NULL. */
    fun getLong(column: String): Long = getLong(position(column))

    fun getLong(position: Int): Long = statement.getLong(position - 1)

    fun getLongOrNull(column: String): Long? {
        val position = position(column)
        return if (statement.isNull(position - 1)) null else getLong(position)
    }

    fun getInt(column: String): Int = getLong(column).toInt()

    fun getInt(position: Int): Int = getLong(position).toInt()

    fun getBlob(position: Int): ByteArray = statement.getBlob(position - 1)

    /** The column's value as a Long, a Double, a String or a ByteArray, null when it is NULL. */
    fun getObject(position: Int): Any? = when (statement.columnType(position - 1)) {
        SqliteType.INTEGER -> statement.getLong(position - 1)
        SqliteType.FLOAT -> statement.getDouble(position - 1)
        SqliteType.TEXT -> statement.getText(position - 1)
        SqliteType.BLOB -> statement.getBlob(position - 1)
        SqliteType.NULL -> null
    }

    private fun position(column: String): Int =
        (indexes[column] ?: throw IllegalArgumentException("The row has no column $column.")) + 1
}

/** Runs [block] in a transaction of its own that it commits, or rolls back when [block] throws. */
internal fun <T> SqliteConnection.inTransaction(now: Instant, block: Tx.() -> T): T {
    val tx = Tx(this, now)
    beginImmediate()
    val result = try {
        tx.block().also { commit() }
    } catch (e: Throwable) {
        try {
            rollback()
        } catch (rollback: Exception) {
            e.addSuppressed(rollback)
        }
        throw e
    }
    tx.runAfterCommit()
    return result
}

internal fun SqliteConnection.runSql(sql: String) {
    prepare(sql).use { it.runToEnd() }
}

internal fun Row.instant(column: String): Instant = Instant.ofEpochMilli(getLong(column))

internal fun Row.instantOrNull(column: String): Instant? = getLongOrNull(column)?.let(Instant::ofEpochMilli)

private fun SqliteStatement.runToEnd() {
    var more = step()
    while (more) more = step()
}

private fun SqliteStatement.bind(index: Int, argument: Any?) {
    when (argument) {
        null -> bindNull(index)
        is String -> bindText(index, argument)
        is Long -> bindLong(index, argument)
        is Int -> bindLong(index, argument.toLong())
        is Instant -> bindLong(index, argument.toEpochMilli())
        else -> throw IllegalArgumentException("The store cannot bind a ${argument::class.qualifiedName}.")
    }
}

private val logger = LoggerFactory.getLogger(Tx::class.java)
