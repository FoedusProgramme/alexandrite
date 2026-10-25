package org.unitedsoftware.alexandrite.account.pleochroic

import org.unitedsoftware.alexandrite.internal.path.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The variables of the secrets file [file] (`NAME=value` per line, as `app`'s `secrets.env` keeps them). */
internal fun readSecrets(file: Path): Map<String, String> {
    val lines = try {
        Files.readAllLines(file)
    } catch (e: NoSuchFileException) {
        return emptyMap()
    }
    val secrets = LinkedHashMap<String, String>()
    for (raw in lines) {
        val line = raw.removeSuffix("\r")
        if (line.isBlank() || line.trimStart().startsWith("#")) continue
        val name = line.substringBefore('=', "").trim().removePrefix("export ").trim()
        if (name.isEmpty()) throw IOException("$file holds a line that names no variable.")
        secrets[name] = line.substringAfter('=')
    }
    return secrets
}

/** Writes [secrets] into [file], owner-only. */
internal fun writeSecretsFile(file: Path, secrets: Map<String, String>) {
    Files.createDirectories(file.toAbsolutePath().parent)
    val text = secrets.entries.joinToString("") { (name, value) -> "$name=$value\n" }
    writeAtomically(file, ownerOnly = true) { it.write(text.toByteArray(Charsets.UTF_8)) }
}
