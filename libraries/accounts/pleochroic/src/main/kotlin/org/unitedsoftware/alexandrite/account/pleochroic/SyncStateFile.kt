package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.SerializationException
import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a host keeps its [SyncState]: [FILE_NAME] in its data directory, shared by `alexandrite sync` and the running
 * app's `pleochroic.sync` tool.
 */
public class SyncStateFile(public val file: Path) {
    /** The state the file holds, [SyncState.OFF] when it is missing, unreadable or malformed. */
    public fun read(): SyncState {
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            return SyncState.OFF
        }
        return try {
            decodeSyncState(text)
        } catch (e: SerializationException) {
            SyncState.OFF
        }
    }

    /** Writes [state] in place of the file's, readable by its owner alone; throws [IOException] when it cannot. */
    public fun write(state: SyncState) {
        Files.createDirectories(file.parent)
        writeAtomically(file, ownerOnly = true) { it.write(encodeSyncState(state).toByteArray(Charsets.UTF_8)) }
    }

    public companion object {
        public const val FILE_NAME: String = "pleochroic-sync.json"

        public fun inDataDirectory(dataDirectory: Path): SyncStateFile = SyncStateFile(dataDirectory.resolve(FILE_NAME))
    }
}
