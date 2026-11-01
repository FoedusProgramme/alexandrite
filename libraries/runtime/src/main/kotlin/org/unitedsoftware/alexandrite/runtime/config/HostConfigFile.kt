package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.io.readUtf8
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.runtime.plugin.pluginProblems
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.config.ConfigChanges
import org.unitedsoftware.alexandrite.sdk.config.ConfigEdit
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath
import org.unitedsoftware.alexandrite.sdk.config.ConfigSnapshot
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import org.unitedsoftware.alexandrite.sdk.config.LiveConfig
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Resolver
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The host's config [file], whose changes must pass the stages PLUGINS and CONFIG of a runtime of [plugins], and take
 * effect at once where a [LiveConfig] of the [live] ones takes them.
 */
@InternalAlexandriteApi
public class HostConfigFile(
    private val file: Path?,
    private val plugins: PluginSet,
    /** Reads the variables that each change resolves its references with. */
    private val environment: () -> Map<String, String>,
    /** The runtime's name in log lines. */
    private val name: String = "alexandrite",
    /** The running plugins' [LiveConfig] contributions, each with the id of its plugin. */
    private val live: () -> List<Pair<String, LiveConfig>> = ::emptyList,
) : HostConfig {
    override suspend fun get(path: String): JsonElement? = lockedConfig {
        val configured = file ?: throw refused(ConfigEditRefusal.NO_FILE, "The host names no config file.")
        val text = try {
            readUtf8(configured)
        } catch (e: NoSuchFileException) {
            throw refused(ConfigEditRefusal.NO_FILE, "The config file $configured does not exist.", e)
        } catch (e: IOException) {
            throw refused(ConfigEditRefusal.IO_ERROR, "Cannot read the config file $configured: $e", e)
        }
        val masked = readable(ConfigEditRefusal.INVALID_FILE, "The config file does not read", configured) {
            maskedSecrets(plugins.members, ConfigFile.parsed(configured, text))
        }
        if (path.isEmpty()) return@lockedConfig masked
        FileEdit { OpenFile(configured, ByteArray(0), Json.encodeToString(JsonObject.serializer(), masked)) }
            .get(path)
    }

    override suspend fun edit(block: ConfigEdit.() -> Unit): ConfigChanges = edited(block, check = false)

    override suspend fun check(block: ConfigEdit.() -> Unit): ConfigChanges = edited(block, check = true)

    override suspend fun replace(text: String): ConfigChanges = lockedConfig { write(openConfig(file), text, null) }

    /** What [block] changes, written unless it is only to [check]. */
    private suspend fun edited(block: ConfigEdit.() -> Unit, check: Boolean): ConfigChanges = lockedConfig {
        val edit = FileEdit {
            openConfig(file).also { opened ->
                readable(ConfigEditRefusal.INVALID_FILE, "The config file does not read as it is", opened.target) {
                    ConfigFile.parsed(opened.target, opened.text)
                }
            }
        }
        edit.block()
        edit.file?.let { opened -> write(opened, edit.text.text, edit.changes.map { it.path }.distinct(), check) }
            ?: NONE
    }

    /**
     * Writes [text] over [file], with the keys of each change as [paths], null for a change of the whole file, unless
     * it is only to [check] what would change.
     */
    private fun write(file: OpenFile, text: String, paths: List<List<String>>?, check: Boolean = false): ConfigChanges {
        if (text == file.text) return NONE
        val target = file.target
        val what = "The change would break the config file"
        val written = readable(ConfigEditRefusal.INVALID_RESULT, what, target) { ConfigFile.parsed(target, text) }
        val resolved = readable(ConfigEditRefusal.INVALID_RESULT, what, target) {
            ConfigFile.resolved(target, written, environment())
        }
        val resolution = resolveConfig(plugins.members, JsonConfigSource(resolved), mutableListOf())
        val problems = pluginProblems(plugins).ifEmpty { resolution.problems }.map { it.message }
        val previous = try {
            ConfigFile.parsed(target, file.text)
        } catch (e: ConfigFileException) {
            JsonObject(emptyMap())
        }
        if (problems.isNotEmpty()) {
            val unknown = unknownKeys(problems)
            val hint = paths.orEmpty().firstNotNullOfOrNull { keys ->
                bracketHint(keys, unknown, { previous.at(it) }, { isMapIn(plugins, it) })
            }
            throw refused(
                ConfigEditRefusal.INVALID_RESULT,
                listOfNotNull("$what: ${problems.joinToString(" ")}", hint).joinToString(" "),
            )
        }
        val changed = paths?.filter { previous.at(it) != written.at(it) }
            ?: mutableListOf<List<String>>().also { changedPaths(emptyList(), previous, written, it) }
        if (changed.isEmpty()) return NONE
        val set = changed.filter { written.at(it) != null }
        for (plugin in resolution.enabled) {
            val root = plugin.member.index.configRoot.split('.')
            val below = set.mapNotNull { keys ->
                when {
                    keys.size > root.size && keys.take(root.size) == root -> keys.drop(root.size)
                    root.take(keys.size) == keys -> emptyList()
                    else -> null
                }
            }
            if (below.isEmpty()) continue
            checkSecrets(plugin.member.index, plugin.channelType, written, below.map { ConfigChange(it, written) })
        }
        val taking = LiveChanges(resolution.enabled, live())
        val restart = changed.filter { keys ->
            val leaves = mutableListOf<List<String>>()
            changedPaths(keys, previous.at(keys), written.at(keys), leaves)
            leaves.map(taking::take).any { !it }
        }
        val commits = readable(ConfigEditRefusal.INVALID_RESULT, what, target) { taking.prepare() }
        val changes = ConfigChanges(changed.map(ConfigPath::format), restart.map(ConfigPath::format))
        if (check) return changes
        replaceConfig(file, text)
        logger.info("{}: changed {} in the config file {}", name, changes.changed.joinToString(), target)
        for ((plugin, commit) in commits) {
            try {
                commit()
            } catch (e: Exception) {
                logger.error("{}: plugin {} could not take the change of its config", name, plugin, e)
            }
        }
        return changes
    }

    private companion object {
        val NONE = ConfigChanges(emptyList(), emptyList())

        val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)
    }
}

/** The [LiveConfig]s of [live] that take the changes of their plugins among [enabled], once these are written. */
private class LiveChanges(enabled: List<EnabledPlugin>, live: List<Pair<String, LiveConfig>>) {
    private val plugins = enabled.associateBy { it.id }
    private val places = live.flatMap { (plugin, config) ->
        val root = plugins[plugin]?.member?.index?.configRoot?.split('.') ?: return@flatMap emptyList()
        config.paths.map { path ->
            (if (path.isEmpty()) root else root + ConfigPath.parse(path)) to (plugin to config)
        }
    }
    private val taking = mutableListOf<Pair<String, LiveConfig>>()

    /** Whether a change of [keys] is taken while the runtime runs. */
    fun take(keys: List<String>): Boolean {
        val place = places.firstOrNull { (prefix, _) -> keys.size >= prefix.size && keys.take(prefix.size) == prefix }
            ?: return false
        if (taking.none { it.second === place.second.second }) taking += place.second
        return true
    }

    /** What each [LiveConfig] that takes a change does once the change is written, by plugin. */
    fun prepare(): List<Pair<String, () -> Unit>> = taking.map { (plugin, config) ->
        plugin to config.prepare(Snapshot(plugins.getValue(plugin).config.bindings))
    }
}

/** The sections that [bindings] bind to their decoded values. */
@OptIn(InternalAlexandriteApi::class)
private class Snapshot(private val bindings: List<Binding<*>>) : ConfigSnapshot {
    override fun <T : Any> section(key: Key<T>): T? {
        val binding = bindings.firstOrNull { it.key == key && it.scope == Scope.SINGLETON } ?: return null
        @Suppress("UNCHECKED_CAST")
        return binding.create(NoResolver) as T
    }
}

/** Resolves nothing, for bindings to values. */
@OptIn(InternalAlexandriteApi::class)
private object NoResolver : Resolver {
    override fun <T : Any> get(key: Key<T>): T = unresolved(key)

    override fun <T : Any> getOrNull(key: Key<T>): T? = unresolved(key)

    override fun <T : Any> getAll(key: Key<T>): List<T> = unresolved(key)

    override fun <T : Any> lazy(key: Key<T>): Lazy<T> = unresolved(key)

    override fun <T : Any> provider(key: Key<T>): () -> T = unresolved(key)

    override fun pluginOf(instance: Any): String? = null

    private fun unresolved(key: Key<*>): Nothing = throw IllegalStateException("A config section resolves no $key.")
}

/** Adds the keys below [keys] whose values differ from [before] to [after] to [changed], whole where one is new. */
private fun changedPaths(
    keys: List<String>,
    before: JsonElement?,
    after: JsonElement?,
    changed: MutableList<List<String>>,
) {
    if (before == after) return
    if (before !is JsonObject || after !is JsonObject) {
        changed += keys
        return
    }
    for (key in after.keys + (before.keys - after.keys)) changedPaths(keys + key, before[key], after[key], changed)
}
