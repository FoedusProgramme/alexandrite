package org.unitedsoftware.alexandrite.app.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.app.Cli
import org.unitedsoftware.alexandrite.app.control.ConfigChangeView
import org.unitedsoftware.alexandrite.app.readSecrets
import org.unitedsoftware.alexandrite.app.writeChanges
import org.unitedsoftware.alexandrite.app.writeSecrets
import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What one `sync now` run did. */
internal class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val deletedRemote: Int = 0,
    val deletedLocal: Int = 0,
    val merged: Int = 0,
    val conflicts: Int = 0,
    val skippedProfiles: Int = 0,
)

/**
 * Runs one sync: reads the change feed from [SyncState.cursor], decides every tracked item with [SyncEngine], and
 * applies it, config through one [writeChanges] batch, secrets through one read-modify-write of the secrets file,
 * instructions through their files, and the rest (pushes, remote deletes) through [transport].
 */
internal class SyncRunner(private val cli: Cli, private val transport: SyncTransport) {
    suspend fun run(state: SyncState): Pair<SyncReport, SyncState> {
        val engine = SyncEngine(state.policy)
        val page = readChanges(state.cursor)
        val remote = page.changes.filterNot { it.kind == "profile" }.associateBy { syncKey(it.area, it.kind, it.id) }
        val skippedProfiles = page.changes.count { it.kind == "profile" }

        val local = collectLocal(cli).associateBy { it.key }
        val configWrites = mutableListOf<ConfigChangeView>()
        val secretWrites = mutableMapOf<String, String?>()
        val fileWrites = mutableMapOf<Path, String?>()
        var records = state.records
        var pushed = 0
        var pulled = 0
        var deletedRemote = 0
        var deletedLocal = 0
        var merged = 0
        var conflicts = 0

        for (key in (records.keys + remote.keys + local.keys)) {
            val (area, kind, id) = splitKey(key)
            val before = records[key]
            val localData = local[key]?.data
            val remoteState = remoteStateOf(remote[key], area, kind, id)
            val decision = engine.decide(before, localData, remoteState)
            if (decision.conflict) conflicts++
            val remoteRevNow = (remoteState as? RemoteState.Present)?.rev ?: before?.remoteRev
            when (val outcome = decision.outcome) {
                is SyncOutcome.Push -> {
                    val meta = pushOne(area, kind, id, outcome.data, remoteRevNow)
                    pushed++
                    records = records + (key to SyncRecord(contentHash(outcome.data), meta.rev))
                }

                SyncOutcome.DeleteRemote -> {
                    remoteRevNow?.let { transport.delete(area, kind, id, it) }
                    deletedRemote++
                    records = records - key
                }

                is SyncOutcome.Pull -> {
                    queueLocal(area, kind, id, outcome.data, configWrites, secretWrites, fileWrites)
                    pulled++
                    records = records + (key to SyncRecord(contentHash(outcome.data), remoteRevNow))
                }

                SyncOutcome.DeleteLocal -> {
                    queueLocal(area, kind, id, null, configWrites, secretWrites, fileWrites)
                    deletedLocal++
                    records = records - key
                }

                is SyncOutcome.Merged -> {
                    queueLocal(area, kind, id, outcome.data, configWrites, secretWrites, fileWrites)
                    val meta = pushOne(area, kind, id, outcome.data, remoteRevNow)
                    merged++
                    records = records + (key to SyncRecord(contentHash(outcome.data), meta.rev))
                }

                SyncOutcome.Noop -> Unit
            }
        }

        commitConfig(configWrites)
        commitSecrets(secretWrites)
        commitFiles(fileWrites)
        val report = SyncReport(pushed, pulled, deletedRemote, deletedLocal, merged, conflicts, skippedProfiles)
        return report to state.with(cursor = page.cursor, records = records)
    }

    /** Pages `/sync/changes` from [since] to its end; a `snapshot_required` answer restarts from the beginning. */
    private suspend fun readChanges(since: Long): ChangePage = try {
        transport.changes(since)
    } catch (e: SyncServiceException) {
        if (e.code == "snapshot_required" && since != 0L) transport.changes(0) else throw e
    }

    private suspend fun remoteStateOf(changed: ChangeEntry?, area: String, kind: String, id: String): RemoteState =
        when {
            changed == null -> RemoteState.Unchanged

            changed.deleted -> RemoteState.Deleted(changed.rev ?: "deleted")

            else -> {
                val fetched = transport.get(area, kind, id)
                if (fetched == null) {
                    RemoteState.Deleted(changed.rev ?: "deleted")
                } else {
                    val data = if (area == AREA_SECRET) unwrapSecretValue(fetched.data) else fetched.data
                    RemoteState.Present(fetched.rev, data)
                }
            }
        }

    private suspend fun pushOne(
        area: String,
        kind: String,
        id: String,
        data: JsonElement,
        ifMatch: String?,
    ): RemoteMeta = try {
        transport.put(area, kind, id, data, ifMatch)
    } catch (e: SyncConflictException) {
        // Decided already: this item's local content wins the conflict, so it overwrites what just landed too.
        transport.put(area, kind, id, data, e.current.rev)
    }

    private fun queueLocal(
        area: String,
        kind: String,
        id: String,
        data: JsonElement?,
        configWrites: MutableList<ConfigChangeView>,
        secretWrites: MutableMap<String, String?>,
        fileWrites: MutableMap<Path, String?>,
    ) {
        if (area == AREA_SECRET) {
            secretWrites[id] = data?.let { (unwrapSecretValue(it) as? JsonPrimitive)?.content }
            return
        }
        if (kind == KIND_INSTRUCTIONS) {
            instructionsFile(cli, id)?.let { file ->
                fileWrites[file] = data?.let { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
            }
            return
        }
        val path = configPath(kind, id) ?: return
        configWrites += if (data == null) {
            ConfigChangeView(path, remove = true)
        } else {
            ConfigChangeView(path, data)
        }
    }

    private fun commitConfig(changes: List<ConfigChangeView>) {
        if (changes.isEmpty()) return
        if (writeChanges(cli, changes) == null) cli.say(cli.t("sync.config_unwritable"))
    }

    private fun commitSecrets(changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        try {
            val secrets = readSecrets(cli.secrets).toMutableMap()
            for ((name, value) in changes) {
                if (value == null) secrets.remove(name) else secrets[name] = value
            }
            writeSecrets(cli.secrets, secrets)
        } catch (e: IOException) {
            cli.say(cli.t("secret.unwritable", "file" to "${cli.secrets}", "reason" to (e.message ?: "$e")))
        }
    }

    private fun commitFiles(changes: Map<Path, String?>) {
        for ((file, text) in changes) {
            try {
                if (text == null) {
                    Files.deleteIfExists(file)
                } else {
                    Files.createDirectories(file.parent)
                    writeAtomically(file, ownerOnly = false) { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            } catch (e: IOException) {
                cli.say(cli.t("sync.file_unwritable", "file" to "$file", "reason" to (e.message ?: "$e")))
            }
        }
    }

    private fun splitKey(key: String): Triple<String, String, String> {
        val parts = key.split(":", limit = 3)
        return Triple(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }
}
