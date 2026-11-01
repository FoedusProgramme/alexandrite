package org.unitedsoftware.alexandrite.testkit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFile
import org.unitedsoftware.alexandrite.runtime.RuntimeConfig
import org.unitedsoftware.alexandrite.runtime.RuntimeSpec
import org.unitedsoftware.alexandrite.runtime.Termination
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.runtime.config.splicedConfig
import org.unitedsoftware.alexandrite.runtime.config.writtenConfig
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource
import org.unitedsoftware.alexandrite.sdk.config.JsonConfigSource
import org.unitedsoftware.alexandrite.sdk.di.Key
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.runtime.AgentPlaces
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.runtime.StopRequest
import org.unitedsoftware.alexandrite.sdk.runtime.WakeScheduler
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.testkit.plugin.DoublesPlugin
import org.unitedsoftware.alexandrite.testkit.plugin.RecordingChannelConfig
import org.unitedsoftware.alexandrite.testkit.plugin.RecordingChannelPlugin
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration

/**
 * Runs the plugin under test and the plugins added to it in a runtime of their own.
 *
 * A run stops at the first problem that a [ScriptedModel] or a [RecordingChannel] it holds or watches meets, and fails
 * with the doubles' problems, with what the block threw as the cause.
 */
public class PluginHarness private constructor(
    private val id: String,
    private val plugins: PluginSet,
    /** The config the harness gives the plugins. */
    private val tree: JsonObject,
    /** The part of [tree] that only the harness's own plugins read. */
    private val owned: JsonObject,
    private val environment: Map<String, String>,
    private val dataRoot: Path?,
    private val configFile: Path?,
    private val keepsConfig: Boolean,
    private val offLimits: List<Path>,
    private val zone: ZoneId,
    private val language: LanguageTag?,
    private val texts: StateFlow<TextCatalog>?,
    private val shutdownGrace: Duration?,
    private val inspectTermination: Boolean,
    private val temporaryRoot: () -> Path,
    private val models: List<ScriptedModel>,
    private val channels: List<RecordingChannelPlugin>,
    private val agentPlaces: ((HostPaths) -> AgentPlaces)?,
) {
    /**
     * Starts the plugins, runs [block] once they are ready, stops them and returns how the runtime ended, or throws
     * why they did not start.
     */
    public suspend fun run(block: suspend Running.() -> Unit): Termination {
        val root = dataRoot ?: temporaryRoot()
        var failure: Throwable? = null
        channels.forEach { it.created.clear() }
        val known = models.associateWith { it.problems.size }
        try {
            val termination = try {
                runIn(root, block)
            } catch (e: Throwable) {
                throw doubleProblems(known, e) ?: e
            }
            doubleProblems(known, null)?.let { throw it }
            return termination
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (dataRoot == null) deleteTemporary(root, failure)
        }
    }

    private suspend fun runIn(root: Path, block: suspend Running.() -> Unit): Termination {
        val settings = RuntimeConfig.builder(root).zone(zone).name("harness")
        offLimits.forEach(settings::protect)
        language?.let(settings::language)
        shutdownGrace?.let(settings::shutdownGrace)
        val builder = RuntimeSpec.builder(settings.build(), plugins)
        texts?.let(builder::texts)
        agentPlaces?.let(builder::agentPlaces)
        val file = configFile
        if (file == null) {
            builder.pluginConfig(JsonConfigSource(ConfigFile.resolve(tree, environment, CONFIG_ORIGIN)))
            return runSpec(builder.build(), block)
        }
        val ours = prepare(file)
        try {
            builder.configFile(file, environment)
            return runSpec(builder.build(), block)
        } finally {
            if (ours) remember(file)
        }
    }

    private suspend fun runSpec(spec: RuntimeSpec, block: suspend Running.() -> Unit): Termination {
        var running: Running? = null
        var finished = false
        val termination = try {
            AlexandriteRuntime.run(spec) {
                val current = Running(this, id, configFile, environment)
                running = current
                watch { current.stop(StopRequest.failure("a test double saw a problem")) }
                current.block()
                finished = true
            }
        } finally {
            watch(null)
        }
        if (!inspectTermination) verify(termination, finished || termination.request == running?.requested)
        return termination
    }

    /** Has the doubles call [stop] on each problem they meet. */
    private fun watch(stop: (() -> Unit)?) {
        models.forEach { it.onProblem = stop }
        channels.forEach { it.onProblem = stop }
    }

    /**
     * Writes the harness's config into [file], unless the run keeps the config that [file] holds, where it splices in
     * the harness's own sections that differ, and returns whether a harness wrote the file.
     */
    private fun prepare(file: Path): Boolean {
        val text = try {
            Files.readString(file)
        } catch (e: NoSuchFileException) {
            null
        } catch (e: CharacterCodingException) {
            return false
        }
        val ours = text != null && harnessFiles[normalized(file)] == text
        if (text == null || (ours && !keepsConfig)) {
            write(file, tree)
            return true
        }
        val current = writtenConfig(text) ?: return ours
        val spliced = spliced(text, current, owned)
        if (spliced != text) Files.writeString(file, spliced)
        return ours
    }

    /** The problems the doubles met during the run, as an error caused by [cause], null when there are none. */
    private fun doubleProblems(known: Map<ScriptedModel, Int>, cause: Throwable?): AssertionError? {
        val problems = models.flatMap { it.problems.drop(known.getValue(it)) } +
            channels.flatMap { it.created }.flatMap { it.violations }
        if (problems.isEmpty()) return null
        return AssertionError("The test doubles saw problems:" + problems.joinToString("") { "\n- $it" }, cause)
    }

    private fun verify(termination: Termination, blockEnded: Boolean) {
        if (!blockEnded) throw AssertionError("The run block was cut short by ${termination.request}.")
        val problems = termination.problems
        if (problems.isEmpty()) return
        val count = if (problems.size == 1) "a problem" else "${problems.size} problems"
        throw AssertionError("Stopping the runtime met $count:" + problems.joinToString("") { "\n- ${it.message}" })
    }

    /** What a [run] block sees of its runtime. */
    public class Running internal constructor(
        private val runtime: AlexandriteRuntime,
        id: String,
        private val configFile: Path?,
        private val environment: Map<String, String>,
    ) {
        private val files = runtime.services.resolver().get(key<PluginFiles>(id))

        @Volatile
        internal var requested: StopRequest? = null

        /** The data directory of the plugin under test. */
        public val dataDir: Path get() = files.dataDir

        /** The cache directory of the plugin under test. */
        public val cacheDir: Path get() = files.cacheDir

        /** Resolves any type the runtime binds. */
        public inline fun <reified T : Any> get(qualifier: String? = null): T = get(key<T>(qualifier))

        /** Every contribution to [T]. */
        public inline fun <reified T : Any> getAll(qualifier: String? = null): List<T> = getAll(key<T>(qualifier))

        public fun <T : Any> get(key: Key<T>): T = runtime.services.resolver().get(key)

        public fun <T : Any> getAll(key: Key<T>): List<T> = runtime.services.resolver().getAll(key)

        public fun stop(request: StopRequest = StopRequest.shutdown("requested by the test")) {
            requested = request
            runtime.stop(request)
        }

        /** The recording channel of the instance [name] of [type], while the instance is open. */
        public fun channel(
            name: String = TEST_INSTANCE.name,
            type: String = TEST_INSTANCE.type.value,
        ): RecordingChannel = channel(ChannelInstanceId(ChannelType(type), name))

        public fun channel(instance: ChannelInstanceId): RecordingChannel =
            get<ChannelDirectory>().channel(instance) as? RecordingChannel
                ?: throw NoSuchElementException("No recording channel of instance $instance is open.")

        /** The config file as the host would read it now. */
        public fun readConfig(): ConfigSource = ConfigFile.read(
            configFile ?: throw IllegalStateException("The harness names no config file."),
            environment,
        )

        /** The scripted model [id] that the runtime's model providers contribute. */
        public fun model(id: String = ScriptedModel.DEFAULT_ENDPOINT.value): ScriptedModel =
            getAll<ModelProvider>().flatMap { it.endpoints }.filterIsInstance<ScriptedModel>()
                .singleOrNull { it.id.value == id }
                ?: throw NoSuchElementException("No scripted model '$id' is contributed.")
    }

    public class Builder internal constructor(private val plugin: PluginIndex) {
        private val extras = mutableListOf<PluginIndex>()
        private val configs = LinkedHashMap<String, JsonObject>()
        private var dataRoot: Path? = null
        private var configFile: Path? = null
        private var keepsConfig = false
        private var environment: Map<String, String> = System.getenv()
        private val offLimits = mutableListOf<Path>()
        private var zone: ZoneId = ZoneOffset.UTC
        private var language: LanguageTag? = null
        private var texts: StateFlow<TextCatalog>? = null
        private var grace: Duration? = null
        private var agentPlaces: ((HostPaths) -> AgentPlaces)? = null
        private var inspectTermination = false
        private val models = mutableListOf<ScriptedModel>()
        private val watched = mutableListOf<ScriptedModel>()
        private val tools = mutableListOf<Tool>()
        private val sources = mutableListOf<ToolSource>()
        private val environments = mutableListOf<ExecutionEnvironment>()
        private val hooks = mutableListOf<Hook>()
        private val handlers = mutableListOf<CommandHandler>()
        private val channels = LinkedHashMap<ChannelType, LinkedHashMap<String, RecordingChannelConfig>>()
        private var submitter: RecordingTurnSubmitter? = null
        private var initiator: RecordingTurnInitiator? = null
        private var agent: RecordingAgent? = null
        private var states: TestChatStates? = null
        private var records: TestRecords? = null
        private var store: MemoryStore? = null
        private var floor: HardFloor? = null
        private var wakes: WakeScheduler? = null
        private var hostFloor = false

        internal var temporaryRoot: () -> Path = { Files.createTempDirectory("alexandrite-harness-") }

        /** Loads [index] beside the plugin under test. */
        public fun plugin(index: PluginIndex): Builder = apply { extras += index }

        /** The config of the plugin under test, below its config root. */
        public fun config(json: JsonObject): Builder = config(plugin, json)

        /** The config of the plugin under test as JSON text, below its config root. */
        public fun config(json: String): Builder = config(plugin, json)

        /** The config of [index], below its config root. */
        public fun config(index: PluginIndex, json: JsonObject): Builder = apply { configs[index.configRoot] = json }

        /** The config of [index] as JSON text, below its config root. */
        public fun config(index: PluginIndex, json: String): Builder = config(
            index,
            Json.parseToJsonElement(json) as? JsonObject
                ?: throw IllegalArgumentException("The config of plugin '${index.info.id}' is no JSON object."),
        )

        /** The runtime's data directory, a temporary one deleted when the run ends unless set. */
        public fun dataRoot(directory: Path): Builder = apply { dataRoot = directory }

        /**
         * The config file that each run writes the harness's config into afresh and reads, none unless set.
         *
         * A run keeps a file that no harness wrote, as [keptConfigFile] does.
         */
        public fun configFile(file: Path): Builder = apply {
            configFile = file
            keepsConfig = false
        }

        /**
         * The config file that runs keep: a run writes the harness's config there only when the file does not exist,
         * else splices in the sections of the harness's recording channels, and reads the file, so what the plugins
         * write reaches later runs.
         */
        public fun keptConfigFile(file: Path): Builder = apply {
            configFile = file
            keepsConfig = true
        }

        /** The variables that `${env:NAME}` references name in the config, the process's unless set. */
        public fun environment(environment: Map<String, String>): Builder =
            apply { this.environment = environment.toMap() }

        /** Declares [path] off-limits, as a host does. */
        public fun protect(path: Path): Builder = apply { offLimits.add(path) }

        /** The runtime's zone, UTC unless set. */
        public fun zone(zone: ZoneId): Builder = apply { this.zone = zone }

        /** The host's language of texts for people, `en` unless set. */
        public fun language(language: LanguageTag): Builder = apply { this.language = language }

        /** The texts the host gives the plugins in place of their own, none unless set. */
        public fun texts(texts: TextCatalog): Builder = texts(MutableStateFlow(texts).asStateFlow())

        /** The texts the host gives the plugins in place of their own, followed while a run runs. */
        public fun texts(texts: StateFlow<TextCatalog>): Builder = apply { this.texts = texts }

        public fun shutdownGrace(shutdownGrace: Duration): Builder = apply { grace = shutdownGrace }

        /** Lays the agents' places out as [places] makes them from the run's host paths. */
        public fun agentPlaces(places: (HostPaths) -> AgentPlaces): Builder = apply { agentPlaces = places }

        /** Lets [run] return a termination whose stop cut the block short or met problems. */
        public fun inspectTermination(): Builder = apply { inspectTermination = true }

        /** Contributes [model] through a model provider of the plugin `testkit`. */
        public fun model(model: ScriptedModel): Builder = apply {
            require(models.none { it.id == model.id }) { "The harness holds a scripted model '${model.id}' already." }
            models += model
        }

        /** Stops and fails a run at the first problem of [model], which the plugins reach through another endpoint. */
        public fun watch(model: ScriptedModel): Builder = apply { if (watched.none { it === model }) watched += model }

        /** Contributes [tool] through the plugin `testkit`. */
        public fun tool(tool: Tool): Builder = apply { tools += tool }

        /** Contributes [source], whose tools come and go while the run lasts, through the plugin `testkit`. */
        public fun toolSource(source: ToolSource): Builder = apply { sources += source }

        /** Contributes [environment] through the plugin `testkit`. */
        public fun executionEnvironment(environment: ExecutionEnvironment): Builder =
            apply { environments += environment }

        /** Binds [wakes] through the plugin `testkit`. */
        public fun wakeScheduler(wakes: WakeScheduler): Builder = apply { this.wakes = wakes }

        /** Contributes [hook] through the plugin `testkit`. */
        public fun hook(hook: Hook): Builder = apply { hooks += hook }

        /** Contributes [handler] through the plugin `testkit`. */
        public fun commandHandler(handler: CommandHandler): Builder = apply { handlers += handler }

        /**
         * Configures the instance [name] of the recording channel [type], whose plugin is `<type>-channel`, with the
         * users [operators] as its operators, [trusted] as the users its config trusts and [partLength] characters per
         * platform message.
         */
        public fun channel(
            name: String = TEST_INSTANCE.name,
            type: String = TEST_INSTANCE.type.value,
            operators: Set<String> = emptySet(),
            partLength: Int = RecordingChannel.DEFAULT_PART_LENGTH,
            trusted: Set<String> = emptySet(),
        ): Builder = apply {
            val instance = ChannelInstanceId(ChannelType(type), name)
            val instances = channels.getOrPut(instance.type) { LinkedHashMap() }
            require(name !in instances) { "The harness holds a recording channel of instance $instance already." }
            instances[name] = RecordingChannelConfig(operators.toSet(), partLength, trusted.toSet())
        }

        /** Binds [submitter] as the runtime's TurnSubmitter. */
        public fun turnSubmitter(submitter: RecordingTurnSubmitter): Builder = apply { this.submitter = submitter }

        /** Hands [initiator] every turn that a plugin's TurnInitiator starts, with the plugin's id. */
        public fun turnInitiator(initiator: RecordingTurnInitiator): Builder = apply { this.initiator = initiator }

        /** Binds the doubles of [agent] as the runtime's agent host APIs. */
        public fun agent(agent: RecordingAgent): Builder = apply { this.agent = agent }

        /** Keeps every plugin's chat states in [states]. */
        public fun chatStates(states: TestChatStates): Builder = apply { this.states = states }

        /** Keeps every plugin's records in [records]. */
        public fun records(records: TestRecords): Builder = apply { this.records = records }

        /** Binds every port of [store] as the runtime's store. */
        public fun store(store: MemoryStore): Builder = apply { this.store = store }

        /** Binds [floor] as the runtime's HardFloor. */
        public fun hardFloor(floor: HardFloor): Builder = apply {
            this.floor = floor
            hostFloor = false
        }

        /** Binds the [testHardFloor] over the run's host paths as the runtime's HardFloor. */
        public fun hardFloor(): Builder = apply {
            floor = null
            hostFloor = true
        }

        public fun build(): PluginHarness {
            val store = store
            val states = states
            val records = records
            require(store == null || states == null || states.store === store.chatStates) {
                "The harness binds one store, so its chat states must be TestChatStates(store) of its MemoryStore."
            }
            require(store == null || records == null || records.store === store.records) {
                "The harness binds one store, so its records must be TestRecords(store) of its MemoryStore."
            }
            val doubles = DoublesPlugin(
                models,
                tools,
                sources,
                hooks,
                handlers,
                submitter,
                initiator,
                agent,
                store,
                store?.chatStates ?: states?.store,
                store?.records ?: records?.store,
                floor,
                hostFloor,
                environments,
                wakes,
            )
            val recording = channels.map { (type, instances) -> RecordingChannelPlugin(type, instances.toMap()) }
            val owned = tree(recording.associate { it.configRoot to it.config() })
            val indexes = extras + recording + listOfNotNull(doubles.takeUnless { it.empty })
            return PluginHarness(
                plugin.info.id,
                indexes.fold(PluginSet.of(plugin), PluginSet::plus),
                merged(tree(configs), owned),
                owned,
                environment,
                dataRoot,
                configFile,
                keepsConfig,
                offLimits.toList(),
                zone,
                language,
                texts,
                grace,
                inspectTermination,
                temporaryRoot,
                (models + watched).distinct(),
                recording,
                agentPlaces,
            )
        }
    }

    public companion object {
        public fun builder(plugin: PluginIndex): Builder = Builder(plugin)
    }
}

private val PRETTY = Json { prettyPrint = true }

private const val CONFIG_ORIGIN = "PluginHarness.Builder.config(...)"

/** The config files that harnesses wrote, with the text each run left in them. */
private val harnessFiles = ConcurrentHashMap<Path, String>()

private fun normalized(file: Path): Path = file.toAbsolutePath().normalize()

/** Records the text of [file] as one a harness left there. */
private fun remember(file: Path) {
    val text = try {
        Files.readString(file)
    } catch (e: IOException) {
        null
    }
    if (text == null) harnessFiles.remove(normalized(file)) else harnessFiles[normalized(file)] = text
}

/** The config tree that holds each config of [roots] below its root. */
private fun tree(roots: Map<String, JsonObject>): JsonObject =
    roots.entries.fold(JsonObject(emptyMap())) { tree, (root, config) ->
        merged(tree, root.split('.').foldRight(config) { name, child -> JsonObject(mapOf(name to child)) })
    }

private fun write(file: Path, tree: JsonObject) {
    file.toAbsolutePath().parent?.let(Files::createDirectories)
    Files.writeString(file, PRETTY.encodeToString(JsonObject.serializer(), tree) + "\n")
}

/** [text] with each value of [owned] that [current], the object at [path] of [text], lacks or differs in spliced in. */
private fun spliced(text: String, current: JsonObject, owned: JsonObject, path: List<String> = emptyList()): String =
    owned.entries.fold(text) { edited, (name, value) ->
        val held = current[name]
        when {
            value is JsonObject && held is JsonObject -> spliced(edited, held, value, path + name)
            value == held -> edited
            else -> splicedConfig(edited, path + name, value)
        }
    }

private fun merged(tree: JsonObject, other: JsonObject): JsonObject = JsonObject(
    (tree.keys + other.keys).associateWith { name ->
        val mine = tree[name]
        val theirs = other[name]
        if (mine is JsonObject && theirs is JsonObject) merged(mine, theirs) else theirs ?: mine!!
    },
)

/** Deletes [root] without following links. */
@OptIn(ExperimentalPathApi::class)
private fun deleteTemporary(root: Path, failure: Throwable?) {
    try {
        root.deleteRecursively()
    } catch (e: IOException) {
        val error = IllegalStateException("Cannot delete the temporary data root $root: $e", e)
        if (failure == null) throw error
        failure.addSuppressed(error)
    }
}
