package org.unitedsoftware.alexandrite.store.memory

import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.StoredRecord

@Singleton
@Binds(RecordStore::class)
internal class MemoryRecordStore(private val data: StoreData) : RecordStore {
    override suspend fun get(plugin: String, collection: String, key: String): StoredRecord<String>? =
        data.locked { records[plugin to collection]?.get(key) }

    override suspend fun put(
        plugin: String,
        collection: String,
        key: String,
        json: String,
        by: UserAddress?,
    ): StoredRecord<String> = data.locked { now ->
        val collected = records.getOrPut(plugin to collection) { LinkedHashMap() }
        val before = collected[key]
        StoredRecord(key, json, before?.createdAt ?: now, if (before == null) by else before.createdBy, now, by)
            .also { collected[key] = it }
    }

    override suspend fun remove(plugin: String, collection: String, key: String): Boolean =
        data.locked { records[plugin to collection]?.remove(key) != null }

    override suspend fun list(plugin: String, collection: String): List<StoredRecord<String>> =
        data.locked { records[plugin to collection]?.values?.toList().orEmpty() }
}
