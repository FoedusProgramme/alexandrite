package org.unitedsoftware.alexandrite.testkit.sql

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.unitedsoftware.alexandrite.engine.sqlitexerial.AlexandriteEngineSqliteXerialIndex
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteStatement
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteType
import org.unitedsoftware.alexandrite.testkit.SQLITE_DRIVER_CONTRACT
import org.unitedsoftware.alexandrite.testkit.boundBy
import kotlin.test.assertFailsWith

/** A way to break the driver contract, and the name of the check that catches it. */
private enum class SqliteDriverFlaw(val check: String) {
    NULL_AS_TEXT("a value of each type reads back as it was bound, with its type"),
    COLUMNS_ON_A_ROW_ONLY("the columns are counted and named as the statement names them, from its prepare on"),
    RESET_KEEPS_THE_END("a statement steps through its rows, and a reset runs it again with its values"),
    ROLLBACK_COMMITS("a committed transaction is kept and a rolled back one leaves nothing"),
}

/** [driver] broken by [flaw]. */
private class FlawedDriver(private val driver: SqliteDriver, private val flaw: SqliteDriverFlaw) : SqliteDriver {
    override fun open(path: String): SqliteConnection = Connection(driver.open(path))

    private inner class Connection(private val connection: SqliteConnection) : SqliteConnection by connection {
        override fun prepare(sql: String): SqliteStatement = Statement(connection.prepare(sql))

        override fun rollback() = if (flaw == SqliteDriverFlaw.ROLLBACK_COMMITS) commit() else connection.rollback()
    }

    private inner class Statement(private val statement: SqliteStatement) : SqliteStatement by statement {
        private var onRow = false

        override fun step(): Boolean = statement.step().also { onRow = it }

        override fun columnCount(): Int =
            if (flaw == SqliteDriverFlaw.COLUMNS_ON_A_ROW_ONLY && !onRow) 0 else statement.columnCount()

        override fun columnType(index: Int): SqliteType = statement.columnType(index).let { type ->
            if (flaw == SqliteDriverFlaw.NULL_AS_TEXT && type == SqliteType.NULL) SqliteType.TEXT else type
        }

        override fun isNull(index: Int): Boolean = flaw != SqliteDriverFlaw.NULL_AS_TEXT && statement.isNull(index)

        override fun reset() {
            onRow = false
            if (flaw != SqliteDriverFlaw.RESET_KEEPS_THE_END) statement.reset()
        }
    }
}

class SqliteDriverContractTest {
    private val driver = boundBy<SqliteDriver>(AlexandriteEngineSqliteXerialIndex())

    @TestFactory
    fun `each check fails a driver with the flaw it guards against`(): List<DynamicTest> =
        SqliteDriverFlaw.entries.map { flaw ->
            DynamicTest.dynamicTest("$flaw: ${flaw.check}") {
                val check = SQLITE_DRIVER_CONTRACT.single { it.name == flaw.check }

                assertFailsWith<AssertionError> { check.run(FlawedDriver(driver, flaw)) }
            }
        }
}
