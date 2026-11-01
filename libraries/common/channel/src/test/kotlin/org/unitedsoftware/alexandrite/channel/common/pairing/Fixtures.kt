package org.unitedsoftware.alexandrite.channel.common.pairing

import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock that stands still until the test moves it. */
class TestClock(var now: Instant = Instant.parse("2026-10-11T08:00:00Z")) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

/** A record store in memory, timed by [clock], whose changes fail while [failure] is set. */
class FailingRecords(clock: Clock) : RecordStore {
    private val memory = MemoryStore(clock).records

    var failure: Exception? = null

    override suspend fun get(plugin: String, collection: String, key: String): StoredRecord<String>? =
        memory.get(plugin, collection, key)

    override suspend fun put(
        plugin: String,
        collection: String,
        key: String,
        json: String,
        by: UserAddress?,
    ): StoredRecord<String> {
        failure?.let { throw it }
        return memory.put(plugin, collection, key, json, by)
    }

    override suspend fun remove(plugin: String, collection: String, key: String): Boolean {
        failure?.let { throw it }
        return memory.remove(plugin, collection, key)
    }

    override suspend fun list(plugin: String, collection: String): List<StoredRecord<String>> =
        memory.list(plugin, collection)
}
