package org.unitedsoftware.alexandrite.runtime.lifecycle

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.path.createOwnerOnly
import org.unitedsoftware.alexandrite.internal.path.writableByOthers
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.StartStage
import org.unitedsoftware.alexandrite.runtime.channel.RuntimeChannelInstance
import org.unitedsoftware.alexandrite.runtime.chat.UnreadableStateListener
import org.unitedsoftware.alexandrite.runtime.config.ConfigResolution
import org.unitedsoftware.alexandrite.runtime.config.EnabledPlugin
import org.unitedsoftware.alexandrite.runtime.config.HostConfigFile
import org.unitedsoftware.alexandrite.runtime.config.resolveConfig
import org.unitedsoftware.alexandrite.runtime.configFailure
import org.unitedsoftware.alexandrite.runtime.hook.HookDecisionListener
import org.unitedsoftware.alexandrite.runtime.hook.HookFailureListener
import org.unitedsoftware.alexandrite.runtime.i18n.RuntimeTexts
import org.unitedsoftware.alexandrite.runtime.plugin.classLoader
import org.unitedsoftware.alexandrite.runtime.plugin.pluginProblems
import org.unitedsoftware.alexandrite.runtime.startFailure
import org.unitedsoftware.alexandrite.runtime.store.UnreadableRecordListener
import org.unitedsoftware.alexandrite.runtime.store.partialStore
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelControl
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Container
import org.unitedsoftware.alexandrite.sdk.di.container.DiException
import org.unitedsoftware.alexandrite.sdk.di.container.PluginBindings
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.HookFailure
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.problem.Problem
import org.unitedsoftware.alexandrite.sdk.runtime.RuntimeControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

/** The work of the start stages from DATA_DIR to GRAPH. */
internal class Assembly(
    private val spec: RuntimeSpec,
    private val redactor: Redactor,
    private val control: (plugin: String) -> RuntimeControl,
    private val scopes: PluginScopes,
    private val texts: RuntimeTexts,
    private val context: CoroutineContext,
) {
    private val name = spec.config.name

    private val hookFailures = HookFailureListener { hook, point, failure ->
        val error = (failure as? HookFailure.Threw)?.error?.let(redactor::error)
        val text = redactor.text("$failure")
        logger.warn("{}: hook {} failed at '{}': {}", name, hook.javaClass.name, point, text, error)
    }

    private val hookDecisions = HookDecisionListener { hook, point, decision ->
        val what = if (decision is HookDecision.Abort) "stopped the chain" else "replaced the payload"
        logger.debug("{}: hook {} {} at '{}'", name, hook.javaClass.name, what, point)
    }

    private val unreadableStates = UnreadableStateListener { plugin, state, key, error ->
        logger.warn(
            "{}: chat state '{}' of plugin {} at {} cannot be read and counts as absent",
            name,
            state,
            plugin,
            key,
            redactor.error(error),
        )
    }

    private val unreadableRecords = UnreadableRecordListener { plugin, collection, key, error ->
        logger.warn(
            "{}: record '{}' of collection '{}' of plugin {} cannot be read and counts as absent",
            name,
            key,
            collection,
            plugin,
            redactor.error(error),
        )
    }

    fun lockDataDir(): DataDirLock {
        val dataDir = spec.config.dataDir
        val lock = try {
            DataDirLock.acquire(dataDir) { holder ->
                val message = "Data directory '$dataDir' is in use: $holder holds its lock file " +
                    "${DataDirLock.FILE_NAME}. Stop that runtime or give this one another data directory."
                val problem = Problem(RuntimeProblemKind.DATA_DIR_LOCKED, message, null)
                throw startFailure(name, StartStage.DATA_DIR, listOf(problem))
            }
        } catch (e: IOException) {
            throw startFailure(name, StartStage.DATA_DIR, cause = e)
        }
        warnIfShared("data", dataDir)
        return lock
    }

    fun createCacheDir() {
        val cacheDir = spec.config.cacheDir
        val inside = try {
            createOwnerOnly(cacheDir)
            val data = spec.config.dataDir.toRealPath()
            val cache = cacheDir.toRealPath()
            data.startsWith(cache) || cache.startsWith(data.resolve(PLUGINS_DIRECTORY))
        } catch (e: IOException) {
            throw startFailure(name, StartStage.DATA_DIR, cause = e)
        }
        if (inside) {
            val detail = "the cache directory '$cacheDir' is the data directory, holds it or lies in its " +
                "'$PLUGINS_DIRECTORY' directory"
            throw startFailure(name, StartStage.DATA_DIR, detail = detail)
        }
        warnIfShared("cache", cacheDir)
    }

    fun checkPlugins() {
        val plugins = spec.plugins
        plugins.unlisted.forEach {
            logger.warn("{}: not loading {}: the index is neither built in nor added to the plugin set", name, it)
        }
        pluginProblems(plugins).takeIf { it.isNotEmpty() }?.let { throw startFailure(name, StartStage.PLUGINS, it) }
    }

    fun resolvePlugins(): ConfigResolution {
        val secrets = mutableListOf<String>()
        val resolution = try {
            resolveConfig(spec.plugins.members, spec.pluginConfig, secrets)
        } catch (e: Exception) {
            interruption(e)?.let { throw it }
            throw startFailure(name, StartStage.CONFIG, cause = e)
        } finally {
            redactor.add(secrets)
        }
        resolution.unknownPluginConfig.forEach {
            logger.warn("{}: ignoring the config at '{}': no plugin of the plugin set reads it", name, it)
        }
        if (resolution.problems.isNotEmpty()) throw startFailure(name, StartStage.CONFIG, resolution.problems)
        partialStore(resolution.enabled.map { it.member }, resolution.enabled.associate { it.id to it.bindings })?.let {
            logger.warn("{}: {}", name, it)
        }
        logger.info(
            "{}: loading plugins [{}], disabled [{}]",
            name,
            resolution.enabled.joinToString { it.member.id },
            resolution.disabled.joinToString { "${it.id} (${it.reason})" },
        )
        for (plugin in resolution.enabled.filter { it.channelType != null && it.config.instances.isEmpty() }) {
            logger.warn(
                "{}: channel plugin {} has no channel instances: add them below '{}.{}'",
                name,
                plugin.id,
                plugin.member.index.configRoot,
                PluginIds.INSTANCES_KEY,
            )
        }
        return resolution
    }

    fun pluginBindings(enabled: List<EnabledPlugin>): List<PluginBindings> =
        enabled.map { PluginBindings(it.id, it.bindings + it.config.bindings) }

    /** The container of [plugins] and of what the runtime binds for [enabled]. */
    fun container(
        enabled: List<EnabledPlugin>,
        plugins: List<PluginBindings>,
        directory: ChannelDirectory,
        channels: ChannelControl,
    ): Container = try {
        val infos = enabled.map { it.member.plugin.info }
        val builtIns = enabled.filter { it.member.plugin.builtIn }.mapTo(HashSet()) { it.id }
        val initiation = key<TurnInitiation>()
        val initiated = plugins.any { plugin -> plugin.bindings.any { it.key == initiation } }
        texts.install(spec.texts.value)
        CoroutineScope(context).launch(CoroutineName("$name texts")) { spec.texts.collect(texts::install) }
        val runtime = runtimeBindings(
            spec.config,
            spec.configFile,
            infos,
            builtIns,
            enabled.associate { it.id to it.member.index.classLoader() },
            hookFailures,
            hookDecisions,
            unreadableStates,
            unreadableRecords,
            texts,
            control,
            { live -> HostConfigFile(spec.writableFile, spec.plugins, spec.environment, name, live) },
            scopes,
            directory,
            channels,
            initiated,
            context,
            spec.agentPlaces,
        )
        Container.build(plugins + runtime)
    } catch (e: Exception) {
        interruption(e)?.let { throw it }
        val problems = (e as? DiException)?.problems.orEmpty()
        configFailure(name, StartStage.GRAPH, e, problems.firstOrNull()?.plugin, null)?.let { throw it }
        throw startFailure(name, StartStage.GRAPH, problems, e)
    }

    /** Validates the channel instance graph of each plugin that has one without creating anything. */
    fun checkChannelInstances(container: Container, enabled: List<EnabledPlugin>) {
        val problems = try {
            enabled.filter { plugin ->
                plugin.channelType != null || plugin.bindings.any { it.scope == Scope.CHANNEL_INSTANCE }
            }.flatMap { container.validateChild(setOf(it.id), placeholders(it)) }
        } catch (e: Exception) {
            interruption(e)?.let { throw it }
            throw startFailure(name, StartStage.GRAPH, cause = e)
        }
        if (problems.isNotEmpty()) throw startFailure(name, StartStage.GRAPH, problems)
    }

    /** Creates the container of each channel instance of [enabled] in config order, handing each to [created]. */
    fun createInstances(container: Container, enabled: List<EnabledPlugin>, created: (InstanceContainer) -> Unit) {
        for (plugin in enabled) {
            for (instance in plugin.config.instances) {
                val scope = scopes.create(plugin.id, instance.id)
                val bound = instanceBinding(
                    key<ChannelInstance>(),
                    RuntimeChannelInstance(instance.id, scope),
                    RUNTIME_PLUGIN,
                    "ChannelInstance",
                    Scope.CHANNEL_INSTANCE,
                )
                val child = try {
                    container.child("${instance.id}", setOf(plugin.id), listOf(bound) + instance.bindings)
                } catch (e: Exception) {
                    interruption(e)?.let { throw it }
                    val label = "channel instance container '${instance.id}'"
                    configFailure(name, StartStage.GRAPH, e, plugin.id, label)?.let { throw it }
                    throw startFailure(name, StartStage.GRAPH, (e as? DiException)?.problems.orEmpty(), e)
                }
                created(InstanceContainer(instance.id, plugin.id, child, scope, child.getAll(key<Channel>()).single()))
            }
        }
    }

    /** Bindings that stand in for the ones the runtime adds to each channel instance container of [plugin]. */
    private fun placeholders(plugin: EnabledPlugin): List<Binding<*>> {
        if (plugin.channelType == null) return emptyList()
        val sections = plugin.member.index.configSections().filter { it.scope == Scope.CHANNEL_INSTANCE }
        return listOf(placeholder(key<ChannelInstance>(), RUNTIME_PLUGIN, "ChannelInstance")) +
            sections.map { placeholder(it.key, plugin.id, it.origin) }
    }

    private fun warnIfShared(role: String, directory: Path) {
        if (writableByOthers(directory)) {
            logger.warn("{}: the {} directory '{}' is writable by users other than its owner", name, role, directory)
        }
    }
}

/** The interruption [error] stems from, null when it stems from none. */
private fun interruption(error: Throwable): InterruptedException? =
    generateSequence(error) { it.cause }.firstNotNullOfOrNull { cause ->
        when (cause) {
            is InterruptedException -> cause
            is ClosedByInterruptException -> InterruptedException().also { it.initCause(cause) }
            else -> null
        }
    }

/** A channel-instance-scoped binding of [key] that validation sees and nothing creates. */
private fun <T : Any> placeholder(key: Key<T>, plugin: String, origin: String): Binding<T> =
    binding(key, plugin, origin, Scope.CHANNEL_INSTANCE, managed = false) { error("$origin is never created") }

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)
