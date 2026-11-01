package org.unitedsoftware.alexandrite.runtime.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.path.hasPosixPermissions
import org.unitedsoftware.alexandrite.internal.path.syncDirectory
import org.unitedsoftware.alexandrite.internal.path.writeNewFile
import org.unitedsoftware.alexandrite.runtime.AlexandriteRuntime
import org.unitedsoftware.alexandrite.runtime.ConfigFileException
import org.unitedsoftware.alexandrite.runtime.channel.pluginChannel
import org.unitedsoftware.alexandrite.runtime.plugin.PluginSet
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditException
import org.unitedsoftware.alexandrite.sdk.config.ConfigEditRefusal
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.ConfigPath
import org.unitedsoftware.alexandrite.sdk.config.HostConfig
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.di.container.Scope
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** [value] set at [path] of the config, or that member removed when [value] is null. */
internal class ConfigChange(val path: List<String>, val value: JsonElement?)

/** The config file as an edit found it. */
internal class OpenFile(val target: Path, val bytes: ByteArray, val text: String)

/** Where the writer keeps the previous text of the config file [target], the real file a link names. */
@InternalAlexandriteApi
public fun configBackup(target: Path): Path = target.resolveSibling("${target.fileName}.bak")

internal fun refused(refusal: ConfigEditRefusal, message: String, cause: Throwable? = null): ConfigEditException =
    ConfigEditException(refusal, message, cause)

/** Serializes every config change of the process. */
private val LOCK = Mutex()

private val SECRET_NAME = Secret.serializer().descriptor.serialName

private val logger: Logger = LoggerFactory.getLogger(AlexandriteRuntime::class.java)

/** Runs [block] on the IO dispatcher under the lock of every config change, to its end once it began. */
internal suspend fun <T> lockedConfig(block: () -> T): T = LOCK.withLock {
    withContext(NonCancellable) { withContext(Dispatchers.IO) { block() } }
}

/** The config [file] as it is now, refused when it cannot be changed. */
internal fun openConfig(file: Path?): OpenFile {
    val target = writableTarget(file)
    val bytes = try {
        Files.readAllBytes(target)
    } catch (e: IOException) {
        throw refused(ConfigEditRefusal.IO_ERROR, "Cannot read the config file $target: $e", e)
    }
    val text = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw refused(ConfigEditRefusal.INVALID_FILE, "The config file $target is not UTF-8 text.", e)
    }
    return OpenFile(target, bytes, text)
}

/** Writes [text] in the place of [file], whose previous bytes its backup then keeps. */
internal fun replaceConfig(file: OpenFile, text: String) {
    try {
        replace(file.target, file.bytes, text.toByteArray(Charsets.UTF_8))
    } catch (e: IOException) {
        throw refused(ConfigEditRefusal.IO_ERROR, "Cannot write the config file ${file.target}: $e", e)
    }
}

/** What [block] returns, or [refusal] saying [what] when it finds the config file unreadable. */
internal fun <T> readable(refusal: ConfigEditRefusal, what: String, target: Path, block: () -> T): T = try {
    block()
} catch (e: ConfigFileException) {
    throw refused(refusal, "$what: ${e.message}", e)
} catch (e: ConfigException) {
    throw refused(refusal, "$what: Config file $target: ${e.message}", e)
}

/**
 * Refuses [changes] below the root of [index], an empty path the whole root, when one of them writes anything but a
 * `${env:NAME}` reference where a [Secret] decodes from [written].
 */
internal fun checkSecrets(
    index: PluginIndex,
    channelType: ChannelType?,
    written: JsonObject,
    changes: List<ConfigChange>,
) {
    val root = index.configRoot.split('.')
    val secrets = mutableListOf<Pair<SecretPlace, JsonElement>>()
    replacedSecrets(index, channelType != null, written) { place, value -> value.also { secrets += place to it } }
    val set = changes.filter { it.value != null }.map { root + it.path }
    val leaked = secrets.firstOrNull { (place, value) ->
        !isReference(value) && set.any { place.keys.take(it.size) == it }
    } ?: return
    throw refused(
        ConfigEditRefusal.SECRET,
        "The change would write a secret at '${leaked.first.path}' into the config file. Secrets stay out of " +
            "the file: write an \${env:NAME} reference instead, whose value `alexandrite secret set NAME` keeps.",
    )
}

/** [written] with each [Secret] of the sections of [members] that is no reference shown as hidden. */
internal fun maskedSecrets(members: List<PluginSet.Member>, written: JsonObject): JsonObject =
    replacedSecrets(members, written) { _, value ->
        if (isReference(value)) value else JsonPrimitive(HostConfig.HIDDEN_SECRET)
    }

/**
 * [text], a config file of [plugins], with each secret it writes as a value that [previous] does not hold replaced by a
 * `${env:NAME}` reference, every other character kept; [name] keeps a secret's value and names its variable.
 */
@InternalAlexandriteApi
public fun referencedSecrets(
    plugins: PluginSet,
    previous: String,
    text: String,
    name: (path: String, value: String) -> String,
): String {
    val written = writtenConfig(text) ?: return text
    val kept = HashMap<String, JsonElement>()
    replacedSecrets(plugins.members, writtenConfig(previous) ?: JsonObject(emptyMap())) { place, value ->
        value.also { kept[place.path] = it }
    }
    val moved = mutableSetOf<List<String>>()
    val referenced = replacedSecrets(plugins.members, written) { place, value ->
        if (value !is JsonPrimitive || isReference(value) || kept[place.path] == value) {
            value
        } else {
            moved += place.keys
            JsonPrimitive(EnvReferences.of(name(place.path, value.content)))
        }
    }
    return moved.fold(text) { spliced, keys -> splicedConfig(spliced, keys, checkNotNull(referenced.at(keys))) }
}

/** [written] with each [Secret] of the sections of [members] replaced by what [secret] makes of it at its path. */
private fun replacedSecrets(
    members: List<PluginSet.Member>,
    written: JsonObject,
    secret: (place: SecretPlace, value: JsonElement) -> JsonElement,
): JsonObject = members.fold(written) { replaced, member ->
    val channel = pluginChannel(member.id, member.index.bindings()).type != null
    replacedSecrets(member.index, channel, replaced, secret)
}

/**
 * [written] with each [Secret] of the sections of [index], those of each channel instance too for a [channel] plugin,
 * replaced by what [secret] makes of it at its path.
 */
private fun replacedSecrets(
    index: PluginIndex,
    channel: Boolean,
    written: JsonObject,
    secret: (place: SecretPlace, value: JsonElement) -> JsonElement,
): JsonObject {
    var replaced = written
    val root = index.configRoot.split('.')
    val instances = if (channel) {
        (written.at(root + PluginIds.INSTANCES_KEY) as? JsonObject)?.keys.orEmpty().map {
            listOf(PluginIds.INSTANCES_KEY, it)
        }
    } else {
        emptyList()
    }
    for (section in index.configSections()) {
        val bases = if (section.scope == Scope.CHANNEL_INSTANCE) instances else listOf(emptyList())
        val path = section.path.split('.').filter(String::isNotEmpty)
        for (base in bases) {
            val keys = root + base + path
            val tree = replaced.at(keys) ?: continue
            replaced =
                replaced.with(keys, withSecrets(section.deserializer.descriptor, tree, SecretPlace(keys), secret))
        }
    }
    return replaced
}

/** Where a secret sits: the keys of the objects on the way to it, and the [path] that names it. */
private class SecretPlace(
    val keys: List<String>,
    val path: String = ConfigPath.format(keys),
    private val listed: Boolean = false,
) {
    fun child(key: String): SecretPlace =
        SecretPlace(if (listed) keys else keys + key, ConfigPath.join(path, key), listed)

    fun item(index: Int): SecretPlace = SecretPlace(keys, "$path[$index]", listed = true)
}

/** [element] as [descriptor] decodes it at [place], each [Secret] in it replaced by what [secret] makes of it. */
private fun withSecrets(
    descriptor: SerialDescriptor,
    element: JsonElement,
    place: SecretPlace,
    secret: (place: SecretPlace, value: JsonElement) -> JsonElement,
): JsonElement = when {
    descriptor.serialName.removeSuffix("?") == SECRET_NAME -> secret(place, element)

    descriptor.isInline -> withSecrets(descriptor.getElementDescriptor(0), element, place, secret)

    element is JsonObject && descriptor.kind == StructureKind.CLASS -> JsonObject(
        element.mapValues { (key, value) ->
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                value
            } else {
                withSecrets(descriptor.getElementDescriptor(index), value, place.child(key), secret)
            }
        },
    )

    element is JsonObject && descriptor.kind == StructureKind.MAP -> JsonObject(
        element.mapValues { (key, value) ->
            withSecrets(descriptor.getElementDescriptor(1), value, place.child(key), secret)
        },
    )

    element is JsonArray && descriptor.kind == StructureKind.LIST -> JsonArray(
        element.mapIndexed { index, value ->
            withSecrets(descriptor.getElementDescriptor(0), value, place.item(index), secret)
        },
    )

    else -> element
}

/** This object with [value] at [keys], whose objects on the way exist. */
private fun JsonObject.with(keys: List<String>, value: JsonElement): JsonObject {
    if (keys.isEmpty()) return value as JsonObject
    val key = keys.first()
    val next = if (keys.size == 1) value else (this[key] as JsonObject).with(keys.drop(1), value)
    return JsonObject(this + (key to next))
}

/** The file to write: the configured one, or the target it links to. */
private fun writableTarget(file: Path?): Path {
    val configured = file?.toAbsolutePath()
        ?: throw refused(ConfigEditRefusal.NO_FILE, "The runtime was started without a config file to write to.")
    val target = try {
        configured.toRealPath()
    } catch (e: NoSuchFileException) {
        throw refused(ConfigEditRefusal.NO_FILE, "The config file $configured does not exist.", e)
    } catch (e: IOException) {
        throw refused(ConfigEditRefusal.IO_ERROR, "Cannot find the config file $configured: $e", e)
    }
    if (!Files.isWritable(target)) {
        throw refused(ConfigEditRefusal.READ_ONLY, "The config file $target is read-only.")
    }
    if (!Files.isWritable(checkNotNull(target.parent))) {
        throw refused(ConfigEditRefusal.READ_ONLY, "The directory of the config file $target cannot be written.")
    }
    return target
}

private fun isReference(value: JsonElement): Boolean =
    value == JsonNull || (value is JsonPrimitive && value.isString && EnvReferences.whole(value.content) != null)

/** Puts [next] in the place of [target] through its backup, which then keeps [previous]. */
private fun replace(target: Path, previous: ByteArray, next: ByteArray) {
    val permissions = if (hasPosixPermissions(target)) Files.getPosixFilePermissions(target) else null
    val backup = configBackup(target)
    try {
        create(backup, next, permissions)
        Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Throwable) {
        try {
            Files.deleteIfExists(backup)
        } catch (cleanup: IOException) {
            e.addSuppressed(cleanup)
        }
        throw e
    }
    try {
        create(backup, previous, permissions)
    } catch (e: IOException) {
        logger.warn("Cannot keep the previous config file as {}: {}", backup, e.toString())
    }
    syncDirectory(checkNotNull(target.parent))
}

/** Writes [bytes] to a new file at [destination] with [permissions], in the place of what is there. */
private fun create(destination: Path, bytes: ByteArray, permissions: Set<PosixFilePermission>?) {
    Files.deleteIfExists(destination)
    writeNewFile(destination, permissions) { it.write(bytes) }
}
