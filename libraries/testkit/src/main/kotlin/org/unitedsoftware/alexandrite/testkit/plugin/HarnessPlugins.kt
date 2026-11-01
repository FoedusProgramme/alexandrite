package org.unitedsoftware.alexandrite.testkit.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.Dependency
import org.unitedsoftware.alexandrite.sdk.di.container.DependencyKind
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.di.container.binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.runtime.WakeScheduler
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.store.ChatStateStore
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.store.MediaStore
import org.unitedsoftware.alexandrite.sdk.store.RecordStore
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.tool.HardFloor
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolSource
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.TrustControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiation
import org.unitedsoftware.alexandrite.sdk.turn.TurnSubmitter
import org.unitedsoftware.alexandrite.sdk.turn.UsageReports
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.RecordingTurnInitiator
import org.unitedsoftware.alexandrite.testkit.RecordingTurnSubmitter
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.testHardFloor
import java.util.concurrent.CopyOnWriteArrayList

/** A plugin of the harness, whose config root is that of a third-party plugin. */
internal abstract class HarnessPlugin(id: String, name: String) : PluginIndex {
    override val info: PluginInfo =
        PluginInfo(id, name, "test", "", AlexandriteSdk.API_VERSION, emptyList(), javaClass.name)
    override val configRoot: String = PluginIds.thirdPartyRoot(id)

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}

/** The plugin `testkit`, which binds the doubles the harness was given. */
internal class DoublesPlugin(
    models: List<ScriptedModel>,
    tools: List<Tool>,
    sources: List<ToolSource>,
    hooks: List<Hook>,
    handlers: List<CommandHandler>,
    submitter: RecordingTurnSubmitter?,
    initiator: RecordingTurnInitiator?,
    agent: RecordingAgent?,
    store: MemoryStore?,
    chatStates: ChatStateStore?,
    records: RecordStore?,
    floor: HardFloor?,
    /** Whether to bind the test floor over the run's host paths. */
    hostFloor: Boolean,
    environments: List<ExecutionEnvironment>,
    wakes: WakeScheduler?,
) : HarnessPlugin(ID, "Test doubles") {
    private val bindings = buildList {
        if (models.isNotEmpty()) {
            val provider = object : ModelProvider {
                override val endpoints: List<ModelEndpoint> = models.toList()
            }
            add(instanceBinding(key<ModelProvider>(), provider, ID, "ScriptedModel provider", multi = true))
        }
        tools.forEach { add(instanceBinding(key<Tool>(), it, ID, "Tool ${it.definition.name}", multi = true)) }
        sources.forEach {
            add(instanceBinding(key<ToolSource>(), it, ID, "ToolSource ${it.namespace}", multi = true))
        }
        hooks.forEach { add(instanceBinding(key<Hook>(), it, ID, "Hook ${it.javaClass.name}", multi = true)) }
        environments.forEach {
            add(instanceBinding(key<ExecutionEnvironment>(), it, ID, "ExecutionEnvironment ${it.name}", multi = true))
        }
        handlers.forEach {
            add(instanceBinding(key<CommandHandler>(), it, ID, "CommandHandler ${it.javaClass.name}", multi = true))
        }
        submitter?.let { add(instanceBinding(key<TurnSubmitter>(), it, ID, "RecordingTurnSubmitter")) }
        initiator?.let { recorder ->
            val initiation = object : TurnInitiation {
                override fun initiate(plugin: String, turn: InitiatedTurn) = recorder.record(plugin, turn)
            }
            add(instanceBinding(key<TurnInitiation>(), initiation, ID, "RecordingTurnInitiator"))
        }
        agent?.let {
            add(instanceBinding(key<TurnControl>(), it.turns, ID, "RecordingAgent.turns"))
            add(instanceBinding(key<ConversationControl>(), it.conversations, ID, "RecordingAgent.conversations"))
            add(instanceBinding(key<ChatSettings>(), it.settings, ID, "RecordingAgent.settings"))
            add(instanceBinding(key<ChatLinks>(), it.links, ID, "RecordingAgent.links"))
            add(instanceBinding(key<TrustControl>(), it.trust, ID, "RecordingAgent.trust"))
            add(instanceBinding(key<PermissionRules>(), it.rules, ID, "RecordingAgent.rules"))
            add(instanceBinding(key<AgentCatalog>(), it.catalog, ID, "RecordingAgent.catalog"))
            add(instanceBinding(key<UsageReports>(), it.usage, ID, "RecordingAgent.usage"))
        }
        store?.let {
            add(instanceBinding(key<ConversationStore>(), it.conversations, ID, "MemoryStore.conversations"))
            add(instanceBinding(key<TranscriptStore>(), it.transcripts, ID, "MemoryStore.transcripts"))
            add(instanceBinding(key<MediaStore>(), it.media, ID, "MemoryStore.media"))
        }
        chatStates?.let { add(instanceBinding(key<ChatStateStore>(), it, ID, "ChatStateStore")) }
        records?.let { add(instanceBinding(key<RecordStore>(), it, ID, "RecordStore")) }
        floor?.let { add(instanceBinding(key<HardFloor>(), it, ID, "HardFloor")) }
        wakes?.let { add(instanceBinding(key<WakeScheduler>(), it, ID, "WakeScheduler")) }
        if (hostFloor) {
            val paths = Dependency(key<HostPaths>(), DependencyKind.INSTANCE, "paths")
            add(
                binding(key<HardFloor>(), ID, "HardFloor of the host's places", dependencies = listOf(paths)) { r ->
                    testHardFloor(r.get(key<HostPaths>()))
                },
            )
        }
    }

    val empty: Boolean get() = bindings.isEmpty()

    override fun bindings(): List<Binding<*>> = bindings

    companion object {
        const val ID: String = "testkit"
    }
}

/** The settings of one instance of a recording channel. */
@Serializable
internal class RecordingChannelConfig(
    val operators: Set<String> = emptySet(),
    val partLength: Int = RecordingChannel.DEFAULT_PART_LENGTH,
    val trusted: Set<String> = emptySet(),
)

/** The plugin `<type>-channel`, whose channel of [type] is a [RecordingChannel] per instance of [instances]. */
internal class RecordingChannelPlugin(
    val type: ChannelType,
    private val instances: Map<String, RecordingChannelConfig>,
) : HarnessPlugin("$type-channel", "Recording channel '$type'") {
    private val id = info.id
    private val settings = key<RecordingChannelConfig>(id)
    private val channel = key<RecordingChannel>(id)

    /** The channels of the run, in the order the runtime created them. */
    val created: MutableList<RecordingChannel> = CopyOnWriteArrayList()

    /** Called on each violation that a channel of the run sees. */
    @Volatile
    var onProblem: (() -> Unit)? = null

    /** The config below the plugin's root. */
    fun config(): JsonObject = JsonObject(
        mapOf(
            PluginIds.INSTANCES_KEY to JsonObject(
                instances.mapValues { (_, config) ->
                    Json.encodeToJsonElement(RecordingChannelConfig.serializer(), config)
                },
            ),
        ),
    )

    override fun configSections(): List<ConfigSectionSpec<*>> = listOf(
        ConfigSectionSpec(
            settings,
            "",
            RecordingChannelConfig.serializer(),
            "RecordingChannelConfig",
            Scope.CHANNEL_INSTANCE,
        ),
    )

    override fun bindings(): List<Binding<*>> = listOf(
        binding(
            channel,
            id,
            "RecordingChannel",
            Scope.CHANNEL_INSTANCE,
            listOf(
                Dependency(key<ChannelInstance>(), DependencyKind.INSTANCE, "instance"),
                Dependency(settings, DependencyKind.INSTANCE, "config"),
                Dependency(key<TurnSubmitter>(), DependencyKind.OPTIONAL, "submitter"),
            ),
            managed = false,
        ) { r ->
            val config = r.get(settings)
            RecordingChannel(
                r.get(key<ChannelInstance>()).id,
                config.operators,
                config.partLength,
                r.getOrNull(key<TurnSubmitter>()),
                config.trusted,
            ).also { channel ->
                channel.onProblem = { onProblem?.invoke() }
                created += channel
            }
        },
        binding(
            key<Channel>(),
            id,
            "RecordingChannel",
            Scope.CHANNEL_INSTANCE,
            listOf(Dependency(channel, DependencyKind.INSTANCE, "channel")),
            multi = true,
            managed = false,
            name = type.value,
        ) { r -> r.get(channel) },
        binding(
            key<StopWatch>(id),
            id,
            "RecordingChannel stop",
            Scope.CHANNEL_INSTANCE,
            listOf(Dependency(channel, DependencyKind.INSTANCE, "channel")),
        ) { r -> StopWatch(r.get(channel)) },
    )
}

/** Tells its channel when the instance stops. */
internal class StopWatch(private val channel: RecordingChannel) : Lifecycle {
    override fun onStop() {
        channel.stopped = true
    }
}

/** The deliveries scripted for the next sends, to one chat or to any. */
internal class ScriptedDeliveries {
    private val queues = HashMap<ChatAddress?, ArrayDeque<Delivery>>()

    /** Queues [deliveries] for [chat], or for any chat when it is null. */
    fun script(deliveries: Array<out Delivery>, chat: ChatAddress? = null) {
        queues.getOrPut(chat) { ArrayDeque() }.addAll(deliveries)
    }

    /** The next delivery scripted for [chat], else for any chat, null when none is left. */
    fun next(chat: ChatAddress): Delivery? = queues[chat]?.removeFirstOrNull() ?: queues[null]?.removeFirstOrNull()
}
