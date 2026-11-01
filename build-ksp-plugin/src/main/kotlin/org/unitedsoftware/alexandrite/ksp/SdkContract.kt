package org.unitedsoftware.alexandrite.ksp

internal const val DI = "org.unitedsoftware.alexandrite.sdk.di"
internal const val SINGLETON = "$DI.Singleton"
internal const val CHANNEL_INSTANCE_SCOPED = "$DI.ChannelInstanceScoped"
internal const val INJECT = "$DI.Inject"
internal const val NAMED = "$DI.Named"
internal const val BINDS = "$DI.Binds"
internal const val CONTRIBUTE = "$DI.Contribute"
internal const val PROVIDES = "$DI.Provides"
internal const val CONTRIBUTED_SPI = "$DI.ContributedSpi"
internal const val BOUND_SPI = "$DI.BoundSpi"
internal const val CONTRIBUTION_NAME = "name"
internal const val NAMED_SPI = "named"
internal const val PLUGIN_LOCAL = "$DI.PluginLocal"
internal const val KEY_FUNCTION = "$DI.key"

internal const val CONTAINER = "$DI.container"
internal const val BINDING = "$CONTAINER.Binding"
internal const val DEPENDENCY = "$CONTAINER.Dependency"
internal const val DEPENDENCY_KIND = "$CONTAINER.DependencyKind"
internal const val SCOPE = "$CONTAINER.Scope"
internal const val BINDING_FUNCTION = "$CONTAINER.binding"

internal const val PLUGIN = "org.unitedsoftware.alexandrite.sdk.plugin.Plugin"
internal const val HOOK = "org.unitedsoftware.alexandrite.sdk.hook.Hook"
internal const val PLUGIN_INDEX = "org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex"
internal const val PLUGIN_INFO = "org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo"
internal const val INTERNAL_API = "org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi"

internal const val CHANNEL = "org.unitedsoftware.alexandrite.sdk.channel.Channel"
internal const val CHANNEL_INSTANCE = "org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance"

internal const val CONFIG_SECTION = "org.unitedsoftware.alexandrite.sdk.config.ConfigSection"
internal const val CONFIG_SECTION_SPEC = "org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec"
internal const val SERIALIZABLE = "kotlinx.serialization.Serializable"

internal const val LIST = "kotlin.collections.List"
internal const val LIST_OF = "kotlin.collections.listOf"
internal const val EMPTY_LIST = "kotlin.collections.emptyList"
internal const val LAZY = "kotlin.Lazy"
internal const val UNIT = "kotlin.Unit"
internal const val STRING = "kotlin.String"
internal const val OPT_IN = "kotlin.OptIn"
internal const val REQUIRES_OPT_IN = "kotlin.RequiresOptIn"
internal const val SUPPRESS = "kotlin.Suppress"

internal val COMPONENT_ANNOTATIONS = listOf(PLUGIN, SINGLETON, CHANNEL_INSTANCE_SCOPED, BINDS, CONTRIBUTE)

internal val INDEXED_CLASS_ANNOTATIONS = COMPONENT_ANNOTATIONS + CONFIG_SECTION

internal val FUNCTION_BINDING_ANNOTATIONS = listOf(SINGLETON, CHANNEL_INSTANCE_SCOPED, BINDS, CONTRIBUTE, NAMED)

/** The `AlexandriteSdk.API_VERSION` that the index and the descriptor record. */
internal const val SDK_API_VERSION = 2

/** Lowercase words of letters and digits joined by single hyphens. */
private val ID = Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*)*")
internal val PLUGIN_ID = ID
internal val CHANNEL_TYPE = ID
internal const val RESERVED_PREFIX = "alexandrite-"
internal const val THIRD_PARTY_ROOT = "plugins"

/** The config key that switches a plugin on and off. */
internal const val ENABLED = "enabled"

/** The config key that holds the config of a channel plugin's channel instances. */
internal const val INSTANCES = "instances"

/** A dot-separated config path below the config root. */
internal val CONFIG_PATH = Regex("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*")

internal const val SERVICE_FILE = "META-INF/services/$PLUGIN_INDEX"
internal const val DESCRIPTOR_DIRECTORY = "META-INF/alexandrite"
internal const val DESCRIPTOR_EXTENSION = "json"
