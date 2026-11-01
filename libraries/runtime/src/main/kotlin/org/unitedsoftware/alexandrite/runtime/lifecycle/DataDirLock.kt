package org.unitedsoftware.alexandrite.runtime.lifecycle

import org.unitedsoftware.alexandrite.internal.path.createOwnerOnly
import org.unitedsoftware.alexandrite.internal.portable.ProcessIds
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** The lock that keeps a data directory to one runtime. */
internal class DataDirLock private constructor(private val key: Any, private val channel: FileChannel) :
    AutoCloseable {
    override fun close() {
        try {
            channel.close()
        } finally {
            held.remove(key)
        }
    }

    companion object {
        const val FILE_NAME = "runtime.lock"

        private const val THIS_JVM = "another runtime of this JVM"

        /** The lock files that runtimes of this JVM hold, by file key. */
        private val held: MutableSet<Any> = ConcurrentHashMap.newKeySet()

        /**
         * Locks [dataDir], calling [inUse] with the holder when another runtime has it, and writes [pid] into the lock
         * file where it is known.
         */
        fun acquire(dataDir: Path, pid: Long? = ProcessIds.current, inUse: (holder: String) -> Nothing): DataDirLock {
            createOwnerOnly(dataDir)
            val file = dataDir.toRealPath().resolve(FILE_NAME)
            try {
                Files.createFile(file)
            } catch (e: FileAlreadyExistsException) {
            }
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile) throw IOException("The lock file $file is not a regular file.")
            val key = attributes.fileKey() ?: file
            if (!held.add(key)) inUse(THIS_JVM)
            try {
                return DataDirLock(key, lockedChannel(file, pid, inUse))
            } catch (e: Throwable) {
                held.remove(key)
                throw e
            }
        }

        private fun lockedChannel(file: Path, pid: Long?, inUse: (holder: String) -> Nothing): FileChannel {
            val channel = FileChannel.open(file, READ, WRITE, NOFOLLOW_LINKS)
            try {
                val lock = try {
                    channel.tryLock()
                } catch (e: OverlappingFileLockException) {
                    inUse(THIS_JVM)
                }
                if (lock == null) inUse(holder(channel))
                channel.truncate(0)
                if (pid != null) channel.write(ByteBuffer.wrap("$pid\n".toByteArray()), 0)
                channel.force(false)
                return channel
            } catch (e: Throwable) {
                try {
                    channel.close()
                } catch (closing: IOException) {
                    e.addSuppressed(closing)
                }
                throw e
            }
        }

        private fun holder(channel: FileChannel): String {
            val pid = try {
                val buffer = ByteBuffer.allocate(32)
                channel.read(buffer, 0)
                String(buffer.array(), 0, buffer.position()).trim().toLongOrNull()
            } catch (e: IOException) {
                null
            }
            return pid?.let { "process $it" } ?: "another process"
        }
    }
}
