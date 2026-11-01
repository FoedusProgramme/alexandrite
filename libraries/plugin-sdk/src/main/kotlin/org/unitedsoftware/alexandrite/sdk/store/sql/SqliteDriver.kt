package org.unitedsoftware.alexandrite.sdk.store.sql

import org.unitedsoftware.alexandrite.sdk.di.BoundSpi

/**
 * Opens SQLite databases, for a store that keeps its data in SQLite.
 *
 * - An engine plugin binds it, and stores call it; the runtime binds none.
 * - The SQLite behind it is version 3.35 or newer and has FTS5 with the trigram tokenizer.
 * - Every call blocks, and every failure of SQLite is a [SqliteException].
 */
@BoundSpi
public interface SqliteDriver {
    /** A connection to the database file at [path], created when there is none, which opening leaves unchanged. */
    public fun open(path: String): SqliteConnection
}

/**
 * A connection to one database, used by one thread at a time.
 *
 * Outside a transaction that [beginImmediate] began, each statement runs in a transaction of its own.
 */
public interface SqliteConnection : AutoCloseable {
    /** [sql], one statement with `?` parameters, compiled for this connection. */
    public fun prepare(sql: String): SqliteStatement

    /** Begins a transaction that holds the database's write lock until [commit] or [rollback], as `BEGIN IMMEDIATE`. */
    public fun beginImmediate()

    public fun commit()

    public fun rollback()

    /** The rowid of the row that the last finished `INSERT` of this connection added, 0 when there is none. */
    public fun lastInsertRowId(): Long

    /** How many rows the last finished `INSERT`, `UPDATE` or `DELETE` of this connection changed. */
    public fun changes(): Long

    /** Closes the connection once its statements are closed, rolling back a transaction that has not ended. */
    override fun close()
}

/**
 * A compiled statement, whose parameters are numbered from 1 and whose columns are numbered from 0.
 *
 * Its columns are counted and named from its prepare on, and their values are read in the row that the last [step]
 * returned true for.
 */
public interface SqliteStatement : AutoCloseable {
    public fun bindLong(index: Int, value: Long)

    public fun bindDouble(index: Int, value: Double)

    public fun bindText(index: Int, value: String)

    public fun bindBlob(index: Int, value: ByteArray)

    public fun bindNull(index: Int)

    /** Runs the statement up to its next row, and returns whether there is one; false once it has run to its end. */
    public fun step(): Boolean

    public fun columnCount(): Int

    public fun columnName(index: Int): String

    /** The type of the value in column [index]. */
    public fun columnType(index: Int): SqliteType

    public fun getLong(index: Int): Long

    public fun getDouble(index: Int): Double

    /** The value as text, empty for [SqliteType.NULL]. */
    public fun getText(index: Int): String

    /** The value as bytes, empty for [SqliteType.NULL]. */
    public fun getBlob(index: Int): ByteArray

    public fun isNull(index: Int): Boolean

    /** Makes the statement run again from its start with the values bound to it. */
    public fun reset()

    override fun close()
}

/** The types of SQLite's values. */
public enum class SqliteType {
    INTEGER,
    FLOAT,
    TEXT,
    BLOB,
    NULL,
}

/** A failure that SQLite reported, or a misuse of the driver's API. */
public class SqliteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
