package org.unitedsoftware.alexandrite.store.memory

import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.internal.id.newId
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.store.MediaInfo
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.transcript.MediaId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.StoredMedia
import java.time.Duration
import java.time.Instant

/** The bytes of media, of which a put removes those that no entry has referred to for a day. */
@Singleton
@Binds(MediaStore::class)
internal class MemoryMediaStore(private val data: StoreData) : MediaStore {
    private var swept: Instant = Instant.MIN

    override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
        require(bytes.size <= MAX_BYTES) {
            "Media of ${bytes.size} bytes is larger than the $MAX_BYTES bytes the store takes."
        }
        require(mediaType.isNotBlank()) { "Media needs a media type." }
        val sha256 = sha256Hex(bytes)
        val id = MediaId(newId())
        val copy = bytes.copyOf()
        data.locked { now ->
            if (swept < now - SWEEP_EVERY) {
                sweep(now)
                swept = now
            }
            media[id] = StoredBytes(MediaInfo(id, kind, mediaType, bytes.size.toLong(), sha256, now), copy)
        }
        return StoredMedia(id)
    }

    override suspend fun read(id: MediaId): ByteArray? = data.locked { media[id]?.bytes?.copyOf() }

    override suspend fun info(id: MediaId): MediaInfo? = data.locked { media[id]?.info }

    private fun StoreData.sweep(now: Instant) {
        val referred = entries.values.flatMapTo(HashSet()) { it.media }
        media.values.removeIf { it.info.id !in referred && it.info.createdAt < now - UNREFERENCED_FOR }
    }

    companion object {
        const val MAX_BYTES: Int = 32 * 1024 * 1024

        val UNREFERENCED_FOR: Duration = Duration.ofHours(24)

        private val SWEEP_EVERY = Duration.ofHours(1)
    }
}
