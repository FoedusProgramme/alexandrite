package org.unitedsoftware.alexandrite.testkit.sql

import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteException
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteStatement
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteType
import org.unitedsoftware.alexandrite.testkit.SqliteDriverCheck
import org.unitedsoftware.alexandrite.testkit.store.expect
import org.unitedsoftware.alexandrite.testkit.store.expectEqual
import org.unitedsoftware.alexandrite.testkit.store.expectThrows
import java.nio.file.Files
import java.nio.file.Path

/** One run of a driver check, whose databases are files in [directory]. */
internal class DriverRun(private val driver: SqliteDriver, private val directory: Path) {
    /** The file of the database [name]. */
    fun file(name: String = "test.db"): Path = directory.resolve(name)

    /** Runs [block] on a new connection to the database [name], and closes it. */
    fun <T> connect(name: String = "test.db", block: SqliteConnection.() -> T): T =
        driver.open(file(name).toString()).use(block)
}

internal val SQLITE_DRIVER_CHECKS: List<SqliteDriverCheck> = listOf(
    SqliteDriverCheck("a value of each type reads back as it was bound, with its type") {
        val blob = ByteArray(300) { it.toByte() }
        connect {
            execute("CREATE TABLE t (v)")
            val values = listOf<SqliteStatement.(Int) -> Unit>(
                { bindLong(it, Long.MIN_VALUE) },
                { bindLong(it, Long.MAX_VALUE) },
                { bindDouble(it, -0.5) },
                { bindText(it, "ünïcode 🚀 and \"quotes\"") },
                { bindText(it, "") },
                { bindBlob(it, blob) },
                { bindBlob(it, ByteArray(0)) },
                { bindNull(it) },
            )
            prepare("INSERT INTO t (v) VALUES (?)").use { insert ->
                for (bind in values) {
                    insert.bind(1)
                    expectEqual(false, insert.step(), "a row of an insert")
                    insert.reset()
                }
            }
            prepare("SELECT v, typeof(v) FROM t ORDER BY rowid").use { select ->
                val read = buildList { while (select.step()) add(select.value(0) to select.getText(1)) }
                expectEqual(
                    listOf(
                        Long.MIN_VALUE to "integer",
                        Long.MAX_VALUE to "integer",
                        -0.5 to "real",
                        "ünïcode 🚀 and \"quotes\"" to "text",
                        "" to "text",
                        blob.toList() to "blob",
                        emptyList<Byte>() to "blob",
                        null to "null",
                    ),
                    read,
                    "the values and their types",
                )
            }
            prepare("SELECT NULL").use { select ->
                select.step()
                expectEqual(true, select.isNull(0), "whether NULL is null")
                expectEqual(0L, select.getLong(0), "NULL as a number")
                expectEqual("", select.getText(0), "NULL as text")
                expectEqual(0, select.getBlob(0).size, "the bytes of NULL")
            }
        }
    },
    SqliteDriverCheck("a statement steps through its rows, and a reset runs it again with its values") {
        connect {
            prepare("WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) SELECT i FROM n")
                .use { select ->
                    select.bindLong(1, 3)
                    val first = buildList { while (select.step()) add(select.getLong(0)) }
                    expectEqual(false, select.step(), "a step after the last row")
                    select.reset()
                    val again = buildList { while (select.step()) add(select.getLong(0)) }

                    expectEqual(listOf(1L, 2L, 3L), first, "the rows")
                    expectEqual(first, again, "the rows after a reset")
                }
        }
    },
    SqliteDriverCheck("the columns are counted and named as the statement names them") {
        connect {
            prepare("SELECT 1 AS one, 'x' AS \"Two words\", 2").use { select ->
                expect(select.step()) { "The statement yielded no row." }
                expectEqual(3, select.columnCount(), "the column count")
                expectEqual(listOf("one", "Two words", "2"), List(3) { select.columnName(it) }, "the column names")
            }
        }
    },
    SqliteDriverCheck("changes and the last rowid tell of the last insert, update or delete") {
        connect {
            execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            execute("INSERT INTO t (id, v) VALUES (41, 'a')")
            execute("INSERT INTO t (v) VALUES ('b')")
            expectEqual(42L, lastInsertRowId(), "the rowid of the last insert")
            expectEqual(1L, changes(), "the rows the insert changed")
            execute("UPDATE t SET v = 'c'")
            expectEqual(2L, changes(), "the rows the update changed")
            execute("DELETE FROM t WHERE id = 0")
            expectEqual(0L, changes(), "the rows a delete of nothing changed")
        }
    },
    SqliteDriverCheck("a committed transaction is kept and a rolled back one leaves nothing") {
        connect {
            execute("CREATE TABLE t (v)")
            beginImmediate()
            execute("INSERT INTO t VALUES ('kept')")
            commit()
            beginImmediate()
            execute("INSERT INTO t VALUES ('dropped')")
            expectEqual(listOf("kept", "dropped"), texts("SELECT v FROM t"), "the rows inside the transaction")
            rollback()
            beginImmediate()
            execute("INSERT INTO t VALUES ('open')")
        }
        connect { expectEqual(listOf("kept"), texts("SELECT v FROM t"), "the rows once the connection closed") }
    },
    SqliteDriverCheck("a transaction begun immediately holds the write lock against other connections") {
        connect { execute("CREATE TABLE t (v)") }
        connect {
            val holder = this
            connect {
                execute("PRAGMA busy_timeout = 0")
                holder.beginImmediate()
                expectThrows<SqliteException>("Beginning a second write transaction") { beginImmediate() }
                expectEqual(emptyList(), texts("SELECT v FROM t"), "the rows a reader sees")
                holder.execute("INSERT INTO t VALUES ('first')")
                holder.commit()
                beginImmediate()
                execute("INSERT INTO t VALUES ('second')")
                commit()
            }
            expectEqual(listOf("first", "second"), texts("SELECT v FROM t"), "the rows")
        }
    },
    SqliteDriverCheck("what SQLite refuses is a SqliteException, and the connection goes on") {
        connect {
            execute("CREATE TABLE t (v UNIQUE)")
            execute("INSERT INTO t VALUES (1)")
            expectThrows<SqliteException>("Running malformed SQL") { prepare("SELEKT 1").use { it.step() } }
            expectThrows<SqliteException>("Breaking a constraint") { execute("INSERT INTO t VALUES (1)") }
            expectThrows<SqliteException>("Committing without a transaction") { commit() }
            expectEqual(listOf("1"), texts("SELECT v FROM t"), "the rows")
        }
    },
    SqliteDriverCheck("opening a file leaves it as it was, and a file that is no database fails its first statement") {
        connect("store.db") { execute("PRAGMA user_version = 7") }
        val database = Files.readAllBytes(file("store.db"))
        connect("store.db") {}
        expectEqual(database.toList(), Files.readAllBytes(file("store.db")).toList(), "the database after an open")
        val noise = ByteArray(4096) { (it * 7).toByte() }
        Files.write(file("noise.db"), noise)

        expectThrows<SqliteException>("Reading a file that is no database") {
            connect("noise.db") { texts("PRAGMA user_version") }
        }
        expectEqual(noise.toList(), Files.readAllBytes(file("noise.db")).toList(), "the file that is no database")
    },
    SqliteDriverCheck("the SQLite is 3.35 or newer, writes ahead and has RETURNING and FTS5 with trigrams") {
        connect {
            val version = texts("SELECT sqlite_version()").single().split('.').map { it.toInt() }
            expect(version[0] > 3 || (version[0] == 3 && version[1] >= 35)) { "SQLite $version is older than 3.35." }
            expectEqual(listOf("wal"), texts("PRAGMA journal_mode = WAL"), "the journal mode")
            execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
            expectEqual(listOf("1"), texts("INSERT INTO t (v) VALUES ('a') RETURNING id"), "what an insert returned")
            execute("CREATE VIRTUAL TABLE f USING fts5(text, tokenize = 'trigram case_sensitive 0')")
            execute("INSERT INTO f (text) VALUES ('The Rocket starts')")
            expectEqual(listOf("The Rocket starts"), texts("SELECT text FROM f WHERE f MATCH 'rocket'"), "the match")
        }
    },
)

private fun SqliteConnection.execute(sql: String) {
    prepare(sql).use { statement ->
        var more = statement.step()
        while (more) more = statement.step()
    }
}

/** The first column of every row of [sql], as text. */
private fun SqliteConnection.texts(sql: String): List<String> =
    prepare(sql).use { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }

/** The value of column [index], as the Kotlin value of its type. */
private fun SqliteStatement.value(index: Int): Any? = when (columnType(index)) {
    SqliteType.INTEGER -> getLong(index)
    SqliteType.FLOAT -> getDouble(index)
    SqliteType.TEXT -> getText(index)
    SqliteType.BLOB -> getBlob(index).toList()
    SqliteType.NULL -> null
}
