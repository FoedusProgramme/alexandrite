package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.unitedsoftware.alexandrite.runtime.RuntimeProblemKind
import org.unitedsoftware.alexandrite.runtime.channel.PluginChannel
import org.unitedsoftware.alexandrite.runtime.channel.pluginChannel
import org.unitedsoftware.alexandrite.runtime.engine.missingEngines
import org.unitedsoftware.alexandrite.runtime.plugin.BuiltInLayer
import org.unitedsoftware.alexandrite.runtime.plugin.DisabledPlugin
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.runtime.store.duplicateStores
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.ConfigSource
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.problem.Problem

internal class EnabledPlugin(
    val member: PluginSet.Member,
    val bindings: List<Binding<*>>,
    /** The plugin's channel type, null when it is no channel plugin. */
    val channelType: ChannelType?,
    val config: PluginConfig,
) {
    val id: String get() = member.id
}

internal class ConfigResolution(
    val enabled: List<EnabledPlugin>,
    val disabled: List<DisabledPlugin>,
    val unknownPluginConfig: List<String>,
    val problems: List<Problem>,
)

/** Resolves the config of [members], adding the values of the secrets it decodes to [secrets]. */
internal fun resolveConfig(
    members: List<PluginSet.Member>,
    source: ConfigSource,
    secrets: MutableCollection<String>,
): ConfigResolution {
    val problems = mutableListOf<Problem>()
    val enabled = mutableListOf<PluginSet.Member>()
    val disabled = mutableListOf<DisabledPlugin>()
    val failedRoots = mutableListOf<String>()
    for (member in members.sortedBy { it.id }) {
        val reason = try {
            disabledReason(member, source)
        } catch (e: ConfigException) {
            problems += invalidConfig(e, member.id)
            failedRoots += member.index.configRoot
            continue
        }
        if (reason == null) enabled += member else disabled += DisabledPlugin(member.id, reason)
    }
    problems += overlappingRoots(enabled)
    val bindings = enabled.associate { it.id to it.index.bindings() }
    val channels = enabled.associate { it.id to pluginChannel(it.id, bindings.getValue(it.id)) }
    channels.values.flatMapTo(problems) { it.problems }
    problems += duplicateChannelTypes(channels)
    problems += listOfNotNull(duplicateStores(enabled, bindings))
    problems += missingEngines(enabled, bindings)
    problems += missingRequirements(members, enabled, disabled)
    val unknown = unknownConfig(members, source, failedRoots)
    problems += unknown.problems
    val configured = enabled.map { member ->
        val channel = channels.getValue(member.id)
        val config = if (channel.problems.isNotEmpty()) {
            NO_CONFIG
        } else {
            try {
                configBindings(member.index, channel.type, source, secrets)
            } catch (e: ConfigException) {
                problems += invalidConfig(e, member.id)
                NO_CONFIG
            }
        }
        EnabledPlugin(member, bindings.getValue(member.id), channel.type, config)
    }
    return ConfigResolution(configured, disabled, unknown.pluginPaths, problems)
}

/** Why [member] is off, null when it is on. */
internal fun disabledReason(member: PluginSet.Member, source: ConfigSource): DisabledPlugin.Reason? {
    val root = member.index.configRoot
    val tree = source.tree(root)
    val enabled = tree?.get(PluginIds.ENABLED_KEY)?.let { value ->
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw ConfigException("$root.${PluginIds.ENABLED_KEY}", "must be true or false")
    }
    val optIn = !member.explicit && member.row?.layer in OPT_IN_LAYERS
    return when {
        enabled == false -> DisabledPlugin.Reason.ENABLED_FALSE
        tree == null && optIn -> DisabledPlugin.Reason.NOT_CONFIGURED
        else -> null
    }
}

private fun overlappingRoots(enabled: List<PluginSet.Member>): List<Problem> {
    val roots = enabled.map { it.id to it.index.configRoot }
    return roots.flatMapIndexed { index, (id, root) ->
        roots.drop(index + 1)
            .filter { (_, other) -> root == other || root.startsWith("$other.") || other.startsWith("$root.") }
            .map { (otherId, other) ->
                Problem(
                    RuntimeProblemKind.OVERLAPPING_ROOTS,
                    "Overlapping config roots: plugin '$id' reads '$root' and plugin '$otherId' reads '$other'. " +
                        "Give each plugin a config root of its own.",
                    null,
                )
            }
    }
}

private fun duplicateChannelTypes(channels: Map<String, PluginChannel>): List<Problem> = channels.entries
    .filter { it.value.type != null }
    .groupBy({ it.value.type }, { it.key })
    .filterValues { it.size > 1 }
    .map { (type, ids) ->
        Problem(
            RuntimeProblemKind.DUPLICATE_CHANNEL_TYPE,
            "Duplicate channel type '$type': plugins ${ids.joinToString(" and ") { "'$it'" }} declare it. " +
                "Switch all but one of them off.",
            null,
        )
    }

private fun missingRequirements(
    members: List<PluginSet.Member>,
    enabled: List<PluginSet.Member>,
    disabled: List<DisabledPlugin>,
): List<Problem> {
    val on = enabled.mapTo(HashSet()) { it.id }
    val off = disabled.associateBy { it.id }
    val roots = members.associate { it.id to it.index.configRoot }
    return enabled.flatMap { member ->
        member.plugin.info.requires.distinct().filter { it !in on }.mapNotNull { required ->
            val root = roots[required]
            val why = when (off[required]?.reason) {
                DisabledPlugin.Reason.ENABLED_FALSE -> "which is disabled by `$root.${PluginIds.ENABLED_KEY} = false`"

                DisabledPlugin.Reason.NOT_CONFIGURED ->
                    "which is not configured. Add a '$root' section to switch it on"

                null -> if (root == null) "which is not loaded. Add it to the plugin set" else return@mapNotNull null
            }
            Problem(
                RuntimeProblemKind.MISSING_REQUIREMENT,
                "Plugin '${member.id}' requires plugin '$required', $why.",
                member.id,
            )
        }
    }
}

private class UnknownConfig(val pluginPaths: List<String>, val problems: List<Problem>)

private fun unknownConfig(
    members: List<PluginSet.Member>,
    source: ConfigSource,
    failedRoots: List<String>,
): UnknownConfig {
    val roots = members.mapTo(sortedSetOf()) { it.index.configRoot }
    val pluginPaths = mutableListOf<String>()
    val problems = mutableListOf<Problem>()

    fun walk(prefix: String?, keys: Set<String>) {
        for (key in keys.sorted()) {
            val path = if (prefix == null) key else "$prefix.$key"
            when {
                key.isEmpty() || '.' in key -> problems += Problem(
                    RuntimeProblemKind.INVALID_CONFIG,
                    "Invalid config key '$path': a key may not be empty or contain '.'. Nest the objects instead.",
                    null,
                )

                path in roots -> Unit

                path == PluginIds.THIRD_PARTY_ROOT || roots.any { it.startsWith("$path.") } -> {
                    val tree = try {
                        source.tree(path)
                    } catch (e: ConfigException) {
                        if (failedRoots.none { it.startsWith("$path.") }) problems += invalidConfig(e, null)
                        null
                    }
                    walk(path, tree?.keys.orEmpty())
                }

                path.startsWith("${PluginIds.THIRD_PARTY_ROOT}.") -> pluginPaths += path

                else -> problems += Problem(
                    RuntimeProblemKind.UNKNOWN_CONFIG,
                    "Unknown config at '$path': no plugin reads it. " +
                        "Config roots of the plugin set: ${roots.joinToString().ifEmpty { "none" }}.",
                    null,
                )
            }
        }
    }

    walk(null, source.tree("")?.keys.orEmpty())
    return UnknownConfig(pluginPaths, problems)
}

private fun invalidConfig(e: ConfigException, plugin: String?): Problem =
    Problem(RuntimeProblemKind.INVALID_CONFIG, e.message ?: e.toString(), plugin)

private val OPT_IN_LAYERS = setOf(BuiltInLayer.CHANNEL, BuiltInLayer.PROVIDER)

private val NO_CONFIG = PluginConfig(emptyList(), emptyList())
