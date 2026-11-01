package org.unitedsoftware.alexandrite.mcp

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.internal.path.resolveConfigured
import org.unitedsoftware.alexandrite.internal.sandbox.HostPattern
import org.unitedsoftware.alexandrite.internal.sandbox.Protect
import org.unitedsoftware.alexandrite.internal.sandbox.proxyVariables
import org.unitedsoftware.alexandrite.internal.sandbox.selectEnvironment
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.sandbox.NetworkAccess
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** The sandbox a server runs in: its [environment], its [boundary] while it has no network, and the hosts it may reach. */
internal class SandboxSpec(
    val environment: ExecutionEnvironment,
    val boundary: Boundary,
    val allowed: List<HostPattern>,
) {
    /** [boundary] with its network through the proxy on the loopback [port]. */
    fun through(port: Int): Boundary = boundary.withNetwork(NetworkAccess.Proxy(port), proxyVariables(port))
}

/**
 * The sandbox that [config] asks for the server at the config path [key] working in [cwd], null for none.
 *
 * Throws a [ConfigException] for an environment that is missing or unavailable, a host that is none, or a path that
 * cannot be resolved.
 */
internal fun serverSandbox(
    key: String,
    config: ServerSandboxConfig,
    cwd: Path?,
    environments: List<ExecutionEnvironment>,
    paths: HostPaths,
): SandboxSpec? {
    val environment = environment(key, config.type, environments) ?: return null
    config.network.allowedDomains.firstOrNull { HostPattern.parse(it) == null }?.let {
        throw ConfigException(
            "$key.network.allowedDomains",
            HostPattern.notAHost(it),
        )
    }
    val protect = Protect.of(config.protect)
    val own = paths.alexandritePlaces
    val writable = listOfNotNull(cwd) + config.allowWrite.map { resolved(paths, "$key.allowWrite", it) }
    val boundary = Boundary(
        writable = writable,
        writeDenied = protect.paths.map { resolved(paths, "$key.protect", it) } +
            if (protect.alexandrite) own else emptyList(),
        protectedNames = protect.names,
        readDenied = own + config.denyRead.map { resolved(paths, "$key.denyRead", it) },
        readable = writable,
    )
    logger.info("The MCP server at {} runs in the {} sandbox", key.removeSuffix(".sandbox"), environment.name)
    return SandboxSpec(environment, boundary, config.network.allowedDomains.mapNotNull(HostPattern::parse))
}

/** The environment that [type] names, null for none or for `auto` on a host without one. */
private fun environment(key: String, type: String, environments: List<ExecutionEnvironment>): ExecutionEnvironment? =
    try {
        selectEnvironment(type, environments, { it.name }, { it.available() }) { why ->
            logger.warn(
                "The MCP server at {} asks for a sandbox, but {}: it runs on the host",
                key.removeSuffix(".sandbox"),
                why,
            )
        }
    } catch (e: IllegalArgumentException) {
        throw ConfigException("$key.type", e.message.orEmpty(), e)
    }

/** [entry] as an absolute path: itself, under the home directory for `~/`, or relative to the config file. */
private fun resolved(paths: HostPaths, key: String, entry: String): Path {
    val path = try {
        Paths.get(expandHome(entry))
    } catch (e: InvalidPathException) {
        throw ConfigException(key, "'$entry' is no valid path", e)
    }
    return resolveConfigured(path.toString(), paths.configFile?.parent) ?: throw ConfigException(
        key,
        "'$entry' is relative, but the host read no config file to resolve it against: give an absolute path",
    )
}

private val logger = LoggerFactory.getLogger(SandboxSpec::class.java)
