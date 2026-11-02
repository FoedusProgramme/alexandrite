package org.unitedsoftware.alexandrite.store.sqlite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteConnection
import org.unitedsoftware.alexandrite.sdk.store.sql.SqliteDriver
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The store's SQLite database, `store.db` in the plugin's data directory, which runs one block at a time.
 *
 * Its SQL keeps to SQLite 3.18 with FTS4, the SQLite of Android's API level 26.
 */
@Singleton
internal class StoreDatabase(
    private val files: PluginFiles,
    private val clock: Clock,
    private val driver: SqliteDriver,
) : Lifecycle {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1, "alexandrite-store-sqlite")

    @Volatile
    private var connection: SqliteConnection? = null

    @Volatile
    private var closed = false

    val file: Path get() = files.dataDir.resolve(FILE_NAME)

    override suspend fun onStart() {
        withContext(dispatcher) {
            check(connection == null && !closed) { "The store was opened before." }
            connection = openStore(driver, file, STORE_MIGRATIONS, now())
        }
    }

    /** Closes the database once the components that use it have stopped and the plugin scopes are cancelled. */
    override fun onDestroy() {
        runBlocking(dispatcher) {
            closed = true
            connection?.close()
            connection = null
        }
    }

    /**
     * Runs [block] in a transaction of its own, after the blocks given before it.
     *
     * A call that has begun returns what [block] returned, even when its caller was cancelled meanwhile.
     */
    suspend fun <T> transaction(block: Tx.() -> T): T {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            withContext(dispatcher) {
                val open = connection
                    ?: throw IllegalStateException(if (closed) "The store is closed." else "The store is not open.")
                open.inTransaction(now(), block)
            }
        }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    companion object {
        const val FILE_NAME: String = "store.db"
    }
}
