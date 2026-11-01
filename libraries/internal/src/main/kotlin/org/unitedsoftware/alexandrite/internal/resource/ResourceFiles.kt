package org.unitedsoftware.alexandrite.internal.resource

import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors
import kotlin.io.path.isRegularFile

/**
 * The paths, relative to [directory], of the files below [directory] in the class path entry (a directory or a jar)
 * that [url], the resource [name], comes from.
 */
public fun resourceFiles(url: URL, name: String, directory: String): List<String> =
    walked(url, name, directory, direct = false, read = false).map { it.first }

/** The text of each file directly in [directory], the resource that [url] names, by its name. */
public fun resourceTexts(url: URL, directory: String): List<Pair<String, String>> =
    walked(url, directory, directory, direct = true, read = true).map { (path, bytes) ->
        path to String(checkNotNull(bytes), Charsets.UTF_8)
    }

/**
 * The files below [directory] in the entry of [url], the resource [name], or when [direct] the files directly in it, each
 * with its bytes when [read].
 */
private fun walked(
    url: URL,
    name: String,
    directory: String,
    direct: Boolean,
    read: Boolean,
): List<Pair<String, ByteArray?>> = when (url.protocol) {
    "file" -> {
        val entry = generateSequence(Paths.get(url.toURI())) { it.parent }.elementAt(name.trim('/').split('/').size)
        val base = entry.resolve(directory)
        if (!Files.isDirectory(base)) {
            emptyList()
        } else {
            Files.walk(base).use { paths ->
                paths.filter { it.isRegularFile() && (!direct || it.parent == base) }
                    .map { base.relativize(it).joinToString("/") to if (read) Files.readAllBytes(it) else null }
                    .collect(Collectors.toList())
            }
        }
    }

    "jar" -> {
        val connection = (url.openConnection() as JarURLConnection).apply { useCaches = false }
        val prefix = "${directory.trim('/')}/"
        connection.jarFile.use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) }
                .filter { !direct || '/' !in it.name.removePrefix(prefix) }
                .map { entry ->
                    entry.name.removePrefix(prefix) to
                        if (read) jar.getInputStream(entry).use { it.readBytes() } else null
                }
                .toList()
        }
    }

    else -> throw IOException("Cannot list the resources beside $url: only directories and jars can be listed.")
}.sortedBy { it.first }
