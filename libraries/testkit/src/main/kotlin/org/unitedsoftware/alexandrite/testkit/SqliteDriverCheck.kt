package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import org.unitedsoftware.alexandrite.testkit.sql.SQLITE_DRIVER_CHECKS
import org.unitedsoftware.alexandrite.testkit.sql.SqliteDriverRun
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** The checks that every [SqliteDriver] passes, each in a directory of its own. */
public val SQLITE_DRIVER_CONTRACT: List<SqliteDriverCheck> = SQLITE_DRIVER_CHECKS

/** One check of the [SQLITE_DRIVER_CONTRACT]. */
public class SqliteDriverCheck internal constructor(
    public val name: String,
    private val body: SqliteDriverRun.() -> Unit,
) {
    /**
     * Runs the check on [driver] in a directory it deletes afterwards, and throws an [AssertionError] when [driver]
     * breaks the contract.
     */
    @OptIn(ExperimentalPathApi::class)
    public fun run(driver: SqliteDriver) {
        val directory = Files.createTempDirectory("alexandrite-sqlite-")
        try {
            SqliteDriverRun(driver, directory).body()
        } finally {
            directory.deleteRecursively()
        }
    }

    override fun toString(): String = name
}
