package org.unitedsoftware.alexandrite.discovery

/** The flags a secret commonly follows on a command line, lowercase. */
private val SECRET_FLAGS = setOf("--token", "--api-key", "--apikey", "--key", "--secret", "--password", "-k")

/**
 * The indices of [server]'s [DiscoveredMcpServer.args] that look like a secret embedded in the command line
 * itself: the value right after one of `--token`, `--api-key`, `--apikey`, `--key`, `--secret`, `--password` or
 * `-k` (case-insensitively), as long as that value is not itself another flag. Best effort, and deliberately
 * narrow: a server that passes its secret some other way (baked into [DiscoveredMcpServer.command], split across
 * several arguments, …) is not caught, which is safer than guessing at every argument that merely looks opaque.
 */
public fun secretArgs(server: DiscoveredMcpServer): Map<Int, String> {
    val found = linkedMapOf<Int, String>()
    server.args.forEachIndexed { index, value ->
        if (index == 0) return@forEachIndexed
        val flag = server.args[index - 1].lowercase()
        if (flag in SECRET_FLAGS && value.isNotBlank() && !value.startsWith("-")) found[index] = value
    }
    return found
}
