package org.unitedsoftware.alexandrite.testkit.sql

import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import java.nio.file.Path

/** One run of a driver check, whose databases are files in [directory]. */
internal class SqliteDriverRun(private val driver: SqliteDriver, private val directory: Path) {
    /** The file of the database [name]. */
    fun file(name: String = "test.db"): Path = directory.resolve(name)

    /** Runs [block] on a new connection to the database [name], and closes it. */
    fun <T> connect(name: String = "test.db", block: SqliteConnection.() -> T): T =
        driver.open(file(name).toString()).use(block)
}
